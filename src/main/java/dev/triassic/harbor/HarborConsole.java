package dev.triassic.harbor;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone console loop. Every line typed into the console is executed in the
 * rootfs shell, exactly like /sh does in plugin mode.
 *
 * "/harbor ..." is reserved for Harbor itself (status, install, reinstall, boot,
 * dropbear, exec, help). "/sh ..." is accepted as an alias for the shell so
 * muscle memory from plugin mode still works. Nothing else is intercepted.
 *
 * Lines are read on the calling thread and executed one at a time on a worker
 * thread, so a long-running (or hung) command can never block the console.
 */
public final class HarborConsole {

    /** Queue sentinel; cannot be typed because it contains a NUL. */
    private static final String STOP = "\u0000__harbor_stop__";

    private final HarborManager manager;
    private final Dropbear dropbear;
    private final HarborBoot boot;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    /** true until the background install + boot pass finishes. */
    private final AtomicBoolean starting = new AtomicBoolean(true);

    public HarborConsole(HarborManager manager, Dropbear dropbear, HarborBoot boot) {
        this.manager = manager;
        this.dropbear = dropbear;
        this.boot = boot;
    }

    /** Called by the background startup pass once install + boot are done. */
    public void startingDone() {
        starting.set(false);
    }

    /** Reads stdin until EOF, then lets queued work finish and returns. */
    public void run() throws Exception {
        Thread worker = new Thread(this::workerLoop, "harbor-console");
        worker.setDaemon(true);
        worker.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String cmd = line.trim();
            if (!cmd.isEmpty()) {
                queue.put(cmd);
            }
        }
        queue.put(STOP);
        worker.join(10000);
    }

    private void workerLoop() {
        while (true) {
            String cmd;
            try {
                cmd = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (STOP.equals(cmd)) {
                return;
            }
            try {
                dispatch(cmd);
            } catch (Throwable t) {
                out("! " + t);
            }
        }
    }

    private void dispatch(String line) {
        if (line.equals("/harbor") || line.startsWith("/harbor ")) {
            manage(line.length() > 8 ? line.substring(8).trim() : "");
            return;
        }
        String shellCmd = line.equals("/sh") ? ""
                : line.startsWith("/sh ") ? line.substring(4).trim()
                : line;
        if (shellCmd.isEmpty()) {
            out("usage: <command>            run it in the rootfs");
            out("       /harbor <subcommand> manage Harbor");
            return;
        }
        shell(shellCmd);
    }

    private void shell(String shellCmd) {
        if (!manager.isInstalled()) {
            out(starting.get()
                    ? "still installing the rootfs - progress is printed above; /harbor status to check"
                    : "rootfs not installed yet - run: /harbor install");
            return;
        }
        long timeout = manager.config().getLong("command-timeout-seconds", 0);
        out("$ " + shellCmd + (timeout > 0 ? "  (" + timeout + "s limit)" : ""));
        try {
            HarborManager.ProcessResult r = manager.execInRootfs(shellCmd, timeout);
            String text = r.output == null ? "" : r.output.replace("\r", "");
            if (!text.isBlank()) {
                out(text.stripTrailing());
            }
            if (r.exitCode != 0) {
                out("exit " + r.exitCode);
            }
        } catch (Exception e) {
            out("! " + e.getMessage());
        }
    }

    private void manage(String args) {
        String sub = args.isEmpty() ? "status" : args.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String rest = args.length() > sub.length() ? args.substring(sub.length()).trim() : "";
        switch (sub) {
            case "status": {
                String[] arch = manager.detectArch();
                out("--- Harbor ---");
                out("OS: " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
                out("Supported: " + (manager.isLinux() && arch != null));
                out("Installed: " + manager.isInstalled()
                        + (starting.get() && !manager.isInstalled() ? "  (install in progress)" : ""));
                out("Rootfs: " + manager.getRootfsDir().getAbsolutePath());
                out("Dropbear: "
                        + (manager.config().getBoolean("dropbear.enabled", true) ? "enabled" : "disabled")
                        + " port " + manager.config().getInt("dropbear.port", 25584));
                out("Startup cmds: " + manager.config().getStringList("startup-commands").size());
                out("Mode: standalone - console lines run in the rootfs, /harbor manages Harbor");
                return;
            }
            case "install": {
                if (starting.get() && !manager.isInstalled()) {
                    out("install already in progress - progress is printed above");
                    return;
                }
                if (manager.isInstalled()) {
                    out("already installed: " + manager.getRootfsDir().getAbsolutePath());
                    return;
                }
                out("installing rootfs (this takes a minute)...");
                out(manager.ensureInstalled() ? "install complete" : "install FAILED (see log above)");
                return;
            }
            case "reinstall": {
                if (starting.get()) {
                    out("install/boot still in progress - wait for it to finish first");
                    return;
                }
                out("wiping " + manager.getRootfsDir().getAbsolutePath() + " and reinstalling...");
                delete(manager.getRootfsDir());
                out(manager.ensureInstalled() ? "reinstall complete" : "reinstall FAILED (see log above)");
                return;
            }
            case "boot": {
                if (!manager.isInstalled()) {
                    out("rootfs not installed yet - run: /harbor install");
                    return;
                }
                HarborBoot.BootReport r = boot.run();
                if (r.dropbearLine != null) {
                    out(r.dropbearLine);
                }
                for (String l : r.startupLines) {
                    out(l);
                }
                return;
            }
            case "dropbear": {
                if (!manager.isInstalled()) {
                    out("rootfs not installed yet");
                    return;
                }
                String action = rest.toLowerCase(Locale.ROOT);
                Dropbear.Result r;
                switch (action) {
                    case "start":
                        r = dropbear.start();
                        // a plain start on a rootfs that was never set up becomes a setup
                        if (!r.ok && r.needsSetup) {
                            r = dropbear.reinstall();
                        }
                        break;
                    case "reinstall":
                        r = dropbear.reinstall();
                        break;
                    case "stop":
                        r = dropbear.stop();
                        break;
                    default:
                        out("usage: /harbor dropbear <start|reinstall|stop>");
                        return;
                }
                out((r.ok ? "dropbear ok" : "dropbear FAILED") + " on port " + r.port
                        + (r.fingerprint.isEmpty() ? "" : " | " + r.fingerprint)
                        + (r.notes.isEmpty() ? "" : " | " + r.notes));
                return;
            }
            case "exec": {
                if (rest.isEmpty()) {
                    out("usage: /harbor exec <command>");
                    return;
                }
                shell(rest);
                return;
            }
            case "help":
                usage();
                return;
            default:
                out("unknown subcommand: " + sub);
                usage();
        }
    }

    private void usage() {
        out("console: type a command to run it in the rootfs (no /sh needed)");
        out("/harbor status");
        out("/harbor install | reinstall");
        out("/harbor boot");
        out("/harbor dropbear <start|reinstall|stop>");
        out("/harbor exec <command>");
    }

    private static void out(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    delete(k);
                }
            }
        }
        f.delete();
    }
}
