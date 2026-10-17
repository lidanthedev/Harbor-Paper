package dev.triassic.harbor;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

        Logger logger = Logger.getLogger("Harbor");
        logger.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
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

        if (!manager.isInstalled()) {
            System.out.println("[Harbor] first run: installing the Alpine rootfs (this takes a minute)...");
            System.out.flush();
            manager.ensureInstalled();
        }
        if (manager.isInstalled()) {
            HarborBoot.BootReport report = boot.run();
            // The dropbear line is the SSH entry point: always show it.
            if (report.dropbearLine != null) {
                System.out.println(report.dropbearLine);
            }
            if (!silent) {
                for (String line : report.startupLines) {
                    System.out.println(line);
                }
            }
            System.out.flush();
        }

        new HarborConsole(manager, dropbear, boot).run();
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

    /** Console lines: just the message, no logger header. */
    private static final class PlainFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record) + System.lineSeparator();
        }
    }
}
