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
    // Throttle network checks triggered by joins (ms)
    private volatile long lastJoinCheckMs = 0L;
    private static final long JOIN_CHECK_COOLDOWN_MS = 60_000L; // 60s

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
        // Notify immediately if we already know we're outdated
        if (checker != null && checker.isChecked() && checker.isOutdated()) {
            int behind = checker.getBehindCount();
            String latest = checker.getLatestVersion() != null ? checker.getLatestVersion() : "latest";
            String current = plugin.getDescription().getVersion();
            p.sendMessage(Text.colorize(prefix + "&eBạn đang chậm &f" + behind + "&e phiên bản!"));
            p.sendMessage(Text.colorize(prefix + "&eBạn đang dùng &6" + current + "&e, phiên bản mới nhất là &6" + latest + "&e."));
            p.sendMessage(Text.colorize(prefix + "&eTải về: &b" + checker.getLatestUrl()));
        } else if (checker != null) {
            // If result is stale or not yet checked, trigger a quick check (throttled)
            long now = System.currentTimeMillis();
            if (!checker.isChecked() || (now - lastJoinCheckMs) >= JOIN_CHECK_COOLDOWN_MS) {
                lastJoinCheckMs = now;
                try { checker.checkAsync(); } catch (Throwable ignored) {}
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (checker.isChecked() && checker.isOutdated() && p.isOnline() && p.hasPermission("boatracing.update")) {
                        int behind = checker.getBehindCount();
                        String latest = checker.getLatestVersion() != null ? checker.getLatestVersion() : "latest";
                        String current = plugin.getDescription().getVersion();
                        p.sendMessage(Text.colorize(prefix + "&eBạn đang chậm &f" + behind + "&e phiên bản!"));
                        p.sendMessage(Text.colorize(prefix + "&eBạn đang dùng &6" + current + "&e, phiên bản mới nhất là &6" + latest + "&e."));
                        p.sendMessage(Text.colorize(prefix + "&eTải về: &b" + checker.getLatestUrl()));
                    }
                }, 20L * 5);
            }
        }
    }
}
