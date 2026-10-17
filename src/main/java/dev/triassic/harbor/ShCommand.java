package dev.triassic.harbor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Collections;
import java.util.List;

/**
 * /sh &lt;command&gt; — runs the rest of the line via /bin/sh -c inside proot.
 * Silent: no banner, output is only sent back to the sender, chunked.
 */
public final class ShCommand implements TabExecutor {

    private final HarborPlugin plugin;

    public ShCommand(HarborPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("harbor.shell")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /" + label + " <command>");
            sender.sendMessage("§7Example: /sh apk add -q htop && htop --version");
            return true;
        }
        if (!plugin.manager().isLinux()) {
            sender.sendMessage("§cHarbor proot requires Linux. This server OS is unsupported.");
            return true;
        }
        if (!plugin.manager().isInstalled()) {
            sender.sendMessage("§eHarbor rootfs is still installing. Try again shortly (/harbor status).");
            // kick off install silently if not already running
            plugin.ensureInstalledAsync(null);
            return true;
        }
        String shellCmd = String.join(" ", args);
        // 0 or less = no timeout (see config command-timeout-seconds).
        long timeout = plugin.getConfig().getLong("command-timeout-seconds", 0);

        sender.sendMessage("§8$ " + shellCmd + (timeout > 0 ? " §8(" + timeout + "s limit)" : ""));
        // Run async so the server thread never blocks (silent, no console spam).
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HarborManager.ProcessResult r = plugin.manager().execInRootfs(shellCmd, timeout);
                String out = r.output == null ? "" : r.output.replace("\r", "").trim();
                if (out.isEmpty()) {
                    out = "(exit " + r.exitCode + ", no output)";
                } else {
                    out = HarborManager.truncate(out, 8000);
                }
                // Minecraft chat lines cap: chunk it
                for (String chunk : chunk(out, 1500)) {
                    sender.sendMessage("§7" + chunk);
                }
                if (r.exitCode != 0) {
                    sender.sendMessage("§cExit code: " + r.exitCode);
                }
            } catch (Exception e) {
                sender.sendMessage("§c/sh failed: " + e.getMessage());
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("harbor.shell")) {
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private static List<String> chunk(String s, int size) {
        List<String> out = new java.util.ArrayList<>();
        String[] lines = s.split("\n", -1);
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            // split overlong single lines
            while (line.length() > size) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(line.substring(0, size));
                line = line.substring(size);
            }
            if (cur.length() + line.length() + 1 > size) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append("\n");
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add("");
        // hard cap: don't flood chat
        if (out.size() > 10) {
            List<String> cut = out.subList(0, 10);
            List<String> copy = new java.util.ArrayList<>(cut);
            copy.add("...[truncated, " + (out.size() - 10) + " more chunks]");
            return copy;
        }
        return out;
    }
}
