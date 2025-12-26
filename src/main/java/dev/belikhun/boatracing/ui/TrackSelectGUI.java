package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class TrackSelectGUI implements Listener {
	private static final Component TITLE = Text.title("🗺 Chọn đường đua");

	private final BoatRacingPlugin plugin;
	private final NamespacedKey KEY_TRACK;

	public TrackSelectGUI(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.KEY_TRACK = new NamespacedKey(plugin, "track_select_name");
	}

	public void open(Player p) {
		if (p == null)
			return;

		List<String> tracks = new ArrayList<>();
		try {
			if (plugin.getTrackLibrary() != null)
				tracks.addAll(plugin.getTrackLibrary().list());
		} catch (Throwable ignored) {
		}

		int size = 54;
		Inventory inv = Bukkit.createInventory(null, size, TITLE);

		// Fill with simple panes.
		ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < size; i++)
			inv.setItem(i, filler);

		int idx = 0;
		for (String name : tracks) {
			if (name == null || name.isBlank())
				continue;
			if (idx >= size)
				break;

			ItemStack it = trackItem(name);
			inv.setItem(idx, it);
			idx++;
		}

		p.openInventory(inv);
		try {
			p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
		} catch (Throwable ignored) {
		}
	}

	private static String fmtElapsed(long ms) {
		long t = Math.max(0L, ms);
		long totalSec = t / 1000L;
		long h = totalSec / 3600L;
		long m = (totalSec % 3600L) / 60L;
		long s = totalSec % 60L;
		if (h > 0L)
			return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s);
		return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
	}

	private static String fmtCountdownSeconds(int sec) {
		int s = Math.max(0, sec);
		if (s >= 60) {
			int m = s / 60;
			int r = s % 60;
			return String.format(java.util.Locale.ROOT, "%d:%02d", m, r);
		}
		return s + "s";
	}

	private static int stackAmountForCount(int racers) {
		if (racers <= 0)
			return 1;
		return Math.min(64, racers);
	}

	private static void trySetGlint(ItemMeta meta, boolean glint) {
		if (meta == null)
			return;
		// Paper API: ItemMeta#setEnchantmentGlintOverride(Boolean)
		// Use reflection so the plugin still compiles/runs on any compatible API
		// surface.
		try {
			java.lang.reflect.Method m = meta.getClass().getMethod("setEnchantmentGlintOverride",
					java.lang.Boolean.class);
			m.invoke(meta, Boolean.valueOf(glint));
			return;
		} catch (Throwable ignored) {
		}
		try {
			java.lang.reflect.Method m = meta.getClass().getMethod("setEnchantmentGlintOverride", boolean.class);
			m.invoke(meta, glint);
		} catch (Throwable ignored) {
		}
	}

	private ItemStack trackItem(String trackName) {
		Material mat;
		List<String> lore = new ArrayList<>();

		RaceManager rm = null;
		try {
			rm = plugin.getRaceService().getOrCreate(trackName);
		} catch (Throwable ignored) {
			rm = null;
		}

		boolean ready = false;
		if (rm != null && rm.getTrackConfig() != null) {
			try {
				ready = rm.getTrackConfig().isReady();
			} catch (Throwable ignored) {
				ready = false;
			}
		}

		boolean running = rm != null && rm.isRunning();
		boolean countdown = rm != null && rm.isAnyCountdownActive();
		boolean registering = rm != null && rm.isRegistering();

		int racers = 0;
		if (rm != null) {
			try {
				racers = rm.getInvolved().size();
			} catch (Throwable ignored) {
				racers = 0;
			}
		}

		// State colors:
		// - Blue (enchanted) = currently playing / cannot join
		// - Red = maintenance/editing (not ready)
		// - Green = open/waiting for players
		if (rm == null) {
			mat = Material.BARRIER;
			lore.add("&cKhông thể tải đường đua này.");
			lore.add("&7Vui lòng thử lại hoặc kiểm tra file cấu hình.");
		} else if (!ready) {
			mat = Material.RED_CONCRETE;
			lore.add("&7Trạng thái: &cĐang bảo trì / chỉnh sửa");
			try {
				List<String> miss = rm.getTrackConfig().missingRequirements();
				if (miss != null && !miss.isEmpty())
					lore.add("&7Thiếu: &f" + String.join(", ", miss));
			} catch (Throwable ignored) {
			}
			lore.add("");
			lore.add("&7● &fChuột phải&7: &eXem thông tin");
			lore.add("&7● &fChuột trái&7: &cKhông thể tham gia");
		} else if (running || countdown) {
			mat = Material.BLUE_CONCRETE;
			lore.add("&7Trạng thái: &bĐang diễn ra");
			if (running) {
				try {
					lore.add("&7⏱ Đã chạy: &f" + fmtElapsed(rm.getRaceElapsedMillis()));
				} catch (Throwable ignored) {
				}
			} else {
				try {
					lore.add("&7⌛ Bắt đầu trong: &f" + fmtCountdownSeconds(rm.getCountdownRemainingSeconds()));
				} catch (Throwable ignored) {
				}
			}
			lore.add("");
			lore.add("&7● &fChuột phải&7: &eXem thông tin");
			lore.add("&7● &fChuột trái&7: &cKhông thể tham gia");
		} else {
			mat = Material.GREEN_CONCRETE;
			lore.add("&7Trạng thái: &aĐang mở (chờ tay đua)");
			if (registering) {
				try {
					lore.add("&7⌛ Bắt đầu trong: &f" + fmtCountdownSeconds(rm.getCountdownRemainingSeconds()));
				} catch (Throwable ignored) {
				}
			}
			lore.add("");
			lore.add("&7● &fChuột trái&7: &aTham gia đăng ký");
			lore.add("&7● &fChuột phải&7: &eXem thông tin");
		}

		// Always show racer count in lore when we can.
		if (rm != null) {
			lore.add(0, "&7👥 Tay đua: &f" + racers);
		}

		ItemStack it = new ItemStack(mat, (rm == null ? 1 : stackAmountForCount(racers)));
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.displayName(Text.item("&e" + trackName));
			im.lore(Text.lore(lore));

			// Enchanted glow for "currently playing" tracks.
			if (rm != null && ready && (running || countdown)) {
				trySetGlint(im, true);
			}

			im.addItemFlags(ItemFlag.values());
			im.getPersistentDataContainer().set(KEY_TRACK, PersistentDataType.STRING, trackName);
			it.setItemMeta(im);
		}
		return it;
	}

	private ItemStack pane(Material mat) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.displayName(Text.item("&r"));
			im.addItemFlags(ItemFlag.values());
			it.setItemMeta(im);
		}
		return it;
	}

	private boolean isThis(InventoryClickEvent e) {
		if (e.getView() == null)
			return false;
		String title = Text.plain(e.getView().title());
		return title.equals(Text.plain(TITLE));
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent e) {
		if (!isThis(e))
			return;
		e.setCancelled(true);
		if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory())
			return;
		HumanEntity who = e.getWhoClicked();
		if (!(who instanceof Player p))
			return;

		ItemStack it = e.getCurrentItem();
		if (it == null)
			return;
		ItemMeta im = it.getItemMeta();
		if (im == null)
			return;
		String track = im.getPersistentDataContainer().get(KEY_TRACK, PersistentDataType.STRING);
		if (track == null || track.isBlank())
			return;

		boolean right = e.getClick() == ClickType.RIGHT;
		if (right) {
			// Use existing status command output.
			try {
				p.closeInventory();
			} catch (Throwable ignored) {
			}
			try {
				Bukkit.dispatchCommand(p, "boatracing race status " + track);
			} catch (Throwable ignored) {
			}
			return;
		}

		// left click = join (only when open)
		RaceManager rm = null;
		try {
			rm = plugin.getRaceService().getOrCreate(track);
		} catch (Throwable ignored) {
			rm = null;
		}
		if (rm == null || rm.getTrackConfig() == null) {
			Text.msg(p, "&c❌ Không thể tải đường đua này.");
			return;
		}
		boolean ready = false;
		try {
			ready = rm.getTrackConfig().isReady();
		} catch (Throwable ignored) {
			ready = false;
		}
		if (!ready) {
			Text.msg(p, "&c❌ Đường đua đang bảo trì / chỉnh sửa.");
			return;
		}

		if (rm.isRunning() || rm.isAnyCountdownActive()) {
			Text.msg(p, "&c❌ Đường đua đang diễn ra, không thể tham gia lúc này.");
			return;
		}

		try {
			p.closeInventory();
		} catch (Throwable ignored) {
		}
		boolean ok = false;
		try {
			ok = plugin.getRaceService().join(track, p);
		} catch (Throwable ignored) {
			ok = false;
		}
		if (!ok) {
			Text.msg(p, "&cKhông thể tham gia đăng ký ngay lúc này.");
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDrag(InventoryDragEvent e) {
		if (e.getView() == null)
			return;
		String title = Text.plain(e.getView().title());
		if (title.equals(Text.plain(TITLE)))
			e.setCancelled(true);
	}
}
