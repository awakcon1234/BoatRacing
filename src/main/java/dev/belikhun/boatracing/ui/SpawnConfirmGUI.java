package dev.belikhun.boatracing.ui;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.util.Text;
import net.kyori.adventure.text.Component;

public class SpawnConfirmGUI implements Listener {
	private static final Component TITLE_CONFIRM = Text.title("Rời cuộc đua?");

	private static final int SLOT_CONFIRM = 11;
	private static final int SLOT_CANCEL = 15;

	private final BoatRacingPlugin plugin;
	private final Set<UUID> viewing = Collections.synchronizedSet(new HashSet<>());

	public SpawnConfirmGUI(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	public void open(Player p) {
		if (p == null)
			return;

		Inventory inv = Bukkit.createInventory(null, 27, TITLE_CONFIRM);

		// Confirm button
		inv.setItem(SLOT_CONFIRM, item(Material.RED_DYE,
				"&c&lBỏ cuộc và về sảnh",
				List.of(
						"&7Bạn sẽ rời khỏi cuộc đua hiện tại.",
						"",
						"&7● &cTiến trình cuộc đua sẽ bị hủy",
						"&7● &7Bạn sẽ được dịch chuyển về sảnh",
						"",
						"&eNhấp để xác nhận")));

		// Cancel button
		inv.setItem(SLOT_CANCEL, item(Material.LIME_DYE,
				"&a&lTiếp tục đua",
				List.of(
						"&7Quay lại cuộc đua.",
						"",
						"&eNhấp để hủy")));

		// Info (center)
		inv.setItem(13, item(Material.PAPER,
				"&e&lXác nhận",
				List.of(
						"&7Bạn đang trong cuộc đua.",
						"",
						"&7Dùng &f/spawn &7để về sảnh.",
						"&7Hệ thống cần bạn xác nhận trước khi bỏ cuộc.")));

		viewing.add(p.getUniqueId());
		p.openInventory(inv);
	}

	@EventHandler(ignoreCancelled = true)
	public void onClick(InventoryClickEvent e) {
		if (!(e.getWhoClicked() instanceof Player p))
			return;
		if (e.getView() == null)
			return;

		String plainTitle = null;
		try {
			plainTitle = Text.plain(e.getView().title());
		} catch (Throwable ignored) {
			plainTitle = null;
		}
		if (plainTitle == null || !plainTitle.equals(Text.plain(TITLE_CONFIRM)))
			return;

		// Our GUI: block item movement
		e.setCancelled(true);

		if (!viewing.contains(p.getUniqueId()))
			return;

		int slot = e.getRawSlot();
		if (slot == SLOT_CANCEL) {
			p.closeInventory();
			p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
			Text.msg(p, "&7Đã hủy. Tiếp tục cuộc đua.");
			return;
		}

		if (slot == SLOT_CONFIRM) {
			p.closeInventory();
			p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);

			try {
				var rs = (plugin != null ? plugin.getRaceService() : null);
				if (rs != null)
					rs.abandonNow(p, true);
			} catch (Throwable ignored) {
			}

			Text.msg(p, "&aĐã bỏ cuộc và dịch chuyển về sảnh.");
			return;
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent e) {
		if (!(e.getPlayer() instanceof Player p))
			return;
		try {
			String plainTitle = Text.plain(e.getView().title());
			if (plainTitle == null || !plainTitle.equals(Text.plain(TITLE_CONFIRM)))
				return;
		} catch (Throwable ignored) {
			return;
		}
		viewing.remove(p.getUniqueId());
	}

	private ItemStack item(Material mat, String name, List<String> loreLines) {
		ItemStack it = new ItemStack(mat);
		ItemMeta meta = it.getItemMeta();
		if (meta != null) {
			meta.displayName(Text.item(name));
			if (loreLines != null && !loreLines.isEmpty()) {
				java.util.List<Component> lore = new java.util.ArrayList<>();
				for (String s : loreLines)
					lore.add(Text.item(s));
				meta.lore(lore);
			}
			it.setItemMeta(meta);
		}
		return it;
	}
}
