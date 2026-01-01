package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.EventParticipant;
import dev.belikhun.boatracing.event.EventParticipantStatus;
import dev.belikhun.boatracing.event.EventService;
import dev.belikhun.boatracing.event.EventState;
import dev.belikhun.boatracing.event.RaceEvent;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EventRegistrationGUI implements Listener {
	private static final Component TITLE_MAIN = Text.title("Đăng ký sự kiện");
	private static final Component TITLE_PARTICIPANTS = Text.title("Người tham gia sự kiện");

	private final BoatRacingPlugin plugin;

	private final NamespacedKey KEY_ACTION;
	private final NamespacedKey KEY_VALUE;

	private enum Action {
		REGISTER,
		UNREGISTER,
		PARTICIPANTS,
		BACK,
		REFRESH,
		CLOSE
	}

	public EventRegistrationGUI(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.KEY_ACTION = new NamespacedKey(plugin, "boatracing_event_reg_gui_action");
		this.KEY_VALUE = new NamespacedKey(plugin, "boatracing_event_reg_gui_value");
	}

	private EventService svc() {
		try {
			return plugin != null ? plugin.getEventService() : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	public void open(Player p) {
		openMain(p);
	}

	private void openMain(Player p) {
		if (p == null)
			return;
		EventService svc = svc();
		RaceEvent e = (svc != null ? svc.getActiveEvent() : null);

		int size = 27;
		Inventory inv = Bukkit.createInventory(null, size, TITLE_MAIN);

		// Layout: [11] register/unregister, [13] info, [15] participants
		inv.setItem(11, registerButton(p, e));
		inv.setItem(13, infoCard(e));
		inv.setItem(15, buttonWithLore(Material.PLAYER_HEAD, Text.item("&b&lDanh sách người tham gia"), Action.PARTICIPANTS,
				List.of(
						"&7Xem toàn bộ người tham gia và trạng thái.",
						"",
						"&eBấm: &fMở danh sách"
				), true, null));

		inv.setItem(22, buttonWithLore(Material.CLOCK, Text.item("&e&lLàm mới"), Action.REFRESH,
				List.of("&7Cập nhật thông tin."), true, null));
		inv.setItem(26, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE,
				List.of("&7Đóng."), true, null));

		p.openInventory(inv);
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
	}

	private void openParticipants(Player p) {
		if (p == null)
			return;
		EventService svc = svc();
		RaceEvent e = (svc != null ? svc.getActiveEvent() : null);

		int size = 54;
		Inventory inv = Bukkit.createInventory(null, size, TITLE_PARTICIPANTS);

		List<EventParticipant> list = participantsOrdered(e);
		int slot = 0;
		for (EventParticipant ep : list) {
			if (slot >= size - 9)
				break;
			inv.setItem(slot++, participantHead(ep));
		}

		int base = size - 9;
		inv.setItem(base, buttonWithLore(Material.ARROW, Text.item("&7&lTrở về"), Action.BACK,
				List.of("&7Về màn hình đăng ký."), true, null));
		inv.setItem(base + 4, buttonWithLore(Material.CLOCK, Text.item("&e&lLàm mới"), Action.REFRESH,
				List.of("&7Cập nhật danh sách."), true, null));
		inv.setItem(base + 8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE,
				List.of("&7Đóng."), true, null));

		p.openInventory(inv);
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
	}

	private ItemStack pane(Material mat) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.displayName(Text.item("&r"));
			it.setItemMeta(im);
		}
		return it;
	}

	private ItemStack registerButton(Player p, RaceEvent e) {
		boolean canInteract = (e != null && e.state == EventState.REGISTRATION);
		boolean isReg = isRegistered(p, e);

		if (!canInteract) {
			return buttonWithLore(Material.GRAY_DYE, Text.item("&7&lĐăng ký"), Action.REGISTER,
					List.of(
							"&7Hiện chưa mở đăng ký sự kiện.",
							"",
							"&7Không thể thao tác lúc này."
					), false, null);
		}

		if (isReg) {
			return buttonWithLore(Material.RED_DYE, Text.item("&c&lHủy đăng ký"), Action.UNREGISTER,
					List.of(
							"&7Bạn đang ở trong danh sách đăng ký.",
							"",
							"&eBấm: &fHủy đăng ký"
					), true, null);
		}

		return buttonWithLore(Material.LIME_DYE, Text.item("&a&lĐăng ký"), Action.REGISTER,
				List.of(
						"&7Tham gia danh sách đăng ký sự kiện.",
						"",
						"&eBấm: &fĐăng ký"
				), true, null);
	}

	private ItemStack infoCard(RaceEvent e) {
		String title = (e == null || e.title == null || e.title.isBlank()) ? "(không có)" : e.title;
		String state = eventStateColored(e == null ? null : e.state);

		int total = 0;
		int reg = 0;
		if (e != null && e.participants != null) {
			total = e.participants.size();
			for (EventParticipant ep : e.participants.values()) {
				if (ep != null && ep.status == EventParticipantStatus.REGISTERED)
					reg++;
			}
		}

		String timeLeft = "";
		if (e != null && e.state == EventState.REGISTRATION) {
			if (e.startTimeMillis > 0L) {
				long remainSec = Math.max(0L, (e.startTimeMillis - System.currentTimeMillis()) / 1000L);
				int sec = (int) Math.min(Integer.MAX_VALUE, remainSec);
				timeLeft = Time.formatCountdownSeconds(sec);
			} else {
				timeLeft = "Chưa đặt lịch";
			}
		}

		List<String> lore = new ArrayList<>();
		lore.add("&7Tên: &f" + title);
		lore.add("&7Trạng thái: " + state);
		lore.add("&7Đã đăng ký: &a" + reg + "&7/&f" + total);
		lore.add("&7Tối đa: &f-");
		if (!timeLeft.isBlank()) {
			lore.add("&7Còn lại: &e" + timeLeft);
		}

		if (e == null) {
			lore.add("");
			lore.add("&cHiện không có sự kiện đang hoạt động.");
		}

		return buttonWithLore(Material.BOOK, Text.item("&6&lThông tin sự kiện"), Action.REFRESH, lore, false, "info");
	}

	private static String eventStateColored(EventState st) {
		if (st == null)
			return "&7(không rõ)";
		return switch (st) {
			case DRAFT -> "&7Nháp";
			case REGISTRATION -> "&aĐang mở đăng ký";
			case RUNNING -> "&bĐang diễn ra";
			case COMPLETED -> "&6Đã kết thúc";
			case CANCELLED -> "&cĐã hủy";
		};
	}

	private boolean isRegistered(Player p, RaceEvent e) {
		if (p == null || e == null || e.participants == null)
			return false;
		EventParticipant ep = e.participants.get(p.getUniqueId());
		return ep != null && ep.status == EventParticipantStatus.REGISTERED;
	}

	private List<EventParticipant> participantsOrdered(RaceEvent e) {
		if (e == null || e.participants == null || e.participants.isEmpty())
			return List.of();

		Map<UUID, EventParticipant> map = new HashMap<>(e.participants);
		List<EventParticipant> out = new ArrayList<>();

		try {
			if (e.registrationOrder != null) {
				for (UUID id : e.registrationOrder) {
					if (id == null)
						continue;
					EventParticipant ep = map.remove(id);
					if (ep != null)
						out.add(ep);
				}
			}
		} catch (Throwable ignored) {
		}

		List<EventParticipant> rest = new ArrayList<>(map.values());
		rest.sort(Comparator.comparing((EventParticipant ep) -> {
			String n = (ep == null ? null : ep.nameSnapshot);
			return (n == null ? "" : n.toLowerCase(java.util.Locale.ROOT));
		}));
		out.addAll(rest);
		return out;
	}

	private ItemStack participantHead(EventParticipant ep) {
		ItemStack it = new ItemStack(Material.PLAYER_HEAD);
		ItemMeta im = it.getItemMeta();
		if (!(im instanceof SkullMeta sm) || ep == null) {
			return it;
		}

		String name = (ep.nameSnapshot == null || ep.nameSnapshot.isBlank()) ? "(không rõ)" : ep.nameSnapshot;
		String st = switch (ep.status) {
			case REGISTERED -> "&aĐã đăng ký";
			case ACTIVE -> "&eĐang tham gia";
			case LEFT -> "&cĐã rời";
		};

		sm.displayName(Text.item("&f" + name));
		List<String> lore = new ArrayList<>();
		lore.add("&7Trạng thái: " + st);
		lore.add("");
		lore.add("&7UUID: &8" + ep.id);
		sm.lore(Text.lore(lore));

		try {
			sm.setOwningPlayer(Bukkit.getOfflinePlayer(ep.id));
		} catch (Throwable ignored) {
		}

		it.setItemMeta(sm);
		return it;
	}

	private ItemStack buttonWithLore(Material mat, Component name, Action action, List<String> lore, boolean enabled, String value) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		if (im == null)
			return it;
		im.displayName(name);
		if (lore != null)
			im.lore(Text.lore(lore));
		try {
			im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action.name());
			if (value != null)
				im.getPersistentDataContainer().set(KEY_VALUE, PersistentDataType.STRING, value);
		} catch (Throwable ignored) {
		}

		// Add a small glow hint via enchant if enabled (matches other GUIs)
		if (enabled) {
			try {
				im.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
				im.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
			} catch (Throwable ignored) {
			}
		}

		it.setItemMeta(im);
		return it;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent e) {
		Inventory top = e.getView().getTopInventory();
		if (top == null)
			return;
		String title = Text.plain(e.getView().title());
		boolean inMain = title.equals(Text.plain(TITLE_MAIN));
		boolean inParticipants = title.equals(Text.plain(TITLE_PARTICIPANTS));
		if (!inMain && !inParticipants)
			return;

		e.setCancelled(true);
		if (e.getClickedInventory() == null || e.getClickedInventory() != top)
			return;
		HumanEntity he = e.getWhoClicked();
		if (!(he instanceof Player p))
			return;

		ItemStack it = e.getCurrentItem();
		if (it == null)
			return;
		ItemMeta im = it.getItemMeta();
		if (im == null)
			return;

		String actStr = null;
		try {
			actStr = im.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
		} catch (Throwable ignored) {
			actStr = null;
		}
		if (actStr == null)
			return;
		Action action;
		try {
			action = Action.valueOf(actStr);
		} catch (Throwable ignored) {
			return;
		}

		EventService svc = svc();
		RaceEvent ev = (svc != null ? svc.getActiveEvent() : null);

		switch (action) {
			case CLOSE -> p.closeInventory();
			case REFRESH -> {
				if (inParticipants)
					openParticipants(p);
				else
					openMain(p);
			}
			case BACK -> openMain(p);
			case PARTICIPANTS -> openParticipants(p);
			case REGISTER -> {
				if (svc == null) {
					Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return;
				}
				if (ev == null || ev.state != EventState.REGISTRATION) {
					Text.msg(p, "&cHiện chưa mở đăng ký sự kiện.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return;
				}
				boolean ok = svc.registerToActiveEvent(p);
				if (!ok) {
					Text.msg(p, "&cKhông thể đăng ký lúc này.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return;
				}
				Text.msg(p, "&a✔ Đã đăng ký sự kiện: &f" + (ev.title == null ? "(không rõ)" : ev.title));
				p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.3f);
				openMain(p);
			}
			case UNREGISTER -> {
				if (svc == null) {
					Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return;
				}
				if (ev == null) {
					Text.msg(p, "&cHiện không có sự kiện nào.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return;
				}
				boolean ok = svc.leaveActiveEvent(p);
				if (!ok) {
					Text.msg(p, "&cBạn chưa đăng ký sự kiện.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return;
				}
				Text.msg(p, "&aĐã hủy đăng ký sự kiện.");
				p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
				openMain(p);
			}
		}

		// Prevent hotbar quick-move into GUI
		if (e.getClick() == ClickType.NUMBER_KEY)
			e.setCancelled(true);
	}
}
