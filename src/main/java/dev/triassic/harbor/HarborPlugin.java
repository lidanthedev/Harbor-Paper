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
 * - Silent: no banner, GoTTY off unless enabled, install + /sh run async with fine-level logging.
 * - Normal commands: /sh &lt;command&gt; runs via proot /bin/sh -c, output goes back to sender.
 */
public final class HarborPlugin extends JavaPlugin {

    private HarborManager harborManager;
    private Dropbear dropbear;
    private Gotty gotty;
    private HarborBoot harborBoot;
    private final AtomicBoolean installRunning = new AtomicBoolean(false);
    private final AtomicBoolean bootRunning = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.harborManager = new HarborManager(getDataFolder(), new BukkitConfig(getConfig()), getLogger());
        this.dropbear = new Dropbear(harborManager);
        this.gotty = new Gotty(harborManager);
        this.harborBoot = new HarborBoot(harborManager, dropbear, gotty);

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
        // The whole pass is timed from here so the slow-startup summary reports
        // what the operator actually waited for (install + boot), not just boot.
        long enabledAt = System.currentTimeMillis();
        ensureInstalledAsync(ok -> {
            if (ok) {
                bootAsync(null, enabledAt);
            }
        });
    }

    public HarborManager manager() {
        return harborManager;
    }

    public Dropbear dropbear() {
        return dropbear;
    }

    public Gotty gotty() {
        return gotty;
    }

    public boolean isSilent() {
        return getConfig().getBoolean("silent", true);
    }

    /**
     * Harbor boot sequence, runs once per server start after the rootfs exists:
     *   1. auto-configure + start dropbear
     *   2. start GoTTY when `gotty.enabled` (off by default)
     *   3. run config `startup-commands` in the background
     * Never blocks; safe to call repeatedly. Called by /harbor boot, so a manual
     * pass reports its own duration.
     */
    public void bootAsync(Consumer<List<String>> done) {
        bootAsync(done, System.currentTimeMillis());
    }

    /** @param startedAt when the pass began, for the slow-startup summary. */
    private void bootAsync(Consumer<List<String>> done, long startedAt) {
        if (!harborManager.isInstalled()) {
            ensureInstalledAsync(ok -> {
                if (ok) bootAsync(done, startedAt);
            });
            return;
        }
        if (!bootRunning.compareAndSet(false, true)) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<String> report = new ArrayList<>();
            try {
                HarborBoot.BootReport boot = harborBoot.run();
                long took = (System.currentTimeMillis() - startedAt) / 1000;
                if (boot.dropbearLine != null) {
                    report.add(boot.dropbearLine);
                    // A fast, successful boot stays quiet. A slow one is exactly
                    // when the operator needs the outcome and not just the
                    // elapsed time, so surface the dropbear line with the summary.
                    boolean verbose = !isSilent() || !boot.dropbearOk
                            || took >= HarborBoot.SLOW_STARTUP_SECONDS;
                    if (verbose) {
                        getLogger().info(boot.dropbearLine);
                    } else {
                        getLogger().fine(boot.dropbearLine);
                    }
                }
                if (boot.gottyLine != null) {
                    report.add(boot.gottyLine);
                    // Same rule as dropbear: quiet on a fast success, visible
                    // when it failed, when the operator asked for chatter, or
                    // when the first-use binary download made the pass slow.
                    boolean verbose = !isSilent() || !boot.gottyOk
                            || took >= HarborBoot.SLOW_STARTUP_SECONDS;
                    if (verbose) {
                        getLogger().info(boot.gottyLine);
                    } else {
                        getLogger().fine(boot.gottyLine);
                    }
                }
                report.addAll(boot.startupLines);
                // Silent mode hides everything above; a pass this slow must not
                // be indistinguishable from a hang. Paper's logger already
                // prefixes "[Harbor] ", so the message stays bare here and
                // renders exactly like standalone mode's "[Harbor] startup
                // finished after Ns".
                if (took >= HarborBoot.SLOW_STARTUP_SECONDS) {
                    getLogger().info("startup finished after " + took + "s");
                }
            } catch (Exception e) {
                getLogger().warning("boot tasks failed: " + e);
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
