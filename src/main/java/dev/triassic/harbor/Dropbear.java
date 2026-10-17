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
 */
public final class Dropbear {

    private final HarborManager manager;

    public Dropbear(HarborManager manager) {
        this.manager = manager;
    }

    /** Result of a setup run, for reporting back to the operator. */
    public static final class Result {
        public final boolean ok;
        public final int port;
        public final String fingerprint;
        public final String notes;

        Result(boolean ok, int port, String fingerprint, String notes) {
            this.ok = ok;
            this.port = port;
            this.fingerprint = fingerprint;
            this.notes = notes;
        }
    }

    public boolean isEnabled() {
        return manager.config().getBoolean("dropbear.enabled", true);
    }

    public int port() {
        return manager.config().getInt("dropbear.port", 25584);
    }

    /** Blocking; call async. Idempotent enough to be safe on every boot. */
    public Result setup() {
        int port = port();
        StringBuilder notes = new StringBuilder();

        // 1. Packages. dropbear-convert (NOT dropbear-openrc) provides
        //    dropbearconvert; openssh-keygen provides ssh-keygen.
        manager.execShell("apk add --no-cache dropbear dropbear-convert openssh-keygen");
        if (!manager.hasBinary("ssh-keygen")) {
            notes.append("ssh-keygen still missing; ");
        }
        if (!manager.hasBinary("dropbearconvert")) {
            notes.append("dropbearconvert still missing; ");
        }

        // 2. Host keys (dropbearkey cannot write keys here: getrandom is blocked).
        manager.execShell("mkdir -p /etc/ssh");
        manager.execShell("ssh-keygen -A 2>&1", 120);
        if (!manager.fileExists("/etc/ssh/ssh_host_ed25519_key")) {
            manager.execShell("ssh-keygen -t ed25519 -N '' -f /etc/ssh/ssh_host_ed25519_key "
                    + "-C 'harbor host key' 2>&1", 120);
        }
        boolean ed25519 = manager.fileExists("/etc/ssh/ssh_host_ed25519_key");

        // 3. Convert OpenSSH -> dropbear format.
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
        manager.execShell("mkdir -p /root/.ssh && chmod 700 /root/.ssh");
        if (pubKey != null && !pubKey.isBlank()) {
            manager.writeFileInRootfs("/root/.ssh/authorized_keys", pubKey.trim() + "\n", "600");
        } else {
            // migrate anything left in the shell HOME by earlier attempts
            manager.execShell("[ -f /home/container/.ssh/authorized_keys ] && "
                    + "cp /home/container/.ssh/authorized_keys /root/.ssh/authorized_keys "
                    + "&& chmod 600 /root/.ssh/authorized_keys");
        }
        if (!manager.fileExists("/root/.ssh/authorized_keys")) {
            notes.append("no authorized_keys yet (add dropbear.authorized-key to config); ");
        }

        // 5. Start it detached. -F is REQUIRED here: without it dropbear tries to
        //    daemonise and misbehaves inside this container. -F keeps it in the
        //    foreground, so we detach it ourselves (see HarborManager#detach:
        //    stdin from /dev/null, stdout+stderr to a persistent log inside the
        //    rootfs, otherwise the launch hangs and no log is ever written).
        //    -E = log to stderr, -K 60 = keepalive.
        boolean allowPw = manager.config().getBoolean("dropbear.allow-password-login", false);
        manager.execShell("pkill -f 'dropbear .*-p " + port + "' 2>/dev/null");
        String args = "-F -E -K 60 -p " + port + " -r " + dstKey
                + " -P /root/.harbor/dropbear.pid";
        if (!allowPw) {
            args += " -g"; // no root password logins; keys only
        }
        // a stale pid file makes a real failure look like a clean start
        manager.execShell("rm -f /root/.harbor/dropbear.pid");
        String logPath = manager.detach("dropbear", "dropbear " + args);

        // 6. Report. Verify the socket, the process, and the log.
        String listener = manager.execShell("ss -tlnp 2>/dev/null | grep ':" + port + "' "
                + "|| netstat -tln 2>/dev/null | grep ':" + port + "'");
        boolean listening = !listener.trim().isEmpty();
        boolean alive = !manager.execShell(
                "pidof dropbear 2>/dev/null || pgrep -f 'dropbear .*-F' 2>/dev/null").trim().isEmpty();
        String log = manager.execShell("cat " + logPath + " 2>/dev/null");
        String fingerprint = manager.execShell(
                "ssh-keygen -lf " + srcKey + " 2>/dev/null").trim();
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
}
