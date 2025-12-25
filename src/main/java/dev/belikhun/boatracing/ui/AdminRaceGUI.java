package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.track.TrackLibrary;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin Race GUI: manage race lifecycle and select tracks.
 */
public class AdminRaceGUI implements Listener {
    private static final Component TITLE = Text.title("Quản lý cuộc đua");
    private static final Component TITLE_TRACKS = Text.title("Chọn đường đua");

    private final BoatRacingPlugin plugin;
    private final NamespacedKey KEY_ACTION;
    private final NamespacedKey KEY_TRACK;

    private enum Action {
        OPEN_REG,
        START,
        FORCE,
        STOP,
        REFRESH,
        PICK_TRACK,
        BACK,
        CLOSE
    }

    public AdminRaceGUI(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.KEY_ACTION = new NamespacedKey(plugin, "race-action");
        this.KEY_TRACK = new NamespacedKey(plugin, "race-track");
    }

    public void open(Player p) {
        if (!hasRacePerm(p)) {
            Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        // Status summary item
        inv.setItem(10, statusItem());

        inv.setItem(12, buttonWithLore(Material.LIME_WOOL, Text.item("&a&lMở đăng ký"), Action.OPEN_REG,
                List.of("&7Mở đăng ký vào cuộc đua cho đường đua hiện tại."), true));
        inv.setItem(13, buttonWithLore(Material.EMERALD_BLOCK, Text.item("&2&lBắt đầu"), Action.START,
                List.of("&7Đặt người chơi đã đăng ký vào vị trí và bắt đầu đếm ngược."), true));
        inv.setItem(14, buttonWithLore(Material.REDSTONE_TORCH, Text.item("&6&lForce start"), Action.FORCE,
                List.of("&7Bắt đầu ngay lập tức bằng danh sách đăng ký hiện tại."), true));
        inv.setItem(15, buttonWithLore(Material.RED_CONCRETE, Text.item("&c&lDừng"), Action.STOP,
                List.of("&7Dừng đăng ký/cuộc đua đang diễn ra."), true));

        inv.setItem(16, buttonWithLore(Material.CLOCK, Text.item("&e&lLàm mới"), Action.REFRESH,
                List.of("&7Cập nhật thông tin trạng thái."), true));

        inv.setItem(22, buttonWithLore(Material.MAP, Text.item("&b&lChọn đường đua"), Action.PICK_TRACK,
                List.of("&7Chọn đường đua làm đường đua hiện tại."), true));
        inv.setItem(26, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE,
                List.of("&7Đóng bảng quản lý đua."), true));

        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
    }

    private boolean hasRacePerm(Player p) {
        return p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup");
    }

    private ItemStack statusItem() {
        TrackLibrary lib = plugin.getTrackLibrary();
        String tname = lib != null && lib.getCurrent() != null ? lib.getCurrent() : "(unsaved)";

        RaceManager rm = (lib != null && lib.getCurrent() != null) ? plugin.getRaceService().getOrCreate(lib.getCurrent()) : null;
        TrackConfig cfg = (rm != null) ? rm.getTrackConfig() : plugin.getTrackConfig();

        boolean running = rm != null && rm.isRunning();
        boolean registering = rm != null && rm.isRegistering();
        int regs = rm != null ? rm.getRegistered().size() : 0;
        int laps = rm != null ? rm.getTotalLaps() : plugin.getRaceService().getDefaultLaps();
        int participants = (rm != null && running) ? rm.getParticipants().size() : 0;
        int starts = cfg.getStarts().size();
        int lights = cfg.getLights().size();
        int cps = cfg.getCheckpoints().size();
        boolean hasFinish = cfg.getFinish() != null;
        boolean hasPit = cfg.getPitlane() != null; // kept for status visibility; mechanic disabled
        boolean ready = cfg.isReady();

        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(Text.item("&f&lTrạng thái đua"));
            List<String> lore = new ArrayList<>();
            lore.add("&7Đường đua: &f" + tname);
            lore.add(running ? "&aĐang chạy &7(Tham gia: &f" + participants + "&7)" : "&7Không có cuộc đua đang chạy.");
            lore.add(registering ? "&eĐăng ký mở &7(Đã đăng ký: &f" + regs + "&7)" : "&7Đăng ký đóng.");
            lore.add("&7Số vòng: &f" + laps);
            lore.add("&7Bắt đầu: &f" + starts + " &8● &7Đèn: &f" + lights + "/5 &8● &7Đích: &f" + (hasFinish?"có":"không") + " &8● &7Pit: &f" + (hasPit?"có":"không"));
            lore.add("&7Checkpoints: &f" + cps);
            // pit mechanic removed
            lore.add(" ");
            lore.add(ready ? "&aĐường đua sẵn sàng." : "&cChưa sẵn sàng: &7" + String.join(", ", cfg.missingRequirements()));
            im.lore(Text.lore(lore));
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

    private ItemStack buttonWithLore(Material mat, Component name, Action action, List<String> lore, boolean enabled) {
        ItemStack it = new ItemStack(enabled ? mat : Material.RED_STAINED_GLASS_PANE);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(name);
            if (lore != null && !lore.isEmpty()) im.lore(Text.lore(lore));
            im.addItemFlags(ItemFlag.values());
            im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action.name());
            it.setItemMeta(im);
        }
        return it;
    }

    public void openTrackPicker(Player p) {
        if (!hasRacePerm(p)) {
            Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        List<String> names = new ArrayList<>();
        if (plugin.getTrackLibrary() != null) names.addAll(plugin.getTrackLibrary().list());
        int rows = Math.max(2, (names.size() / 9) + 1);
        int size = Math.min(54, rows * 9);
        Inventory inv = Bukkit.createInventory(null, size, TITLE_TRACKS);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        int slot = 0;
        for (String n : names) {
            ItemStack it = new ItemStack(Material.MAP);
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.displayName(Text.item("&f" + n));
                im.lore(Text.lore(List.of("&7Bấm: &fChọn đường đua này")));
                im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, Action.PICK_TRACK.name());
                im.getPersistentDataContainer().set(KEY_TRACK, PersistentDataType.STRING, n);
                it.setItemMeta(im);
            }
            inv.setItem(slot++, it);
            if (slot >= size - 9) break;
        }

        // Back and close controls
        int base = size - 9;
        inv.setItem(base, buttonWithLore(Material.ARROW, Text.item("&7&lTrở về"), Action.BACK, List.of("&7Về bảng quản lý đua."), true));
        inv.setItem(base + 8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true));

        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top == null) return;
        String title = Text.plain(e.getView().title());
        boolean inMain = title.equals(Text.plain(TITLE));
        boolean inPicker = title.equals(Text.plain(TITLE_TRACKS));
        if (!inMain && !inPicker) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;

        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        if (!hasRacePerm(p)) {
            Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }

        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        String actStr = im.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
        if (actStr == null) return;

        Action action;
        try { action = Action.valueOf(actStr); } catch (IllegalArgumentException ex) { return; }

        switch (action) {
            case OPEN_REG -> doOpenRegistration(p);
            case START -> doStart(p);
            case FORCE -> doForceStart(p);
            case STOP -> doStop(p);
            case REFRESH -> open(p);
            case PICK_TRACK -> {
                if (inMain) {
                    openTrackPicker(p);
                } else {
                    String tname = im.getPersistentDataContainer().get(KEY_TRACK, PersistentDataType.STRING);
                    if (tname != null) {
                        if (!plugin.getTrackLibrary().exists(tname)) {
                            Text.msg(p, "&cKhông tìm thấy đường đua: &f" + tname);
                            return;
                        }
                        if (!plugin.getTrackLibrary().select(tname)) {
                            Text.msg(p, "&cKhông thể tải đường đua: &f" + tname);
                            return;
                        }
                        Text.msg(p, "&aĐã chọn đường đua: &f" + tname);
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.25f);
                        open(p);
                    }
                }
            }
            case BACK -> open(p);
            case CLOSE -> p.closeInventory();
        }
    }

    private void doOpenRegistration(Player p) {
        TrackLibrary lib = plugin.getTrackLibrary();
        String tname = lib != null && lib.getCurrent() != null ? lib.getCurrent() : null;
        if (tname == null) { Text.msg(p, "&cChưa có đường đua được chọn."); return; }

        RaceManager rm = plugin.getRaceService().getOrCreate(tname);
        if (rm == null) {
            Text.msg(p, "&cKhông thể tải đường đua: &f" + tname);
            return;
        }
        TrackConfig cfg = rm.getTrackConfig();

        if (!cfg.isReady()) {
            Text.msg(p, "&cĐường đua chưa sẵn sàng: &7" + String.join(", ", cfg.missingRequirements()));
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        int laps = rm.getTotalLaps();
        boolean ok = rm.openRegistration(laps, null);
        if (!ok) {
            Text.msg(p, "&cKhông thể mở đăng ký lúc này.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        } else {
            Text.msg(p, "&a📝 Đã mở đăng ký cho &f" + tname);
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            open(p);
        }
    }

    private void doStart(Player p) {
        TrackLibrary lib = plugin.getTrackLibrary();
        String tname = lib != null && lib.getCurrent() != null ? lib.getCurrent() : null;
        if (tname == null) { Text.msg(p, "&cChưa có đường đua được chọn."); return; }

        RaceManager rm = plugin.getRaceService().getOrCreate(tname);
        if (rm == null) {
            Text.msg(p, "&cKhông thể tải đường đua: &f" + tname);
            return;
        }
        TrackConfig cfg = rm.getTrackConfig();

        if (!cfg.isReady()) {
            Text.msg(p, "&cĐường đua chưa sẵn sàng: &7" + String.join(", ", cfg.missingRequirements()));
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        if (rm.isRunning()) { Text.msg(p, "&cCuộc đua đang diễn ra."); return; }
        // Build strict participants list from registered set
        List<Player> participants = new ArrayList<>();
        for (UUID id : new java.util.LinkedHashSet<>(rm.getRegistered())) {
            Player rp = Bukkit.getPlayer(id);
            if (rp != null && rp.isOnline()) participants.add(rp);
        }
        if (participants.isEmpty()) { Text.msg(p, "&cKhông có người tham gia đã đăng ký. Hãy mở đăng ký trước."); return; }
        List<Player> placed = rm.placeAtStartsWithBoats(participants);
        if (placed.isEmpty()) { Text.msg(p, "&cKhông còn vị trí bắt đầu trống trên đường đua này."); return; }
        if (placed.size() < participants.size()) { Text.msg(p, "&e⚠ Một số người chơi đã đăng ký không thể vào vị trí xuất phát do thiếu slot."); }
        rm.startLightsCountdown(placed);
        Text.msg(p, "&a▶ Đã bắt đầu cuộc đua.");
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        open(p);
    }

    private void doForceStart(Player p) {
        TrackLibrary lib = plugin.getTrackLibrary();
        String tname = lib != null && lib.getCurrent() != null ? lib.getCurrent() : null;
        if (tname == null) { Text.msg(p, "&cChưa có đường đua được chọn."); return; }

        RaceManager rm = plugin.getRaceService().getOrCreate(tname);
        if (rm == null) {
            Text.msg(p, "&cKhông thể tải đường đua: &f" + tname);
            return;
        }

        if (rm.getRegistered().isEmpty()) {
            Text.msg(p, "&cKhông có người tham gia đã đăng ký. &7Mở đăng ký trước.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        rm.forceStart();
        Text.msg(p, "&a⚡ Đã bắt đầu ngay.");
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
        open(p);
    }

    private void doStop(Player p) {
        TrackLibrary lib = plugin.getTrackLibrary();
        String tname = lib != null && lib.getCurrent() != null ? lib.getCurrent() : null;
        boolean any = false;
        if (tname != null) any = plugin.getRaceService().stopRace(tname, true);
        if (!any) {
            Text.msg(p, "&7Không có gì để dừng.");
        } else {
            Text.msg(p, "&a⏹ Đã dừng cuộc đua.");
        }
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
        open(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView() == null) return;
        String title = Text.plain(e.getView().title());
        if (title.equals(Text.plain(TITLE)) || title.equals(Text.plain(TITLE_TRACKS))) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // No-op
    }
}

