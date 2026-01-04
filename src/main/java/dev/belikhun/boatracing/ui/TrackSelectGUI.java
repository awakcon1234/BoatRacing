package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TrackSelectGUI implements Listener {
	private static final Component TITLE = Text.title("🗺 Chọn đường đua");

	private final BoatRacingPlugin plugin;
	private final NamespacedKey KEY_TRACK;
	private final ItemStack fillerPane;
	private final File tracksDir;
	private final Map<String, TrackSummary> summaryCache = new HashMap<>();
	private final TrackConfig scratchConfig;

	private static final class TrackSummary {
		final String name;
		final long lastModified;
		final boolean loaded;
		final boolean ready;
		final List<String> missing;
		final double trackLength;
		final ItemStack icon;
		final java.util.UUID authorId;
		final String authorName;
		final String authorText;

		TrackSummary(String name, long lastModified, boolean loaded, boolean ready, List<String> missing,
				double trackLength, ItemStack icon, java.util.UUID authorId, String authorName, String authorText) {
			this.name = name;
			this.lastModified = lastModified;
			this.loaded = loaded;
			this.ready = ready;
			this.missing = (missing == null) ? java.util.Collections.emptyList() : java.util.List.copyOf(missing);
			this.trackLength = trackLength;
			this.icon = icon;
			this.authorId = authorId;
			this.authorName = authorName;
			this.authorText = authorText;
		}
	}

	public TrackSelectGUI(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.KEY_TRACK = new NamespacedKey(plugin, "track_select_name");
		this.fillerPane = pane(Material.GRAY_STAINED_GLASS_PANE);
		this.tracksDir = new File(plugin.getDataFolder(), "tracks");
		this.scratchConfig = new TrackConfig(plugin, plugin.getDataFolder());
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

		try {
			summaryCache.keySet().retainAll(tracks);
		} catch (Throwable ignored) {
		}

		int size = 54;
		Inventory inv = Bukkit.createInventory(null, size, TITLE);

		// Fill with simple panes.
		for (int i = 0; i < size; i++)
			inv.setItem(i, fillerPane);

		int idx = 0;
		for (String name : tracks) {
			if (name == null || name.isBlank())
				continue;
			if (idx >= size)
				break;

			ItemStack it = trackItem(p, name);
			inv.setItem(idx, it);
			idx++;
		}

		p.openInventory(inv);
		try {
			p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
		} catch (Throwable ignored) {
		}
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

	private TrackSummary getTrackSummary(String trackName) {
		if (trackName == null || trackName.isBlank()) {
			return new TrackSummary(trackName, -1L, false, false, Collections.emptyList(), -1.0, null, null, null, null);
		}

		File f = new File(tracksDir, trackName + ".yml");
		long mod = f.exists() ? f.lastModified() : -1L;
		TrackSummary cached = summaryCache.get(trackName);
		if (cached != null && cached.lastModified == mod)
			return cached;

		boolean loaded;
		boolean ready;
		List<String> missing;
		double trackLength;
		ItemStack icon;
		java.util.UUID authorId;
		String authorName;
		String authorText;

		if (!f.exists()) {
			loaded = false;
			ready = false;
			missing = Collections.emptyList();
			trackLength = -1.0;
			icon = null;
			authorId = null;
			authorName = null;
			authorText = null;
		} else {
			synchronized (scratchConfig) {
				loaded = scratchConfig.load(trackName);
				if (loaded) {
					ready = scratchConfig.isReady();
					missing = scratchConfig.missingRequirements();
					trackLength = scratchConfig.getTrackLength();
					icon = scratchConfig.getIcon();
					authorId = scratchConfig.getAuthorId();
					authorName = scratchConfig.getAuthorName();
					authorText = scratchConfig.getAuthorText();
				} else {
					ready = false;
					missing = Collections.emptyList();
					trackLength = -1.0;
					icon = null;
					authorId = null;
					authorName = null;
					authorText = null;
				}
			}
		}

		TrackSummary summary = new TrackSummary(trackName, mod, loaded, ready, missing, trackLength, icon, authorId, authorName, authorText);
		summaryCache.put(trackName, summary);
		return summary;
	}

	private void appendRecordLines(java.util.UUID viewerId, String trackName, List<String> lore) {
		if (trackName == null || trackName.isBlank() || lore == null) return;

		// Global track record
		String trLine = null;
		try {
			var m = plugin.getTrackRecordManager();
			var pm = plugin.getProfileManager();
			var r = (m != null ? m.get(trackName) : null);
			if (r != null && r.bestTimeMillis > 0L) {
				String t = Time.formatStopwatchMillis(r.bestTimeMillis);
				String holderName = (r.holderName == null || r.holderName.isBlank()) ? "(không rõ)" : r.holderName;
				String holder;
				if (pm != null) holder = pm.formatRacerLegacy(r.holderId, holderName);
				else holder = "&f" + holderName;
				trLine = "&7⌚ Kỷ lục: &f" + t + " &7bởi " + holder;
			}
		} catch (Throwable ignored) {
			trLine = null;
		}
		if (trLine == null) trLine = "&7⌚ Kỷ lục: &f-";
		lore.add(trLine);

		// Per-player personal best
		String pbLine = null;
		try {
			var pm = plugin.getProfileManager();
			if (pm != null && viewerId != null) {
				long ms = pm.getPersonalBestMillis(viewerId, trackName);
				if (ms > 0L) pbLine = "&7⌚ Kỷ lục cá nhân: &f" + Time.formatStopwatchMillis(ms);
			}
		} catch (Throwable ignored) {
			pbLine = null;
		}
		if (pbLine == null) pbLine = "&7⌚ Kỷ lục cá nhân: &f-";
		lore.add(pbLine);
	}

	private void appendAuthorLine(TrackSummary summary, List<String> lore) {
		if (summary == null || lore == null)
			return;
		String authorLine = null;
		try {
			java.util.UUID id = summary.authorId;
			String name = summary.authorName;
			String txt = summary.authorText;
			if (id != null) {
				String display;
				String n = (name == null || name.isBlank()) ? "(không rõ)" : name;
				try {
					var pm = plugin.getProfileManager();
					display = (pm != null) ? pm.formatRacerLegacy(id, n) : ("&f" + n);
				} catch (Throwable ignored) {
					display = "&f" + n;
				}
				authorLine = "&7✎ Tác giả: " + display;
			} else if (txt != null && !txt.isBlank()) {
				authorLine = "&7✎ Tác giả: &f" + txt;
			}
		} catch (Throwable ignored) {
			authorLine = null;
		}
		if (authorLine != null)
			lore.add(authorLine);
	}

	private double resolveTrackLength(RaceManager rm, TrackSummary summary) {
		double len = -1.0;
		if (rm != null) {
			try {
				if (rm.getTrackConfig() != null)
					len = rm.getTrackConfig().getTrackLength();
			} catch (Throwable ignored) {
				len = -1.0;
			}
		}
		if ((len <= 0.0 || !Double.isFinite(len)) && summary != null)
			len = summary.trackLength;
		return len;
	}

	private ItemStack trackItem(Player viewer, String trackName) {
		Material mat;
		List<String> lore = new ArrayList<>();
		java.util.UUID viewerId = (viewer == null ? null : viewer.getUniqueId());

		RaceManager rm = null;
		try {
			// Do not create/cached-load races just for GUI display.
			rm = plugin.getRaceService().get(trackName);
		} catch (Throwable ignored) {
			rm = null;
		}

		TrackSummary summary = getTrackSummary(trackName);
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
		if (!summary.loaded) {
			mat = Material.BARRIER;
			lore.add("&cKhông thể tải đường đua này.");
			appendRecordLines(viewerId, trackName, lore);
			lore.add("");
			lore.add("&7Vui lòng thử lại hoặc kiểm tra file cấu hình.");
		} else if (!summary.ready) {
			mat = Material.RED_CONCRETE;
			lore.add("&7Trạng thái: &cĐang bảo trì / chỉnh sửa");
			if (summary.missing != null && !summary.missing.isEmpty())
				lore.add("&7Thiếu: &f" + String.join(", ", summary.missing));
			appendAuthorLine(summary, lore);
			appendRecordLines(viewerId, trackName, lore);
			lore.add("");
			lore.add("&7● &fChuột phải&7: &eXem thông tin");
			lore.add("&7● &fChuột trái&7: &cKhông thể tham gia");
		} else if (running || countdown) {
			mat = Material.BLUE_CONCRETE;
			lore.add("&7Trạng thái: &bĐang diễn ra");
			if (running) {
				try {
					lore.add("&7⌚ Đã chạy: &f" + Time.formatDurationShort(rm.getRaceElapsedMillis()));
				} catch (Throwable ignored) {
				}
			} else {
				try {
					lore.add("&7⌛ Bắt đầu trong: &f" + Time.formatCountdownSeconds(rm.getCountdownRemainingSeconds()));
				} catch (Throwable ignored) {
				}
			}
			appendAuthorLine(summary, lore);
			appendRecordLines(viewerId, trackName, lore);
			lore.add("");
			lore.add("&7● &fChuột phải&7: &aTheo dõi");
			lore.add("&7● &fShift + chuột phải&7: &eXem thông tin");
			lore.add("&7● &fChuột trái&7: &cKhông thể tham gia");
		} else {
			mat = Material.GREEN_CONCRETE;
			lore.add("&7Trạng thái: &aĐang mở (chờ tay đua)");
			if (registering) {
				try {
					lore.add("&7⌛ Bắt đầu trong: &f" + Time.formatCountdownSeconds(rm.getCountdownRemainingSeconds()));
				} catch (Throwable ignored) {
				}
			}
			appendAuthorLine(summary, lore);
			appendRecordLines(viewerId, trackName, lore);
			lore.add("");
			lore.add("&7● &fChuột trái&7: &aTham gia đăng ký");
			lore.add("&7● &fChuột phải&7: &eXem thông tin");
		}

		// Always show racer count and track length when we can.
		double trackLength = resolveTrackLength(rm, summary);
		if (rm != null || (trackLength > 0.0 && Double.isFinite(trackLength))) {
			int idx = 0;
			if (rm != null) {
				lore.add(idx++, "&7👥 Tay đua: &f" + racers);
			}
			String len = (trackLength > 0.0 && Double.isFinite(trackLength))
					? (String.format(java.util.Locale.US, "%.1f", trackLength) + "m")
					: "-";
			lore.add(idx, "&7🛣 Độ dài: &f" + len);
			lore.add(idx, "");
		}

		ItemStack baseIcon = summary.icon;
		ItemStack it;
		if (rm != null && baseIcon != null && baseIcon.getType() != Material.AIR) {
			it = baseIcon.clone();
			try { it.setAmount(stackAmountForCount(racers)); } catch (Throwable ignored) {}
		} else if (baseIcon != null && baseIcon.getType() != Material.AIR) {
			it = baseIcon.clone();
		} else {
			it = new ItemStack(mat, (rm == null ? 1 : stackAmountForCount(racers)));
		}
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.displayName(Text.item("&e" + trackName));
			im.lore(Text.lore(lore));

			// Enchanted glow for "currently playing" tracks.
			if (rm != null && summary.ready && (running || countdown)) {
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
		boolean shiftRight = e.getClick() == ClickType.SHIFT_RIGHT;
		if (right || shiftRight) {
			RaceManager rm = null;
			try {
				rm = plugin.getRaceService().getOrCreate(track);
			} catch (Throwable ignored) {
				rm = null;
			}
			boolean running = rm != null && (rm.isRunning() || rm.isAnyCountdownActive());

			// During an active race: right click = spectate, shift-right = info.
			if (running && right) {
				try {
					p.closeInventory();
				} catch (Throwable ignored) {
				}

				// Can't spectate while involved in any race.
				try {
					if (plugin.getRaceService().findRaceFor(p.getUniqueId()) != null) {
						Text.msg(p, "&cBạn đang tham gia/đăng ký một cuộc đua. Hãy rời cuộc đua trước khi theo dõi.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return;
					}
				} catch (Throwable ignored) {
				}

				boolean ok = false;
				try {
					ok = plugin.getRaceService().spectateStart(track, p);
				} catch (Throwable ignored) {
					ok = false;
				}
				if (!ok) {
					Text.msg(p, "&cKhông thể vào chế độ theo dõi lúc này.");
					try {
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					} catch (Throwable ignored) {
					}
				}
				return;
			}

			// Info fallback: use existing status command output.
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
