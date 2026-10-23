package dev.triassic.harbor;

/**
 * Optional GoTTY web terminal, disabled by default.
 *
 * GoTTY wraps a command in a browser terminal: it serves an xterm.js page over
 * HTTP and relays keystrokes to a pty. Harbor runs it inside the rootfs with
 * /bin/sh as the command, so the browser gets the same root shell that
 * {@code harbor.shell} and dropbear give - it is a second, much more casual
 * door to the filesystem, which is why it is off unless {@code gotty.enabled}
 * says otherwise.
 *
 * No bootstrap work: the binary is a single Go executable, fetched from the
 * upstream release the first time GoTTY actually needs to run. A default
 * install therefore downloads nothing extra and opens no port.
 *
 * gotty has no pid-file flag, so the shell that execs it writes its own pid
 * first ({@code echo $$ > pid; exec gotty ...}) - exec keeps the pid, so the
 * file holds gotty's. {@link #stop()} verifies {@code /proc/<pid>/comm} before
 * killing, the same rule as {@link Dropbear}, so a stale file cannot take out a
 * recycled pid.
 */
public final class Gotty {

    private static final String PID_FILE = "/root/.harbor/gotty.pid";
    private static final String BINARY = "/usr/local/bin/gotty";
    private static final String TARBALL = "/root/.harbor/gotty.tar.gz";

    private final HarborManager manager;

    public Gotty(HarborManager manager) {
        this.manager = manager;
    }

    /** Result of an operation, for reporting back to the operator. */
    public static final class Result {
        public final boolean ok;
        public final int port;
        public final String notes;

        Result(boolean ok, int port, String notes) {
            this.ok = ok;
            this.port = port;
            this.notes = notes;
        }
    }

    public boolean isEnabled() {
        return manager.config().getBoolean("gotty.enabled", false);
    }

    public int port() {
        return manager.config().getInt("gotty.port", 8080);
    }

    // ---- operations --------------------------------------------------------

    /**
     * Start GoTTY, or restart it when it is already running. Blocking: the
     * binary download on first use is the slow part. Call async.
     */
    public Result start() {
        int port = port();
        if (!manager.isInstalled()) {
            return new Result(false, port, "rootfs not installed yet");
        }
        if (!manager.isLinux()) {
            return new Result(false, port, "proot requires Linux");
        }
        StringBuilder notes = new StringBuilder();
        if (!ensureBinary(notes)) {
            return new Result(false, port, notes.toString().trim());
        }
        if (password().isBlank()) {
            // Not fatal (upstream defaults to no auth too), but the operator
            // must be told: this is an unauthenticated root shell on a port.
            notes.append("no gotty.password set - anyone who can reach the port gets a root shell; ");
        }
        killExisting();
        manager.log("gotty: launching on port " + port);
        String logPath = manager.detach("gotty",
                "echo $$ > " + PID_FILE + "; exec gotty " + argsFor(port));

        boolean alive = !manager.execShell("p=$(cat " + PID_FILE + " 2>/dev/null); "
                + "[ -n \"$p\" ] && [ \"$(cat /proc/$p/comm 2>/dev/null)\" = gotty ] && echo yes")
                .trim().isEmpty();
        boolean listening = !listener(port).trim().isEmpty();
        if (!listening) {
            String log = manager.execShell("cat " + logPath + " 2>/dev/null");
            notes.append("not listening on ").append(port)
                    .append(", log ").append(logPath).append(": ")
                    .append(log.isBlank() ? "(empty)" : HarborManager.truncate(log, 400)).append("; ");
        }
        if (!alive) {
            notes.append("process not running; ");
        }
        return new Result(listening && alive, port, notes.toString().trim());
    }

    /** Stop the web terminal. */
    public Result stop() {
        int port = port();
        killExisting();
        boolean listening = !listener(port).trim().isEmpty();
        return new Result(!listening, port,
                listening ? "still listening on " + port : "stopped");
    }

    // ---- internals ---------------------------------------------------------

    /**
     * Fetch the single Go binary from the upstream release, once. The rootfs
     * has busybox wget and tar, and Harbor already relies on that path for the
     * release jars, so no extra package is needed.
     */
    private boolean ensureBinary(StringBuilder notes) {
        if (manager.fileExists(BINARY)) {
            return true;
        }
        String[] arch = manager.detectArch();
        if (arch == null) {
            notes.append("unsupported CPU architecture; ");
            return false;
        }
        String version = manager.config().getString("gotty.version", "1.5.0");
        String url = "https://github.com/sorenisanerd/gotty/releases/download/v" + version
                + "/gotty_v" + version + "_linux_" + arch[1] + ".tar.gz";
        manager.log("gotty: downloading " + url + " (first use only)");
        // No -q: a DNS/TLS/404 failure has to end up in the notes.
        String out = manager.execShell("mkdir -p /usr/local/bin /root/.harbor && "
                + "wget -O " + TARBALL + " " + url + " && "
                + "tar -xzf " + TARBALL + " -C /usr/local/bin && "
                + "chmod 755 " + BINARY + " && rm -f " + TARBALL);
        if (!manager.fileExists(BINARY)) {
            notes.append("could not install gotty from ").append(url).append(": ")
                    .append(out.isBlank() ? "(no output; is the rootfs online?)"
                            : HarborManager.truncate(out, 300))
                    .append("; ");
            return false;
        }
        return true;
    }

    /**
     * Kill our own gotty, identified by the pid its shell wrote before exec.
     * Deliberately not a name-based kill: this container shares the host's PID
     * namespace, so `pkill gotty` could take out another Harbor's instance (and
     * `pkill -f` matches the calling shell's own argv).
     */
    private void killExisting() {
        manager.execShell("p=$(cat " + PID_FILE + " 2>/dev/null); "
                + "if [ -n \"$p\" ] && [ \"$(cat /proc/$p/comm 2>/dev/null)\" = gotty ]; then "
                + "kill \"$p\" 2>/dev/null; sleep 1; [ -d /proc/$p ] && kill -9 \"$p\" 2>/dev/null; "
                + "fi; "
                + "rm -f " + PID_FILE);
        manager.execShell("sleep 1");
    }

    private String argsFor(int port) {
        StringBuilder args = new StringBuilder();
        args.append("--port ").append(port);
        args.append(" --address 0.0.0.0");
        if (permitWrite()) {
            args.append(" --permit-write");
        }
        // Lets a dropped websocket reconnect instead of needing a reload.
        args.append(" --reconnect");
        if (!password().isBlank()) {
            args.append(" --credential ").append(shQuote(username() + ":" + password()));
        }
        args.append(" /bin/sh");
        return args.toString();
    }

    private String username() {
        String user = manager.config().getString("gotty.username", "harbor");
        return user == null ? "" : user.trim();
    }

    private String password() {
        String pass = manager.config().getString("gotty.password", "");
        return pass == null ? "" : pass;
    }

    private boolean permitWrite() {
        return manager.config().getBoolean("gotty.permit-write", true);
    }

    /** Single-quote for /bin/sh -c: passwords may contain anything. */
    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String listener(int port) {
        return manager.execShell("ss -tlnp 2>/dev/null | grep ':" + port + "' "
                + "|| netstat -tln 2>/dev/null | grep ':" + port + "'");
    }
}
