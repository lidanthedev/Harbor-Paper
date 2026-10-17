package dev.triassic.harbor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Ports harbor.sh install + proot exec logic to run with
 * ROOTFS_DIR = plugins/Harbor/container.
 *
 * Silent: all routine logging goes to fine-level when config silent:true.
 */
public final class HarborManager {

    /** Hard cap on captured output so unlimited runs cannot exhaust memory. */
    private static final int MAX_CAPTURE_BYTES = 8 * 1024 * 1024;

    private final HarborPlugin plugin;
    private final File rootfsDir;
    private final File tmpDir;
    private final Object installLock = new Object();

    public HarborManager(HarborPlugin plugin) {
        this.plugin = plugin;
        this.rootfsDir = new File(plugin.getDataFolder(), "container");
        this.tmpDir = new File(System.getProperty("java.io.tmpdir"), "harbor-" + plugin.getDataFolder().getName());
    }

    public File getRootfsDir() {
        return rootfsDir;
    }

    public boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /** Returns {arch, archAlt} e.g. {x86_64, amd64} or null if unsupported. */
    public String[] detectArch() {
        String raw = System.getProperty("os.arch", "").toLowerCase();
        // normalize JVM arch names to uname -m style
        String arch;
        if (raw.equals("amd64") || raw.equals("x86_64") || raw.equals("x86-64")) {
            arch = "x86_64";
        } else if (raw.equals("aarch64") || raw.equals("arm64")) {
            arch = "aarch64";
        } else {
            // fall back to `uname -m` for exotic JVMs
            try {
                Process p = new ProcessBuilder("uname", "-m").start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().toLowerCase();
                p.waitFor(5, TimeUnit.SECONDS);
                if (out.equals("x86_64") || out.equals("aarch64")) {
                    arch = out;
                } else {
                    return null;
                }
            } catch (Exception e) {
                return null;
            }
        }
        String archAlt = arch.equals("x86_64") ? "amd64" : "arm64";
        return new String[]{arch, archAlt};
    }

    public boolean isInstalled() {
        return new File(rootfsDir, ".installed").exists();
    }

    public void log(String msg) {
        if (plugin.isSilent()) {
            plugin.getLogger().fine("[Harbor] " + msg);
        } else {
            plugin.getLogger().info(msg);
        }
    }

    /** Blocking install. Call async. Returns true on success. */
    public boolean ensureInstalled() {
        synchronized (installLock) {
            if (isInstalled()) {
                return true;
            }
            if (!isLinux()) {
                plugin.getLogger().warning("[Harbor] proot requires Linux. Refusing install on "
                        + System.getProperty("os.name"));
                return false;
            }
            String[] archPair = detectArch();
            if (archPair == null) {
                plugin.getLogger().warning("[Harbor] Unsupported CPU architecture: "
                        + System.getProperty("os.arch"));
                return false;
            }
            String arch = archPair[0];

            String alpineVersion = plugin.getConfig().getString("alpine-version", "3.22");
            String alpineFull = plugin.getConfig().getString("alpine-full-version", "3.22.6");
            String apkToolsVersion = plugin.getConfig().getString("apk-tools-version", "2.14.12-r0");
            String prootVersion = plugin.getConfig().getString("proot-version", "5.3.0");
            // Upstream 3.18 pins 404 (apk-tools-static pruned from dl-cdn).
            // Auto-migrate stale configs from plugin v1.0.0 so existing
            // servers don't need to hand-edit config.yml.
            if (alpineVersion.startsWith("3.18") || apkToolsVersion.equals("2.14.0-r2")) {
                alpineVersion = "3.22";
                alpineFull = "3.22.6";
                apkToolsVersion = "2.14.12-r0";
                log("Migrating stale Alpine 3.18 pins to 3.22.6 / apk-tools-static 2.14.12-r0");
            }
            List<String> dns = plugin.getConfig().getStringList("dns");
            if (dns.isEmpty()) {
                dns = Arrays.asList("1.1.1.1", "1.0.0.1");
            }

            try {
                if (!rootfsDir.mkdirs() && !rootfsDir.isDirectory()) {
                    plugin.getLogger().warning("[Harbor] Cannot create " + rootfsDir);
                    return false;
                }
                if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
                    plugin.getLogger().warning("[Harbor] Cannot create tmp " + tmpDir);
                    return false;
                }

                log("Downloading Alpine minirootfs " + alpineFull + " (" + arch + ") ...");
                File rootfsTar = new File(tmpDir, "rootfs.tar.gz");
                download("https://dl-cdn.alpinelinux.org/alpine/v" + alpineVersion
                        + "/releases/" + arch
                        + "/alpine-minirootfs-" + alpineFull + "-" + arch + ".tar.gz", rootfsTar);
                runOrThrow(Arrays.asList("tar", "-xzf", rootfsTar.getAbsolutePath(),
                        "-C", rootfsDir.getAbsolutePath()), null, 300);

                log("Downloading apk-tools-static + proot ...");
                File apkFile = new File(tmpDir, "apk-tools-static.apk");
                download("https://dl-cdn.alpinelinux.org/alpine/v" + alpineVersion
                        + "/main/" + arch + "/apk-tools-static-" + apkToolsVersion + ".apk", apkFile);

                File prootBin = new File(rootfsDir, "usr/local/bin/proot");
                if (!prootBin.getParentFile().mkdirs() && !prootBin.getParentFile().isDirectory()) {
                    throw new IllegalStateException("Cannot create " + prootBin.getParent());
                }
                download("https://github.com/proot-me/proot/releases/download/v" + prootVersion
                        + "/proot-v" + prootVersion + "-" + arch + "-static", prootBin);

                // harbor.sh: tar -xzf apk-tools-static.apk -C /tmp/  then /tmp/sbin/apk.static ... --root $ROOTFS_DIR
                runOrThrow(Arrays.asList("tar", "-xzf", apkFile.getAbsolutePath(),
                        "-C", tmpDir.getAbsolutePath()), null, 120);

                File apkStatic = new File(tmpDir, "sbin/apk.static");
                if (!apkStatic.isFile()) {
                    // some apk layouts extract to tmpDir/sbin directly, fallback search
                    apkStatic = findApkStatic(tmpDir);
                }
                if (apkStatic == null || !apkStatic.isFile()) {
                    throw new IllegalStateException("apk.static not found after extracting " + apkFile);
                }
                runOrThrow(Arrays.asList("chmod", "755", apkStatic.getAbsolutePath(),
                        prootBin.getAbsolutePath()), null, 30);
                // --no-scripts: post-install scripts/triggers call chroot(2),
                // which restricted containers (Pterodactyl) deny with
                // "Operation not permitted". The payload installs fine
                // without them; openrc runlevels are meaningless under proot.
                ProcessResult apkResult = run(Arrays.asList(
                        apkStatic.getAbsolutePath(),
                        "-X", "https://dl-cdn.alpinelinux.org/alpine/v" + alpineVersion + "/main/",
                        "-U", "--allow-untrusted", "--no-scripts",
                        "--root", rootfsDir.getAbsolutePath(),
                        "add", "alpine-base", "apk-tools"), null, 600);
                // Belt and braces: even with --no-scripts, tolerate a
                // non-zero exit as long as the base system actually landed.
                if (apkResult.exitCode != 0
                        && !new File(rootfsDir, "bin/sh").isFile()
                        && !new File(rootfsDir, "bin/busybox").isFile()) {
                    throw new IllegalStateException("apk bootstrap failed (" + apkResult.exitCode + "):\n"
                            + truncate(apkResult.output, 2000));
                }
                if (apkResult.exitCode != 0) {
                    log("apk bootstrap exit " + apkResult.exitCode
                            + " ignored, base system present: " + truncate(apkResult.output, 500));
                }
                runOrThrow(Arrays.asList("chmod", "755", prootBin.getAbsolutePath()), null, 30);

                // resolv.conf
                File resolv = new File(rootfsDir, "etc/resolv.conf");
                if (!resolv.getParentFile().mkdirs() && !resolv.getParentFile().isDirectory()) {
                    throw new IllegalStateException("Cannot create " + resolv.getParent());
                }
                StringBuilder sb = new StringBuilder();
                for (String ns : dns) {
                    sb.append("nameserver ").append(ns).append("\n");
                }
                Files.writeString(resolv.toPath(), sb.toString(), StandardCharsets.UTF_8);

                // cleanup + marker (mirrors harbor.sh)
                deleteQuietly(rootfsTar);
                deleteQuietly(apkFile);
                deleteQuietly(new File(tmpDir, "sbin"));
                if (!new File(rootfsDir, ".installed").createNewFile()) {
                    throw new IllegalStateException("Cannot write .installed marker");
                }
                log("Install complete: " + rootfsDir.getAbsolutePath());
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("[Harbor] Install failed: " + e.getMessage());
                if (!plugin.isSilent()) {
                    e.printStackTrace();
                } else {
                    plugin.getLogger().fine("Install failure: " + e);
                }
                return false;
            }
        }
    }

    /** Execute `shellCmd` via /bin/sh -c inside proot. Blocking; call async. */
    public ProcessResult execInRootfs(String shellCmd, long timeoutSeconds) throws Exception {
        if (!isInstalled()) {
            throw new IllegalStateException("Rootfs not installed yet. Run /harbor install first.");
        }
        if (!isLinux()) {
            throw new IllegalStateException("proot requires Linux.");
        }
        File prootBin = new File(rootfsDir, "usr/local/bin/proot");
        if (!prootBin.isFile()) {
            throw new IllegalStateException("proot binary missing: " + prootBin);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBin.getAbsolutePath());
        cmd.add("--rootfs=" + rootfsDir.getAbsolutePath());
        // --link2symlink emulates hard links with symlinks. Only needed on
        // filesystems without real hard-link support (some FUSE/exFAT mounts).
        // On a normal ext4/overlayfs volume it breaks `apk` installs of
        // packages containing hardlinks (gcc/g++/binutils fail to install with
        // "Operation not permitted") and litters the rootfs with un-removable
        // .l2s.* chains, so it is opt-in.
        if (plugin.getConfig().getBoolean("proot-link2symlink", false)) {
            cmd.add("--link2symlink");
        }
        // Deliberately NOT passing --kill-on-exit: its entire job is to kill
        // every tracee when proot exits, which makes it impossible to leave a
        // backgrounded daemon (dropbear) running after this one-shot session
        // ends. Without it, `cmd &` survives the session as intended.
        cmd.add("--root-id");
        cmd.add("--cwd=/root");
        cmd.add("--bind=/proc");
        cmd.add("--bind=/dev");
        cmd.add("--bind=/sys");
        cmd.add("--bind=/tmp");
        // Expose the server's real working directory (the /home/container
        // volume) inside the rootfs, so /sh can touch the actual server files
        // directly (`ls /mnt`, `cat /mnt/server.properties`, ...). proot is not
        // a sandbox either way (see docs/CONTAINER-LIMITS.md), so this only
        // makes the access convenient. proot bindings are path substitutions,
        // but we create the guest directory anyway so the mount point is a real
        // directory in the rootfs too.
        String realPwdGuest = plugin.getConfig().getString("real-pwd-mount", "/mnt");
        String realPwdHost = System.getProperty("user.dir");
        if (realPwdGuest != null && realPwdHost != null) {
            realPwdGuest = realPwdGuest.trim();
            while (realPwdGuest.length() > 1 && realPwdGuest.endsWith("/")) {
                realPwdGuest = realPwdGuest.substring(0, realPwdGuest.length() - 1);
            }
            // "/" would shadow the whole rootfs; relative paths are not binds.
            if (realPwdGuest.startsWith("/") && realPwdGuest.length() > 1
                    && new File(realPwdHost).isDirectory()) {
                new File(rootfsDir, realPwdGuest).mkdirs();
                cmd.add("--bind=" + realPwdHost + ":" + realPwdGuest);
            }
        }
        cmd.add("/bin/sh");
        cmd.add("-c");
        cmd.add(shellCmd);
        // Host env leaks into proot ($HOME=/home/container, host PATH/TERM),
        // so pin the in-rootfs values instead of inheriting them.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", "/root");
        env.put("PWD", "/root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("SHELL", "/bin/sh");
        env.put("USER", "root");
        env.put("LOGNAME", "root");
        env.put("TERM", "xterm");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("HOSTNAME", "harbor");
        return run(cmd, env, null, timeoutSeconds);
    }

    /**
     * Launch a long-running service so it survives this proot session.
     *
     * Every fd must be redirected: if the backgrounded process keeps our
     * stdout pipe open, the session never sees EOF, the launching command
     * appears to hang and any `> log` is never flushed. Redirect stdout+stderr
     * to a persistent file inside the rootfs (NOT /tmp, which proot re-binds
     * per session) and stdin from /dev/null.
     *
     * @return the log path inside the rootfs
     */
    public String detach(String name, String command) {
        String log = "/root/.harbor/" + name + ".log";
        execShell("mkdir -p /root/.harbor");
        execShell(": > " + log); // truncate so each launch starts clean

        // Pre-flight diagnostics only: start it, give it ~3s to fail or bind,
        // then kill it. A service that cannot start (port in use, bad key)
        // exits immediately and its error is now in the log. Portables: no
        // `timeout` dependency, since busybox may not ship it.
        execInRootfsSafe("{ " + command + " </dev/null >>" + log + " 2>&1 & "
                + "p=$!; sleep 3; kill $p 2>/dev/null; } 2>/dev/null", 20);

        // Then launch it for real, fully detached. The `sleep`/`kill` above
        // guarantees the port is free again before this runs.
        // </dev/null matters: without it the child inherits our stdout pipe,
        // so the session never sees EOF and the launch looks like it hung.
        execShell("nohup " + command + " </dev/null >>" + log + " 2>&1 & "
                + "disown 2>/dev/null || true");
        execShell("sleep 1");
        return log;
    }

    /** Like execShell but keeps the exit code (for pre-flight diagnostics). */
    private ProcessResult execInRootfsSafe(String shellCmd, long timeoutSeconds) {
        try {
            return execInRootfs(shellCmd, timeoutSeconds);
        } catch (Exception e) {
            log("pre-flight failed: " + e.getMessage());
            return new ProcessResult(-1, e.getMessage());
        }
    }

    // ---- helpers used by Dropbear / startup commands ----

    public org.bukkit.configuration.file.FileConfiguration config() {
        return plugin.getConfig();
    }

    /** Convenience: run a command in the rootfs with no timeout; swallow errors. */
    public String execShell(String shellCmd) {
        return execShell(shellCmd, 0);
    }

    /** @param timeoutSeconds 0 or less = no timeout. */
    public String execShell(String shellCmd, long timeoutSeconds) {
        try {
            return execInRootfs(shellCmd, timeoutSeconds).output;
        } catch (Exception e) {
            log("execShell failed (" + shellCmd + "): " + e.getMessage());
            return "";
        }
    }

    /** true when `name` resolves inside the rootfs (command -v). */
    public boolean hasBinary(String name) {
        return !execShell("command -v " + name + " 2>/dev/null").trim().isEmpty();
    }

    /** true when the absolute path exists inside the rootfs. */
    public boolean fileExists(String absolutePathInRootfs) {
        // read straight off the host FS: the rootfs dir is a real directory
        File f = new File(rootfsDir, absolutePathInRootfs.replaceFirst("^/+", ""));
        return f.exists();
    }

    /** Write a file inside the rootfs via a base64 pipe (avoids quoting hell). */
    public boolean writeFileInRootfs(String absolutePathInRootfs, String content, String octalMode) {
        String b64;
        try {
            b64 = java.util.Base64.getEncoder()
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        String cmd = "mkdir -p \"$(dirname " + absolutePathInRootfs + ")\" && "
                + "echo '" + b64 + "' | base64 -d > " + absolutePathInRootfs;
        if (octalMode != null) {
            cmd += " && chmod " + octalMode + " " + absolutePathInRootfs;
        }
        String out = execShell(cmd);
        return fileExists(absolutePathInRootfs) && !out.contains("not found");
    }

    /** Run every command in `commands` sequentially, in one async pass. */
    public List<String> runStartupCommands(List<String> commands) {
        List<String> report = new ArrayList<>();
        if (commands == null || commands.isEmpty()) {
            return report;
        }
        for (String raw : commands) {
            if (raw == null || raw.isBlank() || raw.trim().startsWith("#")) {
                continue;
            }
            String cmd = raw.trim();
            String out;
            int code;
            try {
                ProcessResult r = execInRootfs(cmd, 300);
                out = r.output;
                code = r.exitCode;
            } catch (Exception e) {
                out = e.getMessage();
                code = -1;
            }
            String line = "[startup] $ " + cmd + " -> exit " + code;
            log(line + (out.isBlank() ? "" : "\n" + truncate(out, 1000)));
            report.add(line);
        }
        return report;
    }

    public static final class ProcessResult {
        public final int exitCode; // -1 = timeout
        public final String output;
        public ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private ProcessResult run(List<String> cmd, File workDir, long timeoutSeconds) throws Exception {
        return run(cmd, null, workDir, timeoutSeconds);
    }

    /**
     * @param env when non-null, replaces the inherited environment entirely
     *            (prevents host HOME/PATH/TERM leaking into the rootfs).
     */
    private ProcessResult run(List<String> cmd, Map<String, String> env, File workDir,
                             long timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir);
        }
        if (env != null) {
            pb.environment().clear();
            pb.environment().putAll(env);
        }
        // keep environment minimal but functional
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        Process p = pb.start();

        // Drain stdout/stderr on a thread so a long or unlimited run cannot
        // fill the OS pipe buffer and deadlock the child.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (sink.size() < MAX_CAPTURE_BYTES) {
                        sink.write(buf, 0, Math.min(n, MAX_CAPTURE_BYTES - sink.size()));
                    }
                }
            } catch (Exception ignored) {
            }
        }, "harbor-output-drain");
        reader.setDaemon(true);
        reader.start();

        // timeoutSeconds <= 0 => no timeout, wait as long as it takes.
        boolean done = timeoutSeconds > 0
                ? p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                : waitForever(p);
        if (!done) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
            reader.join(2000);
            return new ProcessResult(-1, readSink(sink) + "\n[timed out after "
                    + timeoutSeconds + "s]");
        }
        reader.join(5000);
        return new ProcessResult(p.exitValue(), readSink(sink));
    }

    /** Block until the process exits, no timeout. */
    private static boolean waitForever(Process p) {
        try {
            p.waitFor();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String readSink(ByteArrayOutputStream sink) {
        String out = sink.toString(StandardCharsets.UTF_8);
        if (sink.size() >= MAX_CAPTURE_BYTES) {
            out += "\n...[output capture capped at " + MAX_CAPTURE_BYTES + " bytes]";
        }
        return out;
    }

    private void runOrThrow(List<String> cmd, File workDir, long timeoutSeconds) throws Exception {
        ProcessResult r = run(cmd, workDir, timeoutSeconds);
        if (r.exitCode != 0) {
            throw new IllegalStateException("Command failed (" + r.exitCode + "): "
                    + String.join(" ", cmd) + "\n" + truncate(r.output, 2000));
        }
    }

    private void download(String url, File dest) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(30000);
        con.setReadTimeout(60000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Harbor-Paper/1.0.0");
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Download failed HTTP " + code + ": " + url);
        }
        if (!dest.getParentFile().mkdirs() && !dest.getParentFile().isDirectory()) {
            throw new IllegalStateException("Cannot create " + dest.getParent());
        }
        try (InputStream in = con.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        if (dest.length() == 0) {
            throw new IllegalStateException("Downloaded empty file: " + url);
        }
    }

    private static File findApkStatic(File tmpDir) {
        // recursive search, depth 3 max (mirrors /tmp/sbin/apk.static layout)
        List<File> stack = new ArrayList<>();
        stack.add(tmpDir);
        for (int depth = 0; depth < 4 && !stack.isEmpty(); depth++) {
            List<File> next = new ArrayList<>();
            for (File d : stack) {
                File[] kids = d.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    if (k.isFile() && k.getName().equals("apk.static")) return k;
                    if (k.isDirectory()) next.add(k);
                }
            }
            stack = next;
        }
        return null;
    }

    private static void deleteQuietly(File f) {
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteQuietly(k);
            }
            Files.deleteIfExists(f.toPath());
        } catch (Exception ignored) {
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\r", "").trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "\n...[truncated " + (t.length() - max) + " chars]";
    }
}
