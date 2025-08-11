package es.jaie55.boatracing.update;

import es.jaie55.boatracing.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public class UpdateNotifier implements Listener {
    private final Plugin plugin;
    private final UpdateChecker checker;
    private final String prefix;

    public UpdateNotifier(Plugin plugin, UpdateChecker checker, String prefix) {
        this.plugin = plugin;
        this.checker = checker;
        this.prefix = prefix;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("updates.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("updates.notify-admins", true)) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("boatracing.update")) return;
        // Notify only if a check already ran and we're outdated; if not yet checked, schedule a short retry
        if (checker != null && checker.isChecked() && checker.isOutdated()) {
            int behind = checker.getBehindCount();
            String latest = checker.getLatestVersion() != null ? checker.getLatestVersion() : "latest";
            String current = plugin.getPluginMeta().getVersion();
            p.sendMessage(Text.colorize(prefix + "&eYou're " + behind + " version(s) out of date!"));
            p.sendMessage(Text.colorize(prefix + "&eYou are running &6" + current + "&e, the latest version is &6" + latest + "&e."));
            p.sendMessage(Text.colorize(prefix + "&eDownload: &b" + checker.getLatestUrl()));
        } else if (checker != null && !checker.isChecked()) {
            // poll after a short delay to notify if outdated
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (checker.isChecked() && checker.isOutdated() && p.isOnline() && p.hasPermission("boatracing.update")) {
                    int behind = checker.getBehindCount();
                    String latest = checker.getLatestVersion() != null ? checker.getLatestVersion() : "latest";
                    String current = plugin.getPluginMeta().getVersion();
                    p.sendMessage(Text.colorize(prefix + "&eYou're " + behind + " version(s) out of date!"));
                    p.sendMessage(Text.colorize(prefix + "&eYou are running &6" + current + "&e, the latest version is &6" + latest + "&e."));
                    p.sendMessage(Text.colorize(prefix + "&eDownload: &b" + checker.getLatestUrl()));
                }
            }, 20L * 5);
        }
    }
}
