package dev.triassic.harbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Automatic dropbear setup for the unprivileged container path.
 *
 * The three container quirks handled here:
 *   * dropbearkey dies on getrandom() in a seccomp-restricted container
 *     -> generate host keys with OpenSSH ssh-keygen -A instead.
 *   * dropbear needs its own key format and the right subpackage
 *     -> apk add dropbear dropbear-convert, then dropbearconvert.
 *   * dropbear resolves the user home from /etc/passwd, not $HOME
 *     -> authorized_keys must live in /root/.ssh, not /home/container.
 *
 * Three operations, deliberately kept separate:
 *   start()     - kill + relaunch using what is already installed (fast, no
 *                 package or key work). This is what "restart" means.
 *   reinstall() - packages + host keys + conversion + authorized_keys, then start.
 *   stop()      - kill the listener and any per-connection children.
 *
 * Note that stop()/start() necessarily drop any *active* SSH sessions: the
 * process being killed is the one serving them.
 */
public final class Dropbear {

    private static final String PID_FILE = "/root/.harbor/dropbear.pid";

    private final HarborManager manager;

    public Dropbear(HarborManager manager) {
        this.manager = manager;
    }

    /** Result of an operation, for reporting back to the operator. */
    public static final class Result {
        public final boolean ok;
        public final int port;
        public final String fingerprint;
        public final String notes;
        /** true when the rootfs is not set up for dropbear yet (call reinstall()). */
        public final boolean needsSetup;

        Result(boolean ok, int port, String fingerprint, String notes, boolean needsSetup) {
            this.ok = ok;
            this.port = port;
            this.fingerprint = fingerprint;
            this.notes = notes;
            this.needsSetup = needsSetup;
        }

        Result(boolean ok, int port, String fingerprint, String notes) {
            this(ok, port, fingerprint, notes, false);
        }
    }

    public boolean isEnabled() {
        return manager.config().getBoolean("dropbear.enabled", true);
    }

    public int port() {
        return manager.config().getInt("dropbear.port", 25584);
    }

    // ---- operations --------------------------------------------------------

    /**
     * Restart dropbear using what is already installed: kill the running one and
     * launch it again with the existing host key. No package install, no key
     * generation - so it is fast and safe to run any time.
     *
     * If the rootfs has never been set up, returns {@code needsSetup} so the
     * caller can fall back to {@link #reinstall()}.
     */
    public Result start() {
        int port = port();
        String key = currentHostKey();
        if (key == null || !manager.hasBinary("dropbear")) {
            return new Result(false, port, "", "not set up yet (run /harbor dropbear reinstall)", true);
        }
        return launchAndReport(port, key, new StringBuilder());
    }

    /** Stop the listener and any per-connection children. */
    public Result stop() {
        int port = port();
        killExisting(port);
        boolean listening = !listener(port).trim().isEmpty();
        return new Result(!listening, port, "",
                listening ? "still listening on " + port : "stopped");
    }

    /**
     * Full setup: packages, host keys, conversion, authorized_keys, then start.
     * Idempotent - every step is skipped when it is already done. This is the
     * "reinstall" path, not a plain restart.
     */
    public Result reinstall() {
        int port = port();
        StringBuilder notes = new StringBuilder();

        // 1. Packages. dropbear-convert (NOT dropbear-openrc) provides
        //    dropbearconvert; openssh-keygen provides ssh-keygen.
        // The log lines are the only visible progress during a first boot: this
        // apk add pulls the package index over the network and is by far the
        // slowest step, and execShell swallows apk's own output.
        manager.log("dropbear: installing packages (dropbear, dropbear-convert, openssh-keygen) - slowest step");
        manager.execShell("apk add --no-cache dropbear dropbear-convert openssh-keygen");
        if (!manager.hasBinary("ssh-keygen")) {
            notes.append("ssh-keygen still missing; ");
        }
        if (!manager.hasBinary("dropbearconvert")) {
            notes.append("dropbearconvert still missing; ");
        }

        // 2. Host keys (dropbearkey cannot write keys here: getrandom is blocked).
        manager.log("dropbear: creating the host key");
        manager.execShell("mkdir -p /etc/ssh");
        manager.execShell("ssh-keygen -A 2>&1", 120);
        if (!manager.fileExists("/etc/ssh/ssh_host_ed25519_key")) {
            manager.execShell("ssh-keygen -t ed25519 -N '' -f /etc/ssh/ssh_host_ed25519_key "
                    + "-C 'harbor host key' 2>&1", 120);
        }
        boolean ed25519 = manager.fileExists("/etc/ssh/ssh_host_ed25519_key");

        // 3. Convert OpenSSH -> dropbear format.
        manager.log("dropbear: converting the host key to dropbear format");
        manager.execShell("mkdir -p /etc/dropbear");
        String srcKey = ed25519 ? "/etc/ssh/ssh_host_ed25519_key" : "/etc/ssh/ssh_host_rsa_key";
        String dstKey = ed25519
                ? "/etc/dropbear/dropbear_ed25519_host_key"
                : "/etc/dropbear/dropbear_rsa_host_key";
        if (!manager.fileExists(dstKey)) {
            manager.execShell("dropbearconvert openssh dropbear " + srcKey + " " + dstKey + " 2>&1");
        }
        if (!manager.fileExists(dstKey)) {
            notes.append("host key conversion failed; ");
        }
        manager.execShell("chmod 600 " + dstKey + " 2>/dev/null");

        // 4. authorized_keys in root's real home (dropbear reads /etc/passwd).
        String pubKey = manager.config().getString("dropbear.authorized-key", "");
        manager.log("dropbear: installing the authorized key");
        manager.execShell("mkdir -p /root/.ssh && chmod 700 /root/.ssh");
        if (pubKey != null && !pubKey.isBlank()) {
            manager.writeFileInRootfs("/root/.ssh/authorized_keys", pubKey.trim() + "\n", "600");
        } else if (!manager.fileExists("/root/.ssh/authorized_keys")) {
            // Migrate anything left in the shell HOME by earlier attempts, but
            // never clobber an existing /root/.ssh/authorized_keys: doing so
            // silently locks out keys that were already working.
            manager.execShell("[ -f /home/container/.ssh/authorized_keys ] && "
                    + "cp /home/container/.ssh/authorized_keys /root/.ssh/authorized_keys "
                    + "&& chmod 600 /root/.ssh/authorized_keys");
        }
        if (!manager.fileExists("/root/.ssh/authorized_keys")) {
            notes.append("no authorized_keys yet (add dropbear.authorized-key to config); ");
        }

        // 5+6. (Re)start and report.
        return launchAndReport(port, dstKey, notes);
    }

    /** Host key fingerprint without restarting anything. */
    public String fingerprint() {
        List<String> candidates = new ArrayList<>(Arrays.asList(
                "/etc/ssh/ssh_host_ed25519_key",
                "/etc/ssh/ssh_host_rsa_key",
                "/etc/dropbear/dropbear_ed25519_host_key"));
        for (String c : candidates) {
            if (manager.fileExists(c)) {
                String fp = manager.execShell("ssh-keygen -lf " + c + " 2>/dev/null").trim();
                if (!fp.isEmpty()) {
                    return fp;
                }
            }
        }
        return "(no host key)";
    }

    // ---- internals ---------------------------------------------------------

    /** dropbear-format host key that already exists, or null. */
    private String currentHostKey() {
        for (String c : new String[]{"/etc/dropbear/dropbear_ed25519_host_key",
                "/etc/dropbear/dropbear_rsa_host_key"}) {
            if (manager.fileExists(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Kill our own running listener, identified by the pid file dropbear wrote
     * with -P.
     *
     * Deliberately NOT `pkill -x dropbear`: this container shares the host's PID
     * namespace, so a name-based kill also takes out a dropbear belonging to a
     * *different* Harbor (a standalone instance next to the plugin, or vice
     * versa) - including the one carrying your SSH session. Observed exactly
     * that while testing standalone mode: its dropbear setup killed the
     * plugin's dropbear on another port and dropped the live session.
     *
     * `pkill -f 'dropbear .*-p <port>'` is equally unusable: that regex matches
     * this command line and the proot launcher's argv, i.e. it shoots the
     * caller. The pid file is the only precise handle available.
     *
     * The pid is verified against /proc/<pid>/comm before killing, so a stale
     * file pointing at a recycled pid can never take out an unrelated process.
     * A stray we cannot identify is left alone: it holds the port, the
     * pre-flight launch fails to bind, and the failure is reported with its log.
     */
    private void killExisting(int port) {
        manager.execShell("p=$(cat " + PID_FILE + " 2>/dev/null); "
                + "if [ -n \"$p\" ] && [ \"$(cat /proc/$p/comm 2>/dev/null)\" = dropbear ]; then "
                + "kill \"$p\" 2>/dev/null; sleep 1; [ -d /proc/$p ] && kill -9 \"$p\" 2>/dev/null; "
                + "fi; "
                + "rm -f " + PID_FILE);
        manager.execShell("sleep 1");
    }

    /** Launch dropbear detached, then verify the socket, the process and the log. */
    private Result launchAndReport(int port, String dstKey, StringBuilder notes) {
        // -F is REQUIRED here: without it dropbear tries to daemonise and
        // misbehaves inside this container. -F keeps it in the foreground and
        // HarborManager#detach does the detaching (stdin from /dev/null,
        // stdout+stderr to a persistent log inside the rootfs, otherwise the
        // launch hangs and no log is ever written). -E = log to stderr,
        // -K 60 = keepalive.
        killExisting(port);
        // a stale pid file makes a real failure look like a clean start
        manager.execShell("rm -f " + PID_FILE);
        manager.log("dropbear: launching on port " + port);
        String logPath = manager.detach("dropbear", "dropbear " + argsFor(port, dstKey));

        String listener = listener(port);
        boolean listening = !listener.trim().isEmpty();
        // Our own pid, not `pidof dropbear`: another Harbor's dropbear must not
        // make a failed start look alive.
        boolean alive = !manager.execShell(
                "p=$(cat " + PID_FILE + " 2>/dev/null); "
                        + "[ -n \"$p\" ] && [ -d /proc/$p ] && echo yes").trim().isEmpty();
        String log = manager.execShell("cat " + logPath + " 2>/dev/null");
        String fingerprint = manager.execShell(
                "ssh-keygen -lf " + sshKeyFor(dstKey) + " 2>/dev/null").trim();
        if (!listening) {
            notes.append("not listening on ").append(port)
                    .append(", log ").append(logPath).append(": ")
                    .append(log.isBlank() ? "(empty)" : HarborManager.truncate(log, 400)).append("; ");
        }
        if (!alive) {
            notes.append("process not running; ");
        }
        return new Result(listening, port, fingerprint, notes.toString().trim());
    }

    private String argsFor(int port, String dstKey) {
        boolean allowPw = manager.config().getBoolean("dropbear.allow-password-login", false);
        String args = "-F -E -K 60 -p " + port + " -r " + dstKey + " -P " + PID_FILE;
        if (!allowPw) {
            args += " -g"; // no root password logins; keys only
        }
        return args;
    }

    private String listener(int port) {
        return manager.execShell("ss -tlnp 2>/dev/null | grep ':" + port + "' "
                + "|| netstat -tln 2>/dev/null | grep ':" + port + "'");
    }

    /** The OpenSSH key a dropbear-format key was converted from (for the fingerprint). */
    private static String sshKeyFor(String dropbearKey) {
        return dropbearKey.contains("ed25519")
                ? "/etc/ssh/ssh_host_ed25519_key"
                : "/etc/ssh/ssh_host_rsa_key";
    }
}
