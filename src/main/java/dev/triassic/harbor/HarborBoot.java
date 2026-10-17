package dev.triassic.harbor;

import java.util.ArrayList;
import java.util.List;

/**
 * The Harbor boot sequence, shared by plugin mode and standalone mode:
 *   1. auto-configure + start dropbear
 *   2. run config `startup-commands`
 *
 * Bukkit-free on purpose: {@link HarborPlugin} calls it from an async task,
 * {@link HarborMain} calls it on the main thread before the console loop.
 */
public final class HarborBoot {

    /** Outcome of one boot pass, for reporting back to the operator. */
    public static final class BootReport {
        /** "[dropbear] listening on port N | <fingerprint>", or null when disabled. */
        public final String dropbearLine;
        public final boolean dropbearOk;
        public final List<String> startupLines;

        BootReport(String dropbearLine, boolean dropbearOk, List<String> startupLines) {
            this.dropbearLine = dropbearLine;
            this.dropbearOk = dropbearOk;
            this.startupLines = startupLines;
        }
    }

    private final HarborManager manager;
    private final Dropbear dropbear;

    public HarborBoot(HarborManager manager, Dropbear dropbear) {
        this.manager = manager;
        this.dropbear = dropbear;
    }

    /**
     * Blocking. Never throws; failures come back as report text.
     *
     * @param progress optional sink for phase lines ("setting up dropbear ...").
     *                 Dropbear setup on a small container is several seconds per
     *                 rootfs round trip, and startup commands add more; without
     *                 these lines the only visible thing is elapsed time.
     */
    public BootReport run(java.util.function.Consumer<String> progress) {
        String dropbearLine = null;
        boolean dropbearOk = true;
        try {
            if (dropbear.isEnabled()) {
                if (progress != null) {
                    progress.accept("[Harbor] setting up dropbear (SSH) ...");
                }
                // Boot: cheap restart when it is already set up, full setup otherwise.
                Dropbear.Result r = dropbear.start();
                if (!r.ok && r.needsSetup) {
                    r = dropbear.reinstall();
                }
                dropbearOk = r.ok;
                dropbearLine = "[dropbear] " + (r.ok ? "listening" : "FAILED")
                        + " on port " + r.port
                        + (r.fingerprint.isEmpty() ? "" : " | " + r.fingerprint)
                        + (r.notes.isEmpty() ? "" : " | " + r.notes);
            }
        } catch (Exception e) {
            dropbearOk = false;
            dropbearLine = "[dropbear] FAILED: " + e;
        }

        List<String> startupLines;
        try {
            List<String> commands = manager.config().getStringList("startup-commands");
            if (!commands.isEmpty() && progress != null) {
                progress.accept("[Harbor] running " + commands.size() + " startup command(s) ...");
            }
            startupLines = manager.runStartupCommands(commands);
        } catch (Exception e) {
            startupLines = new ArrayList<>();
            startupLines.add("[startup] failed: " + e);
        }
        return new BootReport(dropbearLine, dropbearOk, startupLines);
    }

    /** Boot with no progress reporting (plugin mode logs its own way). */
    public BootReport run() {
        return run(null);
    }
}
