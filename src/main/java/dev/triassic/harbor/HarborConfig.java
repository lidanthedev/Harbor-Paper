package dev.triassic.harbor;

import java.util.List;

/**
 * The handful of config reads Harbor needs, decoupled from Bukkit so the same
 * core works when the jar is launched directly as server.jar.
 *
 * Two implementations:
 *   * {@link BukkitConfig} - wraps Paper's FileConfiguration (plugin mode only).
 *   * {@link YamlConfig}   - parses plugins/Harbor/config.yml (standalone mode).
 */
public interface HarborConfig {

    String getString(String path, String def);

    boolean getBoolean(String path, boolean def);

    int getInt(String path, int def);

    long getLong(String path, long def);

    List<String> getStringList(String path);
}
