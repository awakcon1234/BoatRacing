package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.EventService;
import dev.belikhun.boatracing.event.RaceEvent;
import dev.belikhun.boatracing.util.Text;
import net.kyori.adventure.text.Component;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AdminEventGUI implements Listener {
	private static final Component TITLE = Text.title("Quản lý sự kiện");
	private static final Component TITLE_PICK = Text.title("Chọn sự kiện");
	private static final Component TITLE_TRACKS = Text.title("Track pool");
	private static final Component TITLE_TRACK_ADD = Text.title("Thêm track");
	private static final Component TITLE_TRACK_REMOVE = Text.title("Xóa track");

	private final BoatRacingPlugin plugin;
	private final NamespacedKey KEY_ACTION;
	private final NamespacedKey KEY_VALUE;

	private final Map<UUID, String> selectedEventByPlayer = new HashMap<>();
	private final Map<UUID, String> pendingCreateEventId = new HashMap<>();

	private enum Action {
		PICK_EVENT,
		CREATE_EVENT,
		OPEN_REG,
		SCHEDULE,
		START,
		CANCEL,
		TRACK_POOL,
		TRACK_ADD,
		TRACK_REMOVE,
		BACK,
		REFRESH,
		CLOSE
	}

	public AdminEventGUI(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.KEY_ACTION = new NamespacedKey(plugin, "event-admin-action");
		this.KEY_VALUE = new NamespacedKey(plugin, "event-admin-value");
	}

	private boolean hasPerm(Player p) {
		return p != null && p.hasPermission("boatracing.event.admin");
	}

	private EventService svc() {
		return plugin != null ? plugin.getEventService() : null;
	}

	public void open(Player p) {
		if (!hasPerm(p)) {
			Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			return;
		}
		EventService svc = svc();
		if (svc == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			return;
		}

		// Default selection: active event if no selection yet.
		try {
			if (!selectedEventByPlayer.containsKey(p.getUniqueId())) {
				RaceEvent active = svc.getActiveEvent();
				if (active != null && active.id != null && !active.id.isBlank()) {
					selectedEventByPlayer.put(p.getUniqueId(), active.id);
				}
			}
		} catch (Throwable ignored) {
		}

		int size = 27;
		Inventory inv = Bukkit.createInventory(null, size, TITLE);
		ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < size; i++)
			inv.setItem(i, filler);

		inv.setItem(10, statusCard(p));

		inv.setItem(12, buttonWithLore(Material.MAP, Text.item("&b&lChọn sự kiện"), Action.PICK_EVENT,
				List.of(
						"&7Chọn sự kiện để quản lý.",
						" ",
						"&eBấm: &fMở danh sách sự kiện"
				), true, null));

		inv.setItem(13, buttonWithLore(Material.ANVIL, Text.item("&a&lTạo sự kiện"), Action.CREATE_EVENT,
				List.of(
						"&7Tạo sự kiện mới (DRAFT).",
						"&8Bạn sẽ nhập ID và tiêu đề.",
						" ",
						"&eBấm: &fNhập ID"
				), true, null));

		inv.setItem(14, buttonWithLore(Material.LIME_WOOL, Text.item("&a&lMở đăng ký"), Action.OPEN_REG,
				List.of(
						"&7Mở đăng ký cho sự kiện đã chọn.",
						"&8Chỉ 1 sự kiện hoạt động tại một thời điểm.",
						" ",
						"&eBấm: &fMở đăng ký"
				), true, null));

		inv.setItem(15, buttonWithLore(Material.CLOCK, Text.item("&e&lĐặt lịch"), Action.SCHEDULE,
				List.of(
						"&7Đặt giờ bắt đầu sau X giây.",
						"&8Chỉ dùng khi đang ở REGISTRATION.",
						" ",
						"&eBấm: &fNhập số giây"
				), true, null));

		inv.setItem(16, buttonWithLore(Material.EMERALD_BLOCK, Text.item("&2&lBắt đầu"), Action.START,
				List.of(
						"&7Bắt đầu sự kiện ngay.",
						"&8Cần có track pool hợp lệ.",
						" ",
						"&eBấm: &fBắt đầu"
				), true, null));

		inv.setItem(21, buttonWithLore(Material.RED_CONCRETE, Text.item("&c&lHủy"), Action.CANCEL,
				List.of(
						"&7Hủy sự kiện đang hoạt động.",
						" ",
						"&eBấm: &fHủy sự kiện"
				), true, null));

		inv.setItem(22, buttonWithLore(Material.WRITABLE_BOOK, Text.item("&d&lTrack pool"), Action.TRACK_POOL,
				List.of(
						"&7Quản lý danh sách đường đua của sự kiện.",
						" ",
						"&eBấm: &fMở track pool"
				), true, null));

		inv.setItem(23, buttonWithLore(Material.CLOCK, Text.item("&e&lLàm mới"), Action.REFRESH,
				List.of("&7Cập nhật thông tin."), true, null));

		inv.setItem(26, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE,
				List.of("&7Đóng."), true, null));

		p.openInventory(inv);
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
	}

	private ItemStack statusCard(Player p) {
		EventService svc = svc();
		RaceEvent active = (svc != null) ? svc.getActiveEvent() : null;
		String selected = selectedEventByPlayer.get(p.getUniqueId());
		RaceEvent sel = (selected != null && svc != null) ? svc.get(selected) : null;

		ItemStack it = new ItemStack(Material.PAPER);
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.displayName(Text.item("&f&lTrạng thái sự kiện"));
			List<String> lore = new ArrayList<>();

			lore.add("&7Sự kiện đang hoạt động: &f" + (active == null ? "(không có)" : safe(active.title)));
			lore.add("&7ID: &f" + (active == null ? "-" : safe(active.id)));
			lore.add("&7Trạng thái: &f" + (active == null || active.state == null ? "-" : active.state.name()));
			int regs = (active != null && active.participants != null) ? active.participants.size() : 0;
			int pool = (active != null && active.trackPool != null) ? active.trackPool.size() : 0;
			lore.add("&7Đã đăng ký: &f" + regs + " &8● &7Track pool: &f" + pool);

			lore.add(" ");
			lore.add("&7Đang chọn: &f" + (sel == null ? "(chưa chọn)" : safe(sel.title)));
			lore.add("&7ID chọn: &f" + (sel == null ? "-" : safe(sel.id)));

			if (active != null && sel != null && active.id != null && active.id.equals(sel.id)) {
				lore.add(" ");
				lore.add("&a✔ Đang quản lý đúng sự kiện đang hoạt động.");
			}

			im.lore(Text.lore(lore));
			im.addItemFlags(ItemFlag.values());
			it.setItemMeta(im);
		}
		return it;
	}

	private ItemStack pane(Material mat) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.displayName(Component.text(" "));
			im.addItemFlags(ItemFlag.values());
			it.setItemMeta(im);
		}
		return it;
	}

	private ItemStack buttonWithLore(Material mat, Component name, Action action, List<String> lore, boolean enabled, String value) {
		ItemStack it = new ItemStack(enabled ? mat : Material.RED_STAINED_GLASS_PANE);
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.displayName(name);
			if (lore != null && !lore.isEmpty())
				im.lore(Text.lore(lore));
			im.addItemFlags(ItemFlag.values());
			im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action.name());
			if (value != null)
				im.getPersistentDataContainer().set(KEY_VALUE, PersistentDataType.STRING, value);
			it.setItemMeta(im);
		}
		return it;
	}

	public void openPicker(Player p) {
		if (!hasPerm(p)) {
			Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
			return;
		}
		EventService svc = svc();
		if (svc == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			return;
		}

		List<RaceEvent> events = new ArrayList<>(svc.allEvents());
		events.sort(Comparator.comparing((RaceEvent e) -> e == null ? "" : safe(e.title), String.CASE_INSENSITIVE_ORDER));

		int rows = Math.max(2, (events.size() / 9) + 1);
		int size = Math.min(54, rows * 9);
		Inventory inv = Bukkit.createInventory(null, size, TITLE_PICK);
		ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < size; i++)
			inv.setItem(i, filler);

		int slot = 0;
		for (RaceEvent e : events) {
			if (e == null || e.id == null || e.id.isBlank())
				continue;
			String id = e.id.trim();
			String title = safe(e.title);
			String state = e.state == null ? "-" : e.state.name();
			int pool = e.trackPool == null ? 0 : e.trackPool.size();

			List<String> lore = new ArrayList<>();
			lore.add("&7ID: &f" + id);
			lore.add("&7Trạng thái: &f" + state);
			lore.add("&7Track pool: &f" + pool);
			lore.add(" ");
			lore.add("&eBấm: &fChọn sự kiện này");

			inv.setItem(slot++, buttonWithLore(Material.PAPER, Text.item("&f" + title), Action.PICK_EVENT, lore, true, id));
			if (slot >= size - 9)
				break;
		}

		int base = size - 9;
		inv.setItem(base, buttonWithLore(Material.ARROW, Text.item("&7&lTrở về"), Action.BACK, List.of("&7Về quản lý sự kiện."), true, null));
		inv.setItem(base + 8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true, null));

		p.openInventory(inv);
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
	}

	private String selectedEventId(Player p) {
		if (p == null)
			return null;
		String id = selectedEventByPlayer.get(p.getUniqueId());
		return (id == null || id.isBlank()) ? null : id.trim();
	}

	private void openTrackPool(Player p) {
		EventService svc = svc();
		if (svc == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			return;
		}
		String id = selectedEventId(p);
		RaceEvent e = (id != null) ? svc.get(id) : null;
		if (e == null) {
			Text.msg(p, "&cChưa chọn sự kiện.");
			return;
		}

		int size = 54;
		Inventory inv = Bukkit.createInventory(null, size, TITLE_TRACKS);
		ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < size; i++)
			inv.setItem(i, filler);

		List<String> pool = (e.trackPool == null) ? List.of() : new ArrayList<>(e.trackPool);
		int slot = 0;
		for (String tn : pool) {
			if (tn == null || tn.isBlank())
				continue;
			List<String> lore = new ArrayList<>();
			lore.add("&7Đường đua: &f" + tn);
			lore.add(" ");
			lore.add("&eBấm: &fXóa khỏi pool");
			inv.setItem(slot++, buttonWithLore(Material.MAP, Text.item("&f" + tn), Action.TRACK_REMOVE, lore, true, tn));
			if (slot >= size - 9)
				break;
		}

		int base = size - 9;
		inv.setItem(base + 3, buttonWithLore(Material.LIME_WOOL, Text.item("&a&lThêm track"), Action.TRACK_ADD,
				List.of("&7Chọn đường đua để thêm vào pool.", " ", "&eBấm: &fMở danh sách đường đua"), true, null));
		inv.setItem(base + 5, buttonWithLore(Material.RED_WOOL, Text.item("&c&lXóa track"), Action.TRACK_REMOVE,
				List.of("&7Chọn track ở phía trên để xóa.", "&8(Bấm trực tiếp vào track)"), true, null));
		inv.setItem(base, buttonWithLore(Material.ARROW, Text.item("&7&lTrở về"), Action.BACK, List.of("&7Về quản lý sự kiện."), true, null));
		inv.setItem(base + 8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true, null));

		p.openInventory(inv);
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
	}

	private void openTrackAddPicker(Player p) {
		EventService svc = svc();
		if (svc == null)
			return;
		String id = selectedEventId(p);
		RaceEvent e = (id != null) ? svc.get(id) : null;
		if (e == null) {
			Text.msg(p, "&cChưa chọn sự kiện.");
			return;
		}

		List<String> names = new ArrayList<>();
		try {
			if (plugin.getTrackLibrary() != null)
				names.addAll(plugin.getTrackLibrary().list());
		} catch (Throwable ignored) {
		}

		int rows = Math.max(2, (names.size() / 9) + 1);
		int size = Math.min(54, rows * 9);
		Inventory inv = Bukkit.createInventory(null, size, TITLE_TRACK_ADD);
		ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < size; i++)
			inv.setItem(i, filler);

		int slot = 0;
		for (String tn : names) {
			if (tn == null || tn.isBlank())
				continue;
			List<String> lore = new ArrayList<>();
			lore.add("&7Thêm vào track pool.");
			lore.add(" ");
			lore.add("&eBấm: &fThêm track");
			inv.setItem(slot++, buttonWithLore(Material.MAP, Text.item("&f" + tn), Action.TRACK_ADD, lore, true, tn));
			if (slot >= size - 9)
				break;
		}

		int base = size - 9;
		inv.setItem(base, buttonWithLore(Material.ARROW, Text.item("&7&lTrở về"), Action.BACK, List.of("&7Về track pool."), true, null));
		inv.setItem(base + 8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true, null));

		p.openInventory(inv);
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
	}

	private void openTrackRemovePicker(Player p) {
		EventService svc = svc();
		if (svc == null)
			return;
		String id = selectedEventId(p);
		RaceEvent e = (id != null) ? svc.get(id) : null;
		if (e == null) {
			Text.msg(p, "&cChưa chọn sự kiện.");
			return;
		}

		List<String> pool = (e.trackPool == null) ? List.of() : new ArrayList<>(e.trackPool);
		pool.sort(String.CASE_INSENSITIVE_ORDER);

		int rows = Math.max(2, (pool.size() / 9) + 1);
		int size = Math.min(54, rows * 9);
		Inventory inv = Bukkit.createInventory(null, size, TITLE_TRACK_REMOVE);
		ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < size; i++)
			inv.setItem(i, filler);

		int slot = 0;
		for (String tn : pool) {
			if (tn == null || tn.isBlank())
				continue;
			List<String> lore = new ArrayList<>();
			lore.add("&7Xóa khỏi track pool.");
			lore.add(" ");
			lore.add("&eBấm: &fXóa track");
			inv.setItem(slot++, buttonWithLore(Material.MAP, Text.item("&f" + tn), Action.TRACK_REMOVE, lore, true, tn));
			if (slot >= size - 9)
				break;
		}

		int base = size - 9;
		inv.setItem(base, buttonWithLore(Material.ARROW, Text.item("&7&lTrở về"), Action.BACK, List.of("&7Về track pool."), true, null));
		inv.setItem(base + 8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true, null));

		p.openInventory(inv);
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent e) {
		Inventory top = e.getView().getTopInventory();
		if (top == null)
			return;
		String title = Text.plain(e.getView().title());
		boolean inMain = title.equals(Text.plain(TITLE));
		boolean inPick = title.equals(Text.plain(TITLE_PICK));
		boolean inTracks = title.equals(Text.plain(TITLE_TRACKS));
		boolean inAdd = title.equals(Text.plain(TITLE_TRACK_ADD));
		boolean inRemove = title.equals(Text.plain(TITLE_TRACK_REMOVE));
		if (!inMain && !inPick && !inTracks && !inAdd && !inRemove)
			return;

		e.setCancelled(true);
		if (e.getClickedInventory() == null || e.getClickedInventory() != top)
			return;

		HumanEntity he = e.getWhoClicked();
		if (!(he instanceof Player p))
			return;
		if (!hasPerm(p)) {
			Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			return;
		}

		ItemStack it = e.getCurrentItem();
		if (it == null)
			return;
		ItemMeta im = it.getItemMeta();
		if (im == null)
			return;
		String actStr = im.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
		if (actStr == null)
			return;

		Action action;
		try {
			action = Action.valueOf(actStr);
		} catch (IllegalArgumentException ex) {
			return;
		}

		String value = im.getPersistentDataContainer().get(KEY_VALUE, PersistentDataType.STRING);

		switch (action) {
			case CLOSE -> p.closeInventory();
			case REFRESH -> open(p);
			case BACK -> {
				if (inPick)
					open(p);
				else if (inTracks)
					open(p);
				else if (inAdd || inRemove)
					openTrackPool(p);
				else
					open(p);
			}
			case PICK_EVENT -> {
				if (inMain) {
					openPicker(p);
					return;
				}
				if (value == null || value.isBlank())
					return;
				selectedEventByPlayer.put(p.getUniqueId(), value.trim());
				Text.msg(p, "&aĐã chọn sự kiện.");
				p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.25f);
				open(p);
			}
			case CREATE_EVENT -> beginCreateEvent(p);
			case OPEN_REG -> doOpenReg(p);
			case SCHEDULE -> beginSchedule(p);
			case START -> doStart(p);
			case CANCEL -> doCancel(p);
			case TRACK_POOL -> openTrackPool(p);
			case TRACK_ADD -> {
				if (inTracks) {
					openTrackAddPicker(p);
				} else {
					doTrackAdd(p, value);
				}
			}
			case TRACK_REMOVE -> {
				if (inTracks) {
					openTrackRemovePicker(p);
				} else {
					doTrackRemove(p, value);
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDrag(InventoryDragEvent e) {
		if (e.getView() == null)
			return;
		String title = Text.plain(e.getView().title());
		if (title.equals(Text.plain(TITLE))
				|| title.equals(Text.plain(TITLE_PICK))
				|| title.equals(Text.plain(TITLE_TRACKS))
				|| title.equals(Text.plain(TITLE_TRACK_ADD))
				|| title.equals(Text.plain(TITLE_TRACK_REMOVE))) {
			e.setCancelled(true);
		}
	}

	private void beginCreateEvent(Player p) {
		pendingCreateEventId.remove(p.getUniqueId());
		p.closeInventory();

		new AnvilGUI.Builder()
				.plugin(plugin)
				.title(Text.plain(Text.title("Nhập ID sự kiện")))
				.itemLeft(new ItemStack(Material.NAME_TAG))
				.text("event-id")
				.onClick((slot, state) -> {
					if (slot != AnvilGUI.Slot.OUTPUT)
						return List.of();
					String id = state.getText() == null ? "" : state.getText().trim();
					if (id.isBlank()) {
						Text.msg(p, "&cID không hợp lệ.");
						return List.of(AnvilGUI.ResponseAction.close());
					}
					pendingCreateEventId.put(p.getUniqueId(), id);
					Bukkit.getScheduler().runTask(plugin, () -> beginCreateTitle(p));
					return List.of(AnvilGUI.ResponseAction.close());
				})
				.open(p);
	}

	private void beginCreateTitle(Player p) {
		new AnvilGUI.Builder()
				.plugin(plugin)
				.title(Text.plain(Text.title("Nhập tiêu đề")))
				.itemLeft(new ItemStack(Material.PAPER))
				.text("Tiêu đề")
				.onClick((slot, state) -> {
					if (slot != AnvilGUI.Slot.OUTPUT)
						return List.of();
					String title = state.getText() == null ? "" : state.getText().trim();
					String id = pendingCreateEventId.remove(p.getUniqueId());
					if (id == null || id.isBlank()) {
						Text.msg(p, "&cThiếu ID. Hãy thử lại.");
						return List.of(AnvilGUI.ResponseAction.close());
					}
					EventService svc = svc();
					if (svc == null) {
						Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
						return List.of(AnvilGUI.ResponseAction.close());
					}
					boolean ok = svc.createEvent(id, title);
					if (!ok) {
						Text.msg(p, "&cKhông thể tạo sự kiện. &7Có thể ID đã tồn tại.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					} else {
						selectedEventByPlayer.put(p.getUniqueId(), id);
						Text.msg(p, "&a✔ Đã tạo sự kiện: &f" + safe(title) + " &7(ID: &f" + id + "&7)");
						p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
					}
					Bukkit.getScheduler().runTask(plugin, () -> open(p));
					return List.of(AnvilGUI.ResponseAction.close());
				})
				.open(p);
	}

	private void doOpenReg(Player p) {
		EventService svc = svc();
		if (svc == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			return;
		}
		String id = selectedEventId(p);
		if (id == null) {
			Text.msg(p, "&cChưa chọn sự kiện.");
			return;
		}
		boolean ok = svc.openRegistration(id);
		if (!ok) {
			Text.msg(p, "&cKhông thể mở đăng ký. &7Chỉ 1 sự kiện có thể hoạt động.");
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			return;
		}
		Text.msg(p, "&a📝 Đã mở đăng ký sự kiện.");
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.25f);
		open(p);
	}

	private void beginSchedule(Player p) {
		p.closeInventory();

		new AnvilGUI.Builder()
				.plugin(plugin)
				.title(Text.plain(Text.title("Nhập số giây")))
				.itemLeft(new ItemStack(Material.CLOCK))
				.text("30")
				.onClick((slot, state) -> {
					if (slot != AnvilGUI.Slot.OUTPUT)
						return List.of();
					String s = state.getText() == null ? "" : state.getText().trim();
					int sec;
					try {
						sec = Integer.parseInt(s);
					} catch (Throwable t) {
						Text.msg(p, "&cSố không hợp lệ.");
						return List.of(AnvilGUI.ResponseAction.close());
					}
					EventService svc = svc();
					if (svc == null) {
						Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
						return List.of(AnvilGUI.ResponseAction.close());
					}
					boolean ok = svc.scheduleActiveEvent(sec);
					if (!ok) {
						Text.msg(p, "&cKhông thể đặt lịch lúc này. &7Hãy đảm bảo đang ở REGISTRATION.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					} else {
						Text.msg(p, "&a⏳ Đã đặt giờ bắt đầu sau &f" + sec + "&a giây.");
						p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
					}
					Bukkit.getScheduler().runTask(plugin, () -> open(p));
					return List.of(AnvilGUI.ResponseAction.close());
				})
				.open(p);
	}

	private void doStart(Player p) {
		EventService svc = svc();
		if (svc == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			return;
		}
		boolean ok = svc.startActiveEventNow();
		if (!ok) {
			Text.msg(p, "&cKhông thể bắt đầu. &7Kiểm tra track pool và trạng thái.");
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			return;
		}
		Text.msg(p, "&a▶ Đã bắt đầu sự kiện.");
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
		open(p);
	}

	private void doCancel(Player p) {
		EventService svc = svc();
		if (svc == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			return;
		}
		boolean ok = svc.cancelActiveEvent();
		if (!ok) {
			Text.msg(p, "&cKhông có sự kiện để hủy.");
			return;
		}
		Text.msg(p, "&a⎋ Đã hủy sự kiện.");
		p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.0f);
		open(p);
	}

	private void doTrackAdd(Player p, String trackName) {
		if (trackName == null || trackName.isBlank())
			return;
		EventService svc = svc();
		if (svc == null)
			return;
		String eventId = selectedEventId(p);
		if (eventId == null || eventId.isBlank()) {
			Text.msg(p, "&cChưa chọn sự kiện.");
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			open(p);
			return;
		}
		String tn = trackName.trim();
		dev.belikhun.boatracing.event.EventService.TrackPoolResult r = svc.addTrackToEvent(eventId, tn);
		if (r != dev.belikhun.boatracing.event.EventService.TrackPoolResult.OK) {
			switch (r) {
				case NO_SUCH_EVENT -> Text.msg(p, "&cSự kiện không tồn tại.");
				case EVENT_RUNNING -> Text.msg(p, "&cKhông thể chỉnh track khi sự kiện đang chạy.");
				case TRACK_INVALID -> Text.msg(p, "&cTên track không hợp lệ.");
				case DUPLICATE -> Text.msg(p, "&eTrack đã có trong pool.");
				default -> Text.msg(p, "&cKhông thể thêm track.");
			}
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
		} else {
			Text.msg(p, "&a✔ Đã thêm track: &f" + tn);
			p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
		}
		openTrackPool(p);
	}

	private void doTrackRemove(Player p, String trackName) {
		if (trackName == null || trackName.isBlank())
			return;
		EventService svc = svc();
		if (svc == null)
			return;
		String eventId = selectedEventId(p);
		if (eventId == null || eventId.isBlank()) {
			Text.msg(p, "&cChưa chọn sự kiện.");
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			open(p);
			return;
		}
		String tn = trackName.trim();
		dev.belikhun.boatracing.event.EventService.TrackPoolResult r = svc.removeTrackFromEvent(eventId, tn);
		if (r != dev.belikhun.boatracing.event.EventService.TrackPoolResult.OK) {
			switch (r) {
				case NO_SUCH_EVENT -> Text.msg(p, "&cSự kiện không tồn tại.");
				case EVENT_RUNNING -> Text.msg(p, "&cKhông thể chỉnh track khi sự kiện đang chạy.");
				case TRACK_INVALID -> Text.msg(p, "&cTên track không hợp lệ.");
				case NOT_FOUND -> Text.msg(p, "&eTrack này không có trong pool.");
				default -> Text.msg(p, "&cKhông thể xóa track.");
			}
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
		} else {
			Text.msg(p, "&a✔ Đã xóa track: &f" + tn);
			p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
		}
		openTrackPool(p);
	}

	private static String safe(String s) {
		if (s == null)
			return "(không rõ)";
		String t = s.trim();
		return t.isEmpty() ? "(không rõ)" : t;
	}
}
