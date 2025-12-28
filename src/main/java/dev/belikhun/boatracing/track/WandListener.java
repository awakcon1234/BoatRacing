package dev.belikhun.boatracing.track;

import dev.belikhun.boatracing.util.Text;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * Wand listener that responds to interactions with the selector stick.
 */
public class WandListener implements Listener {
	public WandListener(Plugin plugin) {
	}

	@EventHandler(ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent ev) {
		if (ev.getHand() != null && ev.getHand() != EquipmentSlot.HAND) return;
		if (ev.getItem() == null || !ev.getItem().hasItemMeta()) return;
		var meta = ev.getItem().getItemMeta();
		if (meta == null || meta.displayName() == null) return;
		String name = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(meta.displayName());
		if (!name.contains("Selector")) return;
		ev.setCancelled(true);
		var p = ev.getPlayer();
		switch (ev.getAction()) {
			case LEFT_CLICK_BLOCK, LEFT_CLICK_AIR -> {
				if (ev.getClickedBlock() != null) {
					SelectionManager.setCornerA(p, ev.getClickedBlock().getLocation());
					Text.msg(p, "&aĐã đặt góc A = &f" + Text.fmtBlock(ev.getClickedBlock()));
				} else {
					Text.msg(p, "&cHãy nhấp vào một block để đặt góc A.");
				}
			}
			case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR -> {
				if (ev.getClickedBlock() != null) {
					SelectionManager.setCornerB(p, ev.getClickedBlock().getLocation());
					Text.msg(p, "&aĐã đặt góc B = &f" + Text.fmtBlock(ev.getClickedBlock()));
				} else {
					Text.msg(p, "&cHãy nhấp vào một block để đặt góc B.");
				}
			}
			default -> {}
		}
	}
}

