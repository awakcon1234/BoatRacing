package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class HotbarListener implements Listener {
	private final BoatRacingPlugin plugin;
	private final HotbarService hotbar;

	public HotbarListener(BoatRacingPlugin plugin, HotbarService hotbar) {
		this.plugin = plugin;
		this.hotbar = hotbar;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInteract(PlayerInteractEvent e) {
		Player p = e.getPlayer();
		if (p == null) return;

		// On modern Paper, prefer resolving the held item via the interaction hand.
		ItemStack it = null;
		try {
			EquipmentSlot hand = e.getHand();
			if (hand == EquipmentSlot.OFF_HAND) it = p.getInventory().getItemInOffHand();
			else it = p.getInventory().getItemInMainHand();
		} catch (Throwable ignored) {
			try { it = e.getItem(); } catch (Throwable ignored2) { it = null; }
		}
		if (!hotbar.isHotbarItem(it)) return;

		// Cancel default interaction (place/use etc) and run action.
		e.setCancelled(true);
		try {
			e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
			e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
		} catch (Throwable ignored) {}

		HotbarService.Action a = hotbar.getAction(it);
		boolean right = e.getAction() != null && e.getAction().name().toUpperCase(java.util.Locale.ROOT).contains("RIGHT");
		hotbar.runAction(p, a, right);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onInventoryClick(InventoryClickEvent e) {
		if (!(e.getWhoClicked() instanceof Player p)) return;

		ItemStack current = e.getCurrentItem();
		ItemStack cursor = e.getCursor();

		boolean curHot = hotbar.isHotbarItem(current);
		boolean curCursor = hotbar.isHotbarItem(cursor);

		// Prevent moving hotbar items in any way.
		if (curHot || curCursor) {
			e.setCancelled(true);

			// If clicking the item itself, treat as an action.
			if (curHot && current != null) {
				HotbarService.Action a = hotbar.getAction(current);
				boolean right = e.getClick() == ClickType.RIGHT;
				hotbar.runAction(p, a, right);
			}
			return;
		}

		// Prevent swapping with hotbar keys if the hotbar target slot contains a hot item.
		try {
			if (e.getClick() == ClickType.NUMBER_KEY) {
				int hb = e.getHotbarButton();
				if (hb >= 0 && hb <= 8) {
					ItemStack hot = p.getInventory().getItem(hb);
					if (hotbar.isHotbarItem(hot)) {
						e.setCancelled(true);
					}
				}
			}
		} catch (Throwable ignored) {}

		// Prevent placing any item into offhand if it would displace a hotbar item already in offhand.
		try {
			int raw = e.getRawSlot();
			// Raw slot 45 is usually offhand in CraftBukkit views; this can vary, so be conservative:
			if (raw == 45) {
				ItemStack off = p.getInventory().getItemInOffHand();
				if (hotbar.isHotbarItem(off)) e.setCancelled(true);
			}
		} catch (Throwable ignored) {}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDrag(InventoryDragEvent e) {
		// Cancel any drag that involves a hotbar item.
		ItemStack old = e.getOldCursor();
		if (hotbar.isHotbarItem(old)) {
			e.setCancelled(true);
			return;
		}
		try {
			for (ItemStack it : e.getNewItems().values()) {
				if (hotbar.isHotbarItem(it)) {
					e.setCancelled(true);
					return;
				}
			}
		} catch (Throwable ignored) {}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent e) {
		if (e.getItemDrop() == null) return;
		ItemStack it = e.getItemDrop().getItemStack();
		if (!hotbar.isHotbarItem(it)) return;
		e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onSwap(PlayerSwapHandItemsEvent e) {
		ItemStack main = e.getMainHandItem();
		ItemStack off = e.getOffHandItem();
		if (hotbar.isHotbarItem(main) || hotbar.isHotbarItem(off)) {
			e.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		ItemStack it = e.getItemInHand();
		if (hotbar.isHotbarItem(it)) {
			e.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDeath(PlayerDeathEvent e) {
		if (e.getDrops() == null) return;
		e.getDrops().removeIf(hotbar::isHotbarItem);
	}
}
