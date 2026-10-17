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
            Thread heartbeat = firstRun ? startInstallHeartbeat() : null;
            try {
                if (firstRun && !manager.ensureInstalled()) {
                    System.out.println("[Harbor] install FAILED - see above; retry with /harbor install");
                    System.out.flush();
                }
                if (manager.isInstalled()) {
                    HarborBoot.BootReport report = boot.run();
                    // The dropbear line is the SSH entry point: always show it.
                    if (report.dropbearLine != null) {
                        System.out.println(report.dropbearLine);
                        System.out.flush();
                    }
                }
            } catch (Throwable t) {
                System.out.println("[Harbor] startup failed: " + t);
                System.out.flush();
            } finally {
                if (heartbeat != null) {
                    heartbeat.interrupt();
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
     * A multi-minute install that prints nothing is indistinguishable from a
     * hang - which is exactly how a first run was reported. Print elapsed time
     * every 30s so the panel console shows whether the install is alive.
     */
    private static Thread startInstallHeartbeat() {
        Thread heartbeat = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                while (true) {
                    Thread.sleep(30_000);
                    System.out.println("[Harbor] still installing ... "
                            + (System.currentTimeMillis() - start) / 1000
                            + "s elapsed (/harbor status to check)");
                    System.out.flush();
                }
            } catch (InterruptedException done) {
                // install finished; the finally block interrupts us
            }
        }, "harbor-install-heartbeat");
        // Daemon: never keeps the JVM alive on its own.
        heartbeat.setDaemon(true);
        heartbeat.start();
        return heartbeat;
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
