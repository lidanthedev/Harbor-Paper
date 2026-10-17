package dev.triassic.harbor;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * Standalone entry point: run the jar directly (e.g. renamed to server.jar) and
 * Harbor works without Bukkit at all.
 *
 *   * data folder is still plugins/Harbor (created if missing), so the rootfs,
 *     config and SSH setup are exactly the same as plugin mode;
 *   * nothing is registered with Bukkit/Paper - there is no server here;
 *   * every console line is executed in the rootfs, /harbor manages Harbor.
 *
 * When Paper loads this same jar as a plugin it reads plugin.yml instead and
 * never touches this class.
 */
public final class HarborMain {

    private static final String DATA_DIR = "plugins" + File.separator + "Harbor";

    private HarborMain() {
    }

    public static void main(String[] args) throws Exception {
        File dataDir = new File(DATA_DIR);
        dataDir.mkdirs();
        File configFile = new File(dataDir, "config.yml");
        if (!configFile.isFile()) {
            writeResource("/config.yml", configFile);
        }

        HarborConfig config = new YamlConfig(configFile);
        boolean silent = config.getBoolean("silent", true);

        // Say which build this is. A stuck first run is impossible to diagnose
        // if you cannot tell which jar produced it.
        System.out.println("[Harbor] " + version() + " standalone mode, data folder " + dataDir);
        System.out.flush();

        Logger logger = Logger.getLogger("Harbor");
        logger.setUseParentHandlers(false);
        // Log to stdout, not stderr (ConsoleHandler's default). The standalone
        // console is a single stream, and splitting progress onto stderr makes
        // it interleave with command output out of order.
        StreamHandler handler = new StdoutHandler();
        handler.setFormatter(new PlainFormatter());
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        // silent:true hides Harbor's own fine-level chatter, same as plugin mode.
        logger.setLevel(silent ? Level.INFO : Level.ALL);

        HarborManager manager = new HarborManager(dataDir, config, logger);
        Dropbear dropbear = new Dropbear(manager);
        HarborBoot boot = new HarborBoot(manager, dropbear);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (manager.isInstalled() && manager.isLinux() && dropbear.isEnabled()) {
                    dropbear.stop();
                }
            } catch (Throwable ignored) {
                // container is going away; nothing useful to do
            }
        }, "harbor-shutdown"));

        HarborConsole console = new HarborConsole(manager, dropbear, boot);

        // Install + boot run in the background so the console accepts input
        // immediately. Doing this inline made the whole program look hung for
        // the minutes a first-run install takes.
        Thread startup = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            boolean firstRun = !manager.isInstalled();
            if (firstRun) {
                System.out.println("[Harbor] first run: installing the Alpine rootfs "
                        + "(a few minutes; progress below) ...");
                System.out.flush();
                // Harbor logs install progress at fine level, which silent:true
                // hides. A silent multi-minute download looks dead, so surface
                // everything until boot is done.
                logger.setLevel(Level.ALL);
            }
            // What the heartbeat says we are doing. A boot that spends minutes
            // bringing dropbear up used to keep announcing "installing the
            // rootfs", which reads as a stuck install when the rootfs is in fact
            // installed and dropbear is already listening. The phase is kept in
            // HarborConsole so /harbor status reports the same thing.
            if (firstRun) {
                console.startupPhase("installing the rootfs");
            }
            Thread heartbeat = startStartupHeartbeat(console);
            boolean booted = false;
            try {
                if (firstRun && !manager.ensureInstalled()) {
                    System.out.println("[Harbor] install FAILED - see above; retry with /harbor install");
                    System.out.flush();
                }
                if (manager.isInstalled()) {
                    // Each progress line the boot pass prints also becomes the
                    // current phase, so the heartbeat stays truthful.
                    HarborBoot.BootReport report = boot.run(line -> {
                        console.startupPhase(phaseOf(line));
                        println(line);
                    });
                    // The dropbear line is the SSH entry point: always show it.
                    if (report.dropbearLine != null) {
                        System.out.println(report.dropbearLine);
                        System.out.flush();
                    }
                    booted = true;
                }
            } catch (Throwable t) {
                System.out.println("[Harbor] startup failed: " + t);
                System.out.flush();
            } finally {
                heartbeat.interrupt();
                long took = (System.currentTimeMillis() - startedAt) / 1000;
                // Only say it when it was slow enough to have raised questions.
                if (took >= 30) {
                    System.out.println("[Harbor] startup finished after " + took + "s");
                    System.out.flush();
                }
                // A hosting panel only marks the server "running" when it sees the
                // Minecraft-style "Done (Ns)!" line. Print it ourselves, with the
                // real elapsed time, instead of faking one with a startup command.
                if (booted) {
                    emitPanelDoneLine(config, took);
                }
                logger.setLevel(silent ? Level.INFO : Level.ALL);
                console.startingDone();
            }
        }, "harbor-startup");
        startup.setDaemon(true);
        startup.start();

        console.run();
    }

    /** Build version from the jar manifest ("dev" when run from classes). */
    private static String version() {
        Package pkg = HarborMain.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /**
     * A multi-minute startup that prints nothing is indistinguishable from a
     * hang - which is exactly how a first run was reported. Print elapsed time
     * every 30s, naming the phase, so the console shows whether the process is
     * alive and what it is doing.
     */
    private static Thread startStartupHeartbeat(HarborConsole console) {
        long start = System.currentTimeMillis();
        Thread heartbeat = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(30_000);
                    System.out.println("[Harbor] still " + console.startupPhase() + " ... "
                            + (System.currentTimeMillis() - start) / 1000
                            + "s elapsed (/harbor status to check)");
                    System.out.flush();
                }
            } catch (InterruptedException finished) {
                // the startup pass is done and interrupted us
            }
        }, "harbor-startup-heartbeat");
        // Daemon: never keeps the JVM alive on its own.
        heartbeat.setDaemon(true);
        heartbeat.start();
        return heartbeat;
    }

    /**
     * "[Harbor] installing the dropbear packages ..." -> "installing the
     * dropbear packages", so the heartbeat and /harbor status name the phase the
     * console just printed instead of a phrase of their own.
     */
    private static String phaseOf(String progressLine) {
        String line = progressLine.startsWith("[Harbor] ")
                ? progressLine.substring("[Harbor] ".length())
                : progressLine;
        return line.endsWith(" ...") ? line.substring(0, line.length() - 4) : line;
    }

    private static void println(String s) {
        System.out.println(s);
        System.out.flush();
    }

    /**
     * Print the hosting panel's startup line ("Done (Ns)! For help, type ...")
     * with the real elapsed time once the boot pass is done, so the server
     * leaves "starting" in the panel without a startup command faking the
     * number. Raw stdout with no prefix: the panel matches the console text.
     * Template from `panel-done-line`, %d = seconds; blank (default) = off.
     */
    private static void emitPanelDoneLine(HarborConfig config, long seconds) {
        String template = config.getString("panel-done-line", "");
        if (template == null || template.isBlank()) {
            return;
        }
        System.out.println(template.replace("%d", Long.toString(Math.max(0, seconds))));
        System.out.flush();
    }

    private static void writeResource(String resource, File dest) throws Exception {
        try (InputStream in = HarborMain.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("bundled resource missing: " + resource);
            }
            File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.copy(in, dest.toPath());
        }
    }

    /** ConsoleHandler writes to stderr; the standalone console wants stdout. */
    private static final class StdoutHandler extends StreamHandler {
        StdoutHandler() {
            super(System.out, new PlainFormatter());
        }

        @Override
        public void publish(LogRecord record) {
            super.publish(record);
            flush();
        }

        @Override
        public void close() {
            flush();
        }
    }

    /** Console lines: just the message, no logger header. */
    private static final class PlainFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record) + System.lineSeparator();
        }
    }
}
