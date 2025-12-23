package es.jaie55.boatracing.track;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * Wand listener that responds to interactions with the selector stick.
 */
public class WandListener implements Listener {
    private final Plugin plugin;
    public WandListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getHand() != null && ev.getHand() != EquipmentSlot.HAND) return;
        if (ev.getItem() == null || !ev.getItem().hasItemMeta()) return;
        var meta = ev.getItem().getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return;
        if (!meta.getDisplayName().contains("Selector")) return;
        ev.setCancelled(true);
        var p = ev.getPlayer();
        switch (ev.getAction()) {
            case LEFT_CLICK_BLOCK, LEFT_CLICK_AIR -> {
                if (ev.getClickedBlock() != null) SelectionManager.setCornerA(p, ev.getClickedBlock().getLocation());
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aCorner A set."));
            }
            case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR -> {
                if (ev.getClickedBlock() != null) SelectionManager.setCornerB(p, ev.getClickedBlock().getLocation());
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aCorner B set."));
            }
            default -> {}
        }
    }
}
