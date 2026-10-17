package dev.triassic.harbor;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Harbor — Paper port of RealTriassic/Harbor.
 *
 * - Whole Alpine rootfs lives in plugins/Harbor/container (+ .installed marker).
 * - Silent: no banner, no GoTTY, install + /sh run async with fine-level logging.
 * - Normal commands: /sh &lt;command&gt; runs via proot /bin/sh -c, output goes back to sender.
 */
public final class HarborPlugin extends JavaPlugin {

    private HarborManager harborManager;
    private Dropbear dropbear;
    private final AtomicBoolean installRunning = new AtomicBoolean(false);
    private final AtomicBoolean bootRunning = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.harborManager = new HarborManager(this);
        this.dropbear = new Dropbear(harborManager);

        if (getCommand("sh") != null) {
            ShCommand sh = new ShCommand(this);
            getCommand("sh").setExecutor(sh);
            getCommand("sh").setTabCompleter(sh);
        }
        if (getCommand("harbor") != null) {
            HarborCommand hc = new HarborCommand(this);
            getCommand("harbor").setExecutor(hc);
            getCommand("harbor").setTabCompleter(hc);
        }

        // Silent background install, then boot tasks (dropbear + startup commands).
        ensureInstalledAsync(ok -> {
            if (ok) {
                bootAsync(null);
            }
        });
    }

    public HarborManager manager() {
        return harborManager;
    }

    public Dropbear dropbear() {
        return dropbear;
    }

    public boolean isSilent() {
        return getConfig().getBoolean("silent", true);
    }

    /**
     * Harbor boot sequence, runs once per server start after the rootfs exists:
     *   1. auto-configure + start dropbear
     *   2. run config `startup-commands` in the background
     * Never blocks; safe to call repeatedly.
     */
    public void bootAsync(Consumer<List<String>> done) {
        if (!harborManager.isInstalled()) {
            ensureInstalledAsync(ok -> {
                if (ok) bootAsync(done);
            });
            return;
        }
        if (!bootRunning.compareAndSet(false, true)) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<String> report = new ArrayList<>();
            try {
                if (dropbear.isEnabled()) {
                    // Boot: cheap restart when it is already set up, full setup otherwise.
                    Dropbear.Result r = dropbear.start();
                    if (!r.ok && r.needsSetup) {
                        r = dropbear.reinstall();
                    }
                    String line = "[dropbear] " + (r.ok ? "listening" : "FAILED")
                            + " on port " + r.port
                            + (r.fingerprint.isEmpty() ? "" : " | " + r.fingerprint)
                            + (r.notes.isEmpty() ? "" : " | " + r.notes);
                    report.add(line);
                    if (!isSilent() || !r.ok) {
                        getLogger().info(line);
                    } else {
                        getLogger().fine(line);
                    }
                }
                report.addAll(harborManager.runStartupCommands(
                        getConfig().getStringList("startup-commands")));
            } catch (Exception e) {
                getLogger().warning("[Harbor] boot tasks failed: " + e);
            } finally {
                bootRunning.set(false);
                if (done != null) done.accept(report);
            }
        });
    }

    /** Idempotent async install. Callback runs on async thread (may be null). */
    public void ensureInstalledAsync(Consumer<Boolean> done) {
        if (harborManager.isInstalled()) {
            if (done != null) done.accept(true);
            return;
        }
        if (!installRunning.compareAndSet(false, true)) {
            return; // already running, stay silent
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                boolean ok = harborManager.ensureInstalled();
                if (!isSilent() || !ok) {
                    getLogger().info(ok ? "Harbor install complete." : "Harbor install failed.");
                }
                if (done != null) done.accept(ok);
            } finally {
                installRunning.set(false);
            }
        });
    }
}
