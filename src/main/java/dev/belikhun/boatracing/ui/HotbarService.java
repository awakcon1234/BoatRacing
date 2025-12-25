package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.race.RaceService;
import dev.belikhun.boatracing.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.UUID;

public class HotbarService {
    private final BoatRacingPlugin plugin;
    private final RaceService raceService;

    public final NamespacedKey KEY_MARK;
    public final NamespacedKey KEY_ACTION;

    private int taskId = -1;

    public enum Action {
        QUICK_JOIN,
        MAP_SELECT,
        PROFILE,
        ADMIN_PANEL,
        LEAVE_TO_LOBBY,
        FORCE_START,
        RESPAWN_CHECKPOINT
    }

    public enum State {
        LOBBY,
        WAITING,
        COUNTDOWN,
        RACING,
        COMPLETED
    }

    public HotbarService(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.raceService = plugin.getRaceService();
        this.KEY_MARK = new NamespacedKey(plugin, "hotbar_item");
        this.KEY_ACTION = new NamespacedKey(plugin, "hotbar_action");
    }

    public void start() {
        if (taskId != -1) return;
        // Keep it relatively low-impact; we only update slots when needed.
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 10L).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
            taskId = -1;
        }

        // Remove hotbar items from all online players.
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { clearHotbarItems(p); } catch (Throwable ignored) {}
        }
    }

    public void clearHotbarItems(Player p) {
        if (p == null) return;
        try {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack it = p.getInventory().getItem(slot);
                if (isHotbarItem(it)) p.getInventory().setItem(slot, null);
            }
            ItemStack off = p.getInventory().getItemInOffHand();
            if (isHotbarItem(off)) p.getInventory().setItemInOffHand(null);
        } catch (Throwable ignored) {}
    }

    public boolean isHotbarItem(ItemStack it) {
        if (it == null) return false;
        ItemMeta im = it.getItemMeta();
        if (im == null) return false;
        try {
            Byte b = im.getPersistentDataContainer().get(KEY_MARK, PersistentDataType.BYTE);
            return b != null && b == (byte) 1;
        } catch (Throwable ignored) {}
        return false;
    }

    public Action getAction(ItemStack it) {
        if (it == null) return null;
        ItemMeta im = it.getItemMeta();
        if (im == null) return null;
        String a = null;
        try { a = im.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING); }
        catch (Throwable ignored) { a = null; }
        if (a == null) return null;
        try { return Action.valueOf(a); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public void runAction(Player p, Action a, boolean rightClick) {
        if (p == null || a == null) return;

        switch (a) {
            case PROFILE -> {
                try { plugin.getProfileGUI().open(p); } catch (Throwable ignored) {}
            }
            case ADMIN_PANEL -> {
                if (!p.hasPermission("boatracing.admin")) {
                    Text.msg(p, "&cBạn không có quyền sử dụng chức năng này.");
                    return;
                }
                try { plugin.getAdminGUI().openMain(p); } catch (Throwable ignored) {}
            }
            case MAP_SELECT -> {
                try { plugin.getTrackSelectGUI().open(p); } catch (Throwable ignored) {}
            }
            case QUICK_JOIN -> {
                // If there is exactly 1 ready track, join it; otherwise open selection.
                try {
                    var lib = plugin.getTrackLibrary();
                    if (lib == null) {
                        Text.msg(p, "&cHiện chưa có đường đua nào.");
                        return;
                    }
                    java.util.List<String> names = new java.util.ArrayList<>(lib.list());
                    java.util.List<String> ready = new java.util.ArrayList<>();
                    for (String n : names) {
                        if (n == null || n.isBlank()) continue;
                        RaceManager rm = raceService.getOrCreate(n);
                        if (rm != null && rm.getTrackConfig() != null && rm.getTrackConfig().isReady()) {
                            ready.add(n);
                        }
                    }
                    if (ready.size() == 1) {
                        String t = ready.get(0);
                        if (!raceService.join(t, p)) {
                            Text.msg(p, "&cKhông thể tham gia đăng ký ngay lúc này.");
                        }
                    } else {
                        plugin.getTrackSelectGUI().open(p);
                    }
                } catch (Throwable ignored) {}
            }
            case LEAVE_TO_LOBBY -> {
                try {
                    boolean ok = plugin.getRaceService().leaveToLobby(p);
                    if (!ok) {
                        // Still ensure they're in a clean state.
                        try {
                            if (p.getWorld() != null) p.teleport(p.getWorld().getSpawnLocation());
                        } catch (Throwable ignored2) {}
                    }
                } catch (Throwable ignored) {}
            }
            case FORCE_START -> {
                if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                    Text.msg(p, "&cBạn không có quyền sử dụng chức năng này.");
                    return;
                }
                try {
                    RaceManager rm = raceService.findRaceFor(p.getUniqueId());
                    if (rm == null) return;
                    rm.forceStart();
                } catch (Throwable ignored) {}
            }
            case RESPAWN_CHECKPOINT -> {
                try {
                    RaceManager rm = raceService.findRaceFor(p.getUniqueId());
                    if (rm == null) return;
                    rm.manualRespawnAtCheckpoint(p);
                } catch (Throwable ignored) {}
            }
        }

        try {
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
        } catch (Throwable ignored) {}
    }

    private void tick() {
        // If RaceService hasn't initialized yet, do nothing.
        if (raceService == null) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                State state = resolveState(p.getUniqueId());
                applyFor(p, state);
            } catch (Throwable ignored) {}
        }
    }

    private State resolveState(UUID id) {
        RaceManager rm = raceService.findRaceFor(id);
        if (rm == null) return State.LOBBY;

        if (rm.isRegistering()) return State.WAITING;

        if (!rm.isRunning() && rm.isCountdownActiveFor(id)) return State.COUNTDOWN;

        if (rm.isRunning()) return State.RACING;

        // If race isn't running/registering but there are results, treat as completed.
        try {
            var st = rm.getParticipantState(id);
            if (st != null && st.finished) return State.COMPLETED;
            boolean anyFinished = rm.getStandings().stream().anyMatch(s -> s != null && s.finished);
            if (anyFinished) return State.COMPLETED;
        } catch (Throwable ignored) {}

        return State.LOBBY;
    }

    private void applyFor(Player p, State st) {
        if (p == null) return;

        // Slots mapping (simple): left=0,1 | middle=4 | right=7,8
        // Keep it minimal: we only touch these slots.

        // Clear / set per-state.
        switch (st) {
            case LOBBY -> {
                setSlot(p, 0, item(Material.FEATHER, "&a&l⚡ Tham gia nhanh", Action.QUICK_JOIN,
                    "&7Tự động vào đường đua nếu chỉ có &f1&7 đường sẵn sàng.",
                    "&7Nếu có nhiều đường, sẽ mở &emenu chọn đường&7."));
                setSlot(p, 1, item(Material.FILLED_MAP, "&e&l🗺 Chọn đường đua", Action.MAP_SELECT,
                    "&7Mở danh sách đường đua để tham gia."));

                setSlot(p, 4, null);

                setSlot(p, 7, playerHeadItem(p, "&b&l👤 Hồ sơ tay đua", Action.PROFILE,
                    "&7Tùy chỉnh màu, số đua, biểu tượng và thuyền."));

                if (p.hasPermission("boatracing.admin")) {
                    setSlot(p, 8, item(Material.REDSTONE, "&c&l🛠 Bảng quản trị", Action.ADMIN_PANEL,
                        "&7Mở menu quản trị."));
                } else {
                    setSlot(p, 8, null);
                }
            }
            case WAITING -> {
                setSlot(p, 0, playerHeadItem(p, "&b&l👤 Hồ sơ tay đua", Action.PROFILE,
                    "&7Tùy chỉnh hồ sơ tay đua."));
                setSlot(p, 1, null);

                if (p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup")) {
                    setSlot(p, 4, item(Material.LIME_CONCRETE, "&e&l▶ Bắt đầu ngay", Action.FORCE_START,
                            "&7Bắt đầu ngay với những người đã đăng ký."));
                } else {
                    setSlot(p, 4, null);
                }

                setSlot(p, 7, null);
                setSlot(p, 8, item(Material.BARRIER, "&c&l⎋ Rời cuộc đua", Action.LEAVE_TO_LOBBY,
                    "&7Rời cuộc đua và quay về sảnh."));
            }
            case COUNTDOWN, RACING -> {
                // Keep only respawn on the right.
                setSlot(p, 0, null);
                setSlot(p, 1, null);
                setSlot(p, 4, null);
                setSlot(p, 7, null);
                setSlot(p, 8, item(Material.ENDER_PEARL, "&b&l⟲ Về checkpoint", Action.RESPAWN_CHECKPOINT,
                    "&7Dịch chuyển về &fcheckpoint gần nhất&7."));
            }
            case COMPLETED -> {
                setSlot(p, 0, null);
                setSlot(p, 1, null);
                setSlot(p, 4, null);
                setSlot(p, 7, null);
                setSlot(p, 8, item(Material.OAK_DOOR, "&a&l⏪ Về sảnh", Action.LEAVE_TO_LOBBY,
                    "&7Rời cuộc đua và quay về sảnh."));
            }
        }
    }

    private void setSlot(Player p, int slot, ItemStack desired) {
        if (slot < 0 || slot > 8) return;

        ItemStack cur = null;
        try { cur = p.getInventory().getItem(slot); } catch (Throwable ignored) { cur = null; }

        // If desired is null: only clear if current is one of our items.
        if (desired == null) {
            if (isHotbarItem(cur)) {
                p.getInventory().setItem(slot, null);
            }
            return;
        }

        // If current already matches our desired action, keep it.
        Action curAct = getAction(cur);
        Action wantAct = getAction(desired);
        if (curAct != null && wantAct != null && curAct == wantAct) {
            return;
        }

        // Overwrite slot.
        p.getInventory().setItem(slot, desired);
    }

    private ItemStack item(Material mat, String name, Action action, String... loreLines) {
        if (mat == null) mat = Material.PAPER;
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(Text.item(name));
            if (loreLines != null && loreLines.length > 0) {
                java.util.List<String> lore = new java.util.ArrayList<>();
                for (String s : loreLines) {
                    if (s == null) continue;
                    lore.add(s);
                }
                im.lore(Text.lore(lore));
            }
            im.addItemFlags(ItemFlag.values());

            im.getPersistentDataContainer().set(KEY_MARK, PersistentDataType.BYTE, (byte) 1);
            im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action.name());
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack playerHeadItem(Player owner, String name, Action action, String... loreLines) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta im = it.getItemMeta();
        if (im instanceof SkullMeta sm) {
            try { sm.setOwningPlayer(owner); } catch (Throwable ignored) {}
            im = sm;
        }
        if (im != null) {
            im.displayName(Text.item(name));
            if (loreLines != null && loreLines.length > 0) {
                java.util.List<String> lore = new java.util.ArrayList<>();
                for (String s : loreLines) {
                    if (s == null) continue;
                    lore.add(s);
                }
                im.lore(Text.lore(lore));
            }
            im.addItemFlags(ItemFlag.values());
            im.getPersistentDataContainer().set(KEY_MARK, PersistentDataType.BYTE, (byte) 1);
            im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action.name());
            it.setItemMeta(im);
        }
        return it;
    }
}
