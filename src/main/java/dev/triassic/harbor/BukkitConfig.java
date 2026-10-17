package dev.triassic.harbor;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * {@link HarborConfig} backed by Paper's FileConfiguration.
 *
 * This class is the only place the config touches Bukkit, so it is loaded in
 * plugin mode and never referenced from the standalone entry point.
 */
public final class BukkitConfig implements HarborConfig {

    private final FileConfiguration config;

    public BukkitConfig(FileConfiguration config) {
        this.config = config;
    }

    @Override
    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    @Override
    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    @Override
    public long getLong(String path, long def) {
        return config.getLong(path, def);
    }

    @Override
    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }
}
