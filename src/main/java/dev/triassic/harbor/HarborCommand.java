package dev.triassic.harbor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * /harbor status | install | reinstall | exec &lt;cmd&gt;
 */
public final class HarborCommand implements TabExecutor {

    private final HarborPlugin plugin;

    public HarborCommand(HarborPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("harbor.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status": {
                String[] arch = plugin.manager().detectArch();
                sender.sendMessage("§8--- §fHarbor §8---");
                sender.sendMessage("§7OS: " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
                sender.sendMessage("§7Supported: " + (plugin.manager().isLinux() && arch != null));
                sender.sendMessage("§7Installed: " + plugin.manager().isInstalled());
                sender.sendMessage("§7Rootfs: §f" + plugin.manager().getRootfsDir().getAbsolutePath());
                sender.sendMessage("§7Dropbear: "
                        + (plugin.getConfig().getBoolean("dropbear.enabled", true) ? "enabled" : "disabled")
                        + " port " + plugin.getConfig().getInt("dropbear.port", 25584));
                sender.sendMessage("§7Startup cmds: §f"
                        + plugin.getConfig().getStringList("startup-commands").size());
                sender.sendMessage("§7Use: §f/sh <command> §7e.g. §f/sh cat /etc/alpine-release");
                sender.sendMessage("§7Boot tasks: §f/harbor boot §7| dropbear: §f/harbor dropbear");
                return true;
            }
            case "install": {
                if (plugin.manager().isInstalled()) {
                    sender.sendMessage("§aHarbor already installed.");
                    return true;
                }
                sender.sendMessage("§eInstalling Alpine rootfs in background (silent)...");
                plugin.ensureInstalledAsync(ok ->
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                sender.sendMessage(ok ? "§aHarbor install complete."
                                        : "§cHarbor install failed. See console.")));
                return true;
            }
            case "reinstall": {
                sender.sendMessage("§eWiping plugins/Harbor/container and reinstalling...");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    delete(plugin.manager().getRootfsDir());
                    boolean ok = plugin.manager().ensureInstalled();
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(ok ? "§aHarbor reinstall complete."
                                    : "§cHarbor reinstall failed. See console."));
                });
                return true;
            }
            case "exec": {
                if (args.length < 2) {
                    sender.sendMessage("§eUsage: /harbor exec <command>");
                    return true;
                }
                // delegate to same path as /sh
                String[] rest = Arrays.copyOfRange(args, 1, args.length);
                return new ShCommand(plugin).onCommand(sender, command, "sh", rest);
            }
            case "boot": {
                sender.sendMessage("§eRunning Harbor boot tasks (dropbear + startup-commands)...");
                plugin.bootAsync(report -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (report.isEmpty()) {
                        sender.sendMessage("§7Nothing to do.");
                    }
                    for (String line : report) {
                        sender.sendMessage("§7" + line);
                    }
                }));
                return true;
            }
            case "dropbear": {
                boolean enable = args.length < 2 || !args[1].equalsIgnoreCase("off");
                if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        plugin.manager().execShell("pkill -f dropbear 2>/dev/null");
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                sender.sendMessage("§aStopped dropbear."));
                    });
                    return true;
                }
                if (!plugin.manager().isInstalled()) {
                    sender.sendMessage("§cRootfs not installed yet.");
                    return true;
                }
                sender.sendMessage("§eSetting up dropbear (host key via ssh-keygen + dropbearconvert)...");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    Dropbear.Result r = plugin.dropbear().setup();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(r.ok
                                ? "§aDropbear listening on port " + r.port
                                : "§cDropbear setup failed.");
                        if (!r.fingerprint.isEmpty()) {
                            sender.sendMessage("§7Host key: " + r.fingerprint);
                        }
                        if (!r.notes.isEmpty()) {
                            sender.sendMessage("§eNotes: " + r.notes);
                        }
                        sender.sendMessage("§7Connect: ssh -p " + r.port
                                + " root@<panel-host> -D 127.0.0.1:1080 -N");
                        if (!enable) {
                            sender.sendMessage("§7(ignored: 'off' handled separately)");
                        }
                    });
                });
                return true;
            }
            default:
                sender.sendMessage("§eUsage: /" + label + " <status|install|reinstall|boot|dropbear|exec>");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "install", "reinstall", "boot", "dropbear", "exec");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("dropbear")) {
            return Arrays.asList("on", "off");
        }
        return Collections.emptyList();
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k);
        }
        f.delete();
    }
}
