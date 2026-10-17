package dev.triassic.harbor;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link HarborConfig} backed by SnakeYAML, for standalone mode (no Bukkit).
 *
 * SnakeYAML is bundled and relocated by the Shadow plugin
 * (org.yaml.snakeyaml -> dev.triassic.harbor.lib.snakeyaml), so this never
 * collides with the copy Paper ships in plugin mode. A SafeConstructor is used
 * so a config file cannot instantiate arbitrary classes.
 *
 * Semantics mirror FileConfiguration for the keys Harbor reads: dotted paths,
 * defaults for missing keys, "" preserved, scalars coerced leniently.
 */
public final class YamlConfig implements HarborConfig {

    private final Map<String, Object> root;

    @SuppressWarnings("unchecked")
    public YamlConfig(File file) throws Exception {
        Object loaded;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        this.root = loaded instanceof Map ? (Map<String, Object>) loaded : Collections.emptyMap();
    }

    private Object lookup(String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<?, ?>) cur).get(part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    @Override
    public String getString(String path, String def) {
        Object v = lookup(path);
        return v == null ? def : String.valueOf(v);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object v = lookup(path);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase();
            if (s.equals("true")) {
                return true;
            }
            if (s.equals("false")) {
                return false;
            }
        }
        return def;
    }

    @Override
    public int getInt(String path, int def) {
        Object v = lookup(path);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    @Override
    public long getLong(String path, long def) {
        Object v = lookup(path);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    @Override
    public List<String> getStringList(String path) {
        Object v = lookup(path);
        if (!(v instanceof List)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) v) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
