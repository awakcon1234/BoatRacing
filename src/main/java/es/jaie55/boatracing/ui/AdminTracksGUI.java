package es.jaie55.boatracing.ui;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.track.Region;
import es.jaie55.boatracing.track.SelectionUtils;
import es.jaie55.boatracing.track.TrackConfig;
import es.jaie55.boatracing.track.TrackLibrary;
import es.jaie55.boatracing.util.Text;
import net.kyori.adventure.text.Component;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class AdminTracksGUI implements Listener {
    private static final Component TITLE = Text.title("Quản lý đường đua");
    private static final Component TITLE_PICK = Text.title("Chọn/ tạo đường đua");
    private final BoatRacingPlugin plugin;
    private final TrackLibrary lib;
    private final NamespacedKey KEY_ACTION;
    private final NamespacedKey KEY_TRACK;
    // Per-player visualization task ids for centerline debug drawing
    private final Map<java.util.UUID, Integer> vizTasks = new HashMap<>();

    private enum Action {
        PICK_TRACK,
        NEW_TRACK,
        SAVE,
        SAVE_AS,
        SET_BOUNDS,
        SET_WAIT_SPAWN,
        ADD_START,
        CLEAR_STARTS,
        SET_FINISH,
        ADD_CHECKPOINT,
        CLEAR_CHECKPOINTS,
        ADD_LIGHT,
        CLEAR_LIGHTS,
        REFRESH,
        BACK,
        CLOSE,
        BUILD_PATH,
        TOGGLE_VIZ,
    }

    public AdminTracksGUI(BoatRacingPlugin plugin, TrackLibrary trackLibrary) {
        this.plugin = plugin;
        this.lib = trackLibrary;
        this.KEY_ACTION = new NamespacedKey(plugin, "tracks-action");
        this.KEY_TRACK = new NamespacedKey(plugin, "tracks-track");
    }

    private boolean hasSetup(Player p) { return p.hasPermission("boatracing.setup"); }

    public void open(Player p) {
        if (!hasSetup(p)) { Text.msg(p, "&cBạn không có quyền thực hiện điều đó."); return; }
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        // Top status
        inv.setItem(10, statusCard());

        // Row of core actions
        inv.setItem(12, buttonWithLore(Material.MAP, Text.item("&b&lChọn đường"), Action.PICK_TRACK,
                List.of("&7Chọn đường đua hiện tại"), true));
        inv.setItem(13, buttonWithLore(Material.PAPER, Text.item("&a&lLưu"), Action.SAVE,
                List.of("&7Lưu cấu hình đường đua hiện tại"), true));
        inv.setItem(14, buttonWithLore(Material.BOOK, Text.item("&e&lLưu thành..."), Action.SAVE_AS,
                List.of("&7Nhập tên mới để lưu"), true));
        inv.setItem(16, buttonWithLore(Material.CLOCK, Text.item("&e&lLàm mới"), Action.REFRESH,
                List.of("&7Cập nhật thông tin"), true));
        boolean vizOn = vizTasks.containsKey(p.getUniqueId());
        inv.setItem(15, buttonWithLore(vizOn ? Material.AMETHYST_SHARD : Material.GLASS,
            Text.item((vizOn?"&d&lẨn":"&d&lHiện") + " đường giữa"), Action.TOGGLE_VIZ,
            List.of(vizOn?"&7Tắt hiển thị đường giữa bằng hạt": "&7Hiện đường giữa bằng hạt (debug)",
                "&8Mẹo: chỉ hiện các nút trong phạm vi 64m"), true));

        // Editing tools
        inv.setItem(18, buttonWithLore(Material.OAK_BOAT, Text.item("&aThêm Start"), Action.ADD_START,
                List.of("&7Thêm vị trí hiện tại làm vị trí bắt đầu"), true));
        inv.setItem(19, buttonWithLore(Material.BARRIER, Text.item("&cXóa Start"), Action.CLEAR_STARTS,
                List.of("&7Xóa tất cả vị trí bắt đầu"), true));
        inv.setItem(20, buttonWithLore(Material.BEACON, Text.item("&bĐặt Vùng bao"), Action.SET_BOUNDS,
            List.of("&7Dùng selection để đặt vùng bao (bounds)"), true));
        inv.setItem(21, buttonWithLore(Material.WHITE_BANNER, Text.item("&6Đặt Đích"), Action.SET_FINISH,
                List.of("&7Dùng selection để đặt vùng đích"), true));
        inv.setItem(22, buttonWithLore(Material.RESPAWN_ANCHOR, Text.item("&aĐặt Spawn chờ"), Action.SET_WAIT_SPAWN,
            List.of("&7Đặt điểm spawn chờ từ vị trí hiện tại"), true));
        // Pit mechanic disabled: hide pit button
        inv.setItem(23, buttonWithLore(Material.LODESTONE, Text.item("&aThêm Checkpoint"), Action.ADD_CHECKPOINT,
                List.of("&7Dùng selection để thêm checkpoint"), true));
        inv.setItem(24, buttonWithLore(Material.REDSTONE_LAMP, Text.item("&6Thêm Đèn"), Action.ADD_LIGHT,
                List.of("&7Nhìn vào Đèn Redstone và bấm"), true));
        inv.setItem(25, buttonWithLore(Material.LAVA_BUCKET, Text.item("&cXóa Checkpoint"), Action.CLEAR_CHECKPOINTS,
                List.of("&7Xóa tất cả checkpoint"), true));
        inv.setItem(26, buttonWithLore(Material.FLINT_AND_STEEL, Text.item("&cXóa Đèn"), Action.CLEAR_LIGHTS,
            List.of("&7Xóa tất cả đèn xuất phát"), true));
        // Place build-path button in free slot (11) to avoid exceeding 27-slot inventory bounds
        inv.setItem(11, buttonWithLore(Material.COMPASS, Text.item("&b&lXây dựng đường giữa"), Action.BUILD_PATH,
            List.of("&7Tạo đường giữa bằng A* trên băng."), true));

        // Close
        // Move close button to top-right corner to avoid overlap with editing tools
        inv.setItem(8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE,
            List.of("&7Đóng trình quản lý đường đua"), true));

        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
    }

    private ItemStack statusCard() {
        TrackConfig cfg = plugin.getTrackConfig();
        String name = plugin.getTrackLibrary().getCurrent();
        if (name == null) name = "(chưa chọn)";
        int starts = cfg.getStarts().size();
        int lights = cfg.getLights().size();
        int cps = cfg.getCheckpoints().size();
        boolean hasFinish = cfg.getFinish() != null;
        boolean hasBounds = cfg.getBounds() != null;
        boolean hasWaitSpawn = cfg.getWaitingSpawn() != null;
        int pathNodes = cfg.getCenterline().size();
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(Text.item("&f&lĐường: &e" + name));
            List<String> lore = new ArrayList<>();
            lore.add("&7Starts: &f" + starts);
            lore.add("&7Đèn: &f" + lights + "/5");
            lore.add("&7Đích: &f" + (hasFinish?"có":"không"));
            lore.add("&7Vùng bao: &f" + (hasBounds?"có":"không"));
            lore.add("&7Spawn chờ: &f" + (hasWaitSpawn?"có":"không"));
            // pit removed from gameplay; optional to display
            lore.add("&7Checkpoints: &f" + cps);
            lore.add("&7Đường giữa: &f" + pathNodes + " nút");
            if (!cfg.isReady()) lore.add("&cChưa sẵn sàng: &7" + String.join(", ", cfg.missingRequirements()));
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

    public void openPicker(Player p) {
        if (!hasSetup(p)) { Text.msg(p, "&cBạn không có quyền thực hiện điều đó."); return; }
        List<String> names = lib.list();
        int rows = Math.max(2, (names.size() / 9) + 1);
        int size = Math.min(54, rows * 9);
        Inventory inv = Bukkit.createInventory(null, size, TITLE_PICK);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);
        int slot = 0;
        for (String n : names) {
            ItemStack it = new ItemStack(Material.MAP);
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.displayName(Text.item("&f" + n));
                im.lore(Text.lore(List.of("&7Bấm để chọn")));
                PersistentDataContainer pdc = im.getPersistentDataContainer();
                pdc.set(KEY_ACTION, PersistentDataType.STRING, Action.PICK_TRACK.name());
                pdc.set(KEY_TRACK, PersistentDataType.STRING, n);
                it.setItemMeta(im);
            }
            inv.setItem(slot++, it);
            if (slot >= size - 9) break;
        }
        int base = size - 9;
        inv.setItem(base, buttonWithLore(Material.ANVIL, Text.item("&a&lTạo mới"), Action.NEW_TRACK, List.of("&7Nhập tên để tạo đường mới"), true));
        inv.setItem(base + 8, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
    }

    private void promptNewTrack(Player p) {
        new AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.plain(Text.title("Tên đường mới")))
            .text("new-track")
            .itemLeft(new ItemStack(Material.PAPER))
            .onClick((slot, state) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) return List.of();
                String input = state.getText() == null ? "" : state.getText().trim();
                if (!input.matches("[A-Za-z0-9_-]{2,32}")) {
                    Text.msg(p, "&cTên không hợp lệ. Dùng chữ/số/_/- (2-32).");
                    return List.of(AnvilGUI.ResponseAction.close());
                }
                if (lib.exists(input)) {
                    Text.msg(p, "&cĐã tồn tại đường tên đó.");
                    return List.of(AnvilGUI.ResponseAction.close());
                }
                boolean ok = plugin.getTrackConfig().save(input);
                if (!ok) {
                    Text.msg(p, "&cKhông thể lưu đường.");
                } else {
                    lib.select(input);
                    Text.msg(p, "&aĐã tạo và chọn đường: &f" + input);
                }
                Bukkit.getScheduler().runTask(plugin, () -> open(p));
                return List.of(AnvilGUI.ResponseAction.close());
            })
            .open(p);
    }

    private void promptSaveAs(Player p) {
        new AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.plain(Text.title("Lưu thành")))
            .text(plugin.getTrackLibrary().getCurrent() == null ? "track-name" : plugin.getTrackLibrary().getCurrent())
            .itemLeft(new ItemStack(Material.PAPER))
            .onClick((slot, state) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) return List.of();
                String input = state.getText() == null ? "" : state.getText().trim();
                if (!input.matches("[A-Za-z0-9_-]{2,32}")) {
                    Text.msg(p, "&cTên không hợp lệ. Dùng chữ/số/_/- (2-32).");
                    return List.of(AnvilGUI.ResponseAction.close());
                }
                boolean ok = plugin.getTrackConfig().save(input);
                if (!ok) {
                    Text.msg(p, "&cKhông thể lưu đường.");
                } else {
                    plugin.getTrackLibrary().select(input);
                    Text.msg(p, "&aĐã lưu thành: &f" + input);
                }
                Bukkit.getScheduler().runTask(plugin, () -> open(p));
                return List.of(AnvilGUI.ResponseAction.close());
            })
            .open(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top == null) return;
        String title = Text.plain(e.getView().title());
        boolean inMain = title.equals(Text.plain(TITLE));
        boolean inPick = title.equals(Text.plain(TITLE_PICK));
        if (!inMain && !inPick) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return; Player p = (Player) he;
        if (!hasSetup(p)) { Text.msg(p, "&cBạn không có quyền thực hiện điều đó."); return; }

        ItemStack it = e.getCurrentItem(); if (it == null) return;
        ItemMeta im = it.getItemMeta(); if (im == null) return;
        String actStr = im.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
        if (actStr == null) return;
        Action action; try { action = Action.valueOf(actStr); } catch (Exception ex) { return; }

        switch (action) {
            case PICK_TRACK -> { if (inMain) openPicker(p); else pickTrackFromItem(p, im); }
            case NEW_TRACK -> promptNewTrack(p);
            case SAVE -> doSave(p);
            case SAVE_AS -> promptSaveAs(p);
            case SET_BOUNDS -> doSetBounds(p);
            case SET_WAIT_SPAWN -> doSetWaitSpawn(p);
            case ADD_START -> doAddStart(p);
            case CLEAR_STARTS -> { plugin.getTrackConfig().clearStarts(); Text.msg(p, "&aĐã xóa tất cả start."); open(p);} 
            case SET_FINISH -> doSetFinish(p);
            // SET_PIT removed
            case ADD_CHECKPOINT -> doAddCheckpoint(p);
            case CLEAR_CHECKPOINTS -> { plugin.getTrackConfig().clearCheckpoints(); Text.msg(p, "&aĐã xóa tất cả checkpoint."); open(p);} 
            case ADD_LIGHT -> doAddLight(p);
            case CLEAR_LIGHTS -> { plugin.getTrackConfig().clearLights(); Text.msg(p, "&aĐã xóa tất cả đèn." ); open(p);} 
            case REFRESH -> open(p);
            case BUILD_PATH -> doBuildPath(p);
            case TOGGLE_VIZ -> doToggleViz(p);
            case BACK -> open(p);
            case CLOSE -> p.closeInventory();
        }
    }

    private void pickTrackFromItem(Player p, ItemMeta im) {
        String tname = im.getPersistentDataContainer().get(KEY_TRACK, PersistentDataType.STRING);
        if (tname == null) return;
        if (!lib.exists(tname)) { Text.msg(p, "&cKhông tìm thấy đường: &f" + tname); return; }
        if (!lib.select(tname)) { Text.msg(p, "&cKhông thể tải đường: &f" + tname); return; }
        Text.msg(p, "&aĐã chọn đường: &f" + tname);
        open(p);
    }

    private void doSave(Player p) {
        String cur = lib.getCurrent();
        if (cur == null) { promptSaveAs(p); return; }
        boolean ok = plugin.getTrackConfig().save(cur);
        if (!ok) { Text.msg(p, "&cKhông thể lưu đường."); return; }
        Text.msg(p, "&aĐã lưu &f" + cur);
        open(p);
    }

    private void doAddStart(Player p) {
        org.bukkit.Location raw = p.getLocation();
        org.bukkit.Location loc = es.jaie55.boatracing.track.TrackConfig.normalizeStart(raw);
        plugin.getTrackConfig().addStart(loc);
        Text.msg(p, "&aĐã thêm Start tại &f" + Text.fmtPos(loc) + " &7(yaw=" + Math.round(loc.getYaw()) + ", pitch=0)");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        open(p);
    }

    private void doSetFinish(Player p) {
        SelectionUtils.SelectionDetails sel = SelectionUtils.getSelectionDetailed(p);
        if (sel == null) {
            Text.msg(p, "&cKhông có selection hợp lệ. Dùng wand để chọn 2 góc.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        Region r = new Region(sel.worldName, sel.box);
        plugin.getTrackConfig().setFinish(r);
        Text.msg(p, "&aĐã đặt vùng đích: &f" + Text.fmtArea(r));
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.3f);
        open(p);
    }

    private void doSetBounds(Player p) {
        SelectionUtils.SelectionDetails sel = SelectionUtils.getSelectionDetailed(p);
        if (sel == null) {
            Text.msg(p, "&cKhông có selection hợp lệ. Dùng wand để chọn 2 góc.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        Region r = new Region(sel.worldName, sel.box);
        plugin.getTrackConfig().setBounds(r);
        Text.msg(p, "&aĐã đặt vùng bao (bounds): &f" + Text.fmtArea(r));
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.3f);
        open(p);
    }

    private void doSetWaitSpawn(Player p) {
        org.bukkit.Location raw = p.getLocation();
        // use normalized start format for consistency (snap yaw 45°, pitch 0, x/z to .0 or .5)
        org.bukkit.Location loc = es.jaie55.boatracing.track.TrackConfig.normalizeStart(raw);
        plugin.getTrackConfig().setWaitingSpawn(loc);
        Text.msg(p, "&aĐã đặt spawn chờ tại &f" + Text.fmtPos(loc) + " &7(yaw=" + Math.round(loc.getYaw()) + ", pitch=0)");
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        open(p);
    }

    // Pit mechanic removed

    private void doAddCheckpoint(Player p) {
        SelectionUtils.SelectionDetails sel = SelectionUtils.getSelectionDetailed(p);
        if (sel == null) {
            Text.msg(p, "&cKhông có selection hợp lệ. Dùng wand để chọn 2 góc.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        Region r = new Region(sel.worldName, sel.box);
        plugin.getTrackConfig().addCheckpoint(r);
        Text.msg(p, "&aĐã thêm checkpoint #&f" + plugin.getTrackConfig().getCheckpoints().size() + " &7(" + Text.fmtArea(r) + ")");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        open(p);
    }

    private void doAddLight(Player p) {
        Block target = getTargetBlockLenient(p, 20);
        if (target == null) {
            Text.msg(p, "&cHãy nhìn vào Đèn Redstone trong bán kính 20 block.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        if (target.getType() != org.bukkit.Material.REDSTONE_LAMP) {
            Text.msg(p, "&cBlock đang nhìn không phải Đèn Redstone: &f" + target.getType());
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        boolean ok = plugin.getTrackConfig().addLight(target);
        if (!ok) {
            Text.msg(p, "&cKhông thể thêm đèn. Dùng Đèn Redstone, tránh trùng lặp, tối đa 5.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        Text.msg(p, "&aĐã thêm đèn tại &f" + Text.fmtBlock(target));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        open(p);
    }

    private static Block getTargetBlockLenient(Player p, int range) {
        if (p == null) return null;
        try {
            try {
                Block b = p.getTargetBlockExact(range, org.bukkit.FluidCollisionMode.ALWAYS);
                if (b != null) return b;
            } catch (Throwable ignored) {}

            try {
                Block b = p.getTargetBlockExact(range);
                if (b != null) return b;
            } catch (Throwable ignored) {}

            try {
                org.bukkit.util.RayTraceResult rr = p.rayTraceBlocks((double) range, org.bukkit.FluidCollisionMode.ALWAYS);
                if (rr != null && rr.getHitBlock() != null) return rr.getHitBlock();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    private void doBuildPath(Player p) {
        var cfg = plugin.getTrackConfig();
        if (!cfg.isReady()) {
            Text.msg(p, "&cĐường đua chưa sẵn sàng (cần Start và Finish).");
            return;
        }
        Text.msg(p, "&7Đang xây dựng đường giữa...");
        // Run sync (small corridors). For large tracks, offload to async and schedule block checks on main thread chunk by chunk.
        java.util.List<org.bukkit.Location> nodes = es.jaie55.boatracing.track.CenterlineBuilder.build(cfg, 8, plugin.getLogger(), true);
        if (nodes == null || nodes.isEmpty()) {
            Text.msg(p, "&cKhông thể tìm đường giữa. Hãy đảm bảo đường là băng liền mạch giữa các checkpoint.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        cfg.setCenterline(nodes);
        // save immediately under current name if any
        if (plugin.getTrackLibrary().getCurrent() != null) cfg.save(plugin.getTrackLibrary().getCurrent());
        Text.msg(p, "&aĐã tạo đường giữa với &f" + nodes.size() + " &anút.");
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        open(p);
    }

    private void doToggleViz(Player p) {
        java.util.UUID id = p.getUniqueId();
        Integer taskId = vizTasks.remove(id);
        if (taskId != null) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
            Text.msg(p, "&7Đã tắt hiển thị đường giữa.");
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 0.9f);
            open(p);
            return;
        }
        // Start a new repeating visualizer (updates as the player moves)
        int newId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<org.bukkit.Location> nodes = plugin.getTrackConfig().getCenterline();
            if (nodes.isEmpty()) return;
            if (!p.isOnline()) return;
            org.bukkit.World pw = p.getWorld();
            org.bukkit.Location pl = p.getLocation();
            // Only render inside player's view distance.
            int viewChunks = getClientViewDistanceChunks(p);
            if (viewChunks <= 0) viewChunks = Bukkit.getViewDistance();
            int radiusBlocks = Math.max(16, (viewChunks + 1) * 16);
            double maxDistSq = (double) radiusBlocks * (double) radiusBlocks;
            // Draw a continuous line by interpolating between nodes.
            // This also draws the seam (last -> first) when endpoints are close.
            org.bukkit.Particle.DustOptions dust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(170, 0, 255), 1.2f);

            int n = nodes.size();
            if (n <= 0) return;

            boolean drawSeam = false;
            if (n >= 2) {
                org.bukkit.Location a0 = nodes.get(0);
                org.bukkit.Location aN = nodes.get(n - 1);
                if (a0 != null && aN != null) {
                    org.bukkit.World w0 = (a0.getWorld() != null) ? a0.getWorld() : pw;
                    org.bukkit.World wN = (aN.getWorld() != null) ? aN.getWorld() : pw;
                    if (w0.getName().equals(pw.getName()) && wN.getName().equals(pw.getName())) {
                        double dx = a0.getX() - aN.getX();
                        double dz = a0.getZ() - aN.getZ();
                        drawSeam = (dx * dx + dz * dz) <= (40.0 * 40.0);
                    }
                }
            }

            int segCount = drawSeam ? n : (n - 1);
            double stepSize = 0.5; // blocks
            int maxSamplesPerSeg = 24;

            for (int i = 0; i < segCount; i++) {
                org.bukkit.Location a = nodes.get(i);
                org.bukkit.Location b = nodes.get((i + 1) % n);
                if (a == null || b == null) continue;

                org.bukkit.World aw = (a.getWorld() != null) ? a.getWorld() : pw;
                org.bukkit.World bw = (b.getWorld() != null) ? b.getWorld() : pw;
                // Use name match to be resilient across reloads.
                if (!aw.getName().equals(pw.getName()) || !bw.getName().equals(pw.getName())) continue;

                double dx = b.getX() - a.getX();
                double dz = b.getZ() - a.getZ();
                double dy = b.getY() - a.getY();
                double len = Math.sqrt(dx * dx + dz * dz + dy * dy);
                int samples = (len <= 0.0001) ? 1 : (int) Math.ceil(len / stepSize);
                if (samples > maxSamplesPerSeg) samples = maxSamplesPerSeg;

                for (int s = 0; s <= samples; s++) {
                    double t = (samples <= 0) ? 0.0 : ((double) s / (double) samples);
                    double x = a.getX() + dx * t;
                    double z = a.getZ() + dz * t;
                    double y = (a.getY() + dy * t) + 2.0;

                    double ddx = x - pl.getX();
                    double ddz = z - pl.getZ();
                    if ((ddx * ddx + ddz * ddz) > maxDistSq) continue;
                    pw.spawnParticle(org.bukkit.Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
                }
            }
        }, 0L, 5L).getTaskId();
        vizTasks.put(id, newId);
        Text.msg(p, "&aĐã bật hiển thị đường giữa.");
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
        open(p);
    }

    private static int getClientViewDistanceChunks(Player p) {
        try {
            java.lang.reflect.Method m = p.getClass().getMethod("getClientViewDistance");
            Object v = m.invoke(p);
            if (v instanceof Integer i) return i;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        java.util.UUID id = e.getPlayer().getUniqueId();
        Integer taskId = vizTasks.remove(id);
        if (taskId != null) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView() == null) return;
        String title = Text.plain(e.getView().title());
        if (title.equals(Text.plain(TITLE)) || title.equals(Text.plain(TITLE_PICK))) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // no state for now
    }
}
