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

    /** Blocking. Never throws; failures come back as report text. */
    public BootReport run() {
        String dropbearLine = null;
        boolean dropbearOk = true;
        try {
            if (dropbear.isEnabled()) {
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
            startupLines = manager.runStartupCommands(
                    manager.config().getStringList("startup-commands"));
        } catch (Exception e) {
            startupLines = new ArrayList<>();
            startupLines.add("[startup] failed: " + e);
        }
        return new BootReport(dropbearLine, dropbearOk, startupLines);
    }
}
