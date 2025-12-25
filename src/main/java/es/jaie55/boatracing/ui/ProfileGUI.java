package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.profile.PlayerProfileManager;
import dev.belikhun.boatracing.util.Text;
import net.kyori.adventure.text.Component;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class ProfileGUI implements Listener {
    private static final Component TITLE = Text.title("Hồ sơ tay đua");
    private static final Component TITLE_COLOR = Text.title("Chọn màu");
    private static final Component TITLE_ICON = Text.title("Chọn biểu tượng");
    private final BoatRacingPlugin plugin;
    private final NamespacedKey KEY_ACTION;
    private final NamespacedKey KEY_COLOR;
    private final NamespacedKey KEY_BOAT;
    private final NamespacedKey KEY_ICON;

    private enum Action { COLOR, NUMBER, ICON, BOAT, SPEEDUNIT, CLOSE }

    public ProfileGUI(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.KEY_ACTION = new NamespacedKey(plugin, "profile-action");
        this.KEY_COLOR = new NamespacedKey(plugin, "profile-color");
        this.KEY_BOAT = new NamespacedKey(plugin, "profile-boat");
        this.KEY_ICON = new NamespacedKey(plugin, "profile-icon");
    }

    public void open(Player p) {
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        PlayerProfileManager pm = plugin.getProfileManager();
        var prof = pm.get(p.getUniqueId());

        // Preview item
        inv.setItem(10, previewItem(p, prof));

        inv.setItem(12, buttonWithLore(dyeFor(prof.color), Text.item("&b&lMàu"), Action.COLOR,
                List.of("&7Chọn màu đại diện của bạn."), true));
        String u = (prof.speedUnit==null || prof.speedUnit.isEmpty()) ? plugin.getConfig().getString("scoreboard.speed.unit","kmh") : prof.speedUnit;
        String label = "kmh".equalsIgnoreCase(u)?"km/h": ("bps".equalsIgnoreCase(u)?"bps":"bph");
        inv.setItem(11, buttonWithLore(Material.COMPASS, Text.item("&e&lĐơn vị tốc độ"), Action.SPEEDUNIT,
            List.of("&7Hiện tại: &f" + label, "&eBấm: &fLuân phiên km/h → bps → bph"), true));
        inv.setItem(14, buttonWithLore(Material.NAME_TAG, Text.item("&a&lSố đua"), Action.NUMBER,
                List.of("&7Nhập số đua (1-99)."), true));
        inv.setItem(15, buttonWithLore(boatMatFor(prof.boatType), Text.item("&b&lThuyền"), Action.BOAT,
            List.of("&7Chọn loại thuyền của bạn."), true));
        inv.setItem(16, buttonWithLore(Material.FLOWER_BANNER_PATTERN, Text.item("&d&lBiểu tượng"), Action.ICON,
            List.of("&7Chọn 1 biểu tượng từ danh sách."), true));

        inv.setItem(26, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE,
                List.of("&7Đóng"), true));

        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
    }

    private ItemStack previewItem(Player p, PlayerProfileManager.Profile prof) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            if (im instanceof SkullMeta sm) {
                sm.setOwningPlayer(p);
                im = sm;
            }
            String icon = prof.icon == null ? "" : prof.icon;
            String num = prof.number > 0 ? ("#" + prof.number) : "(chưa có số)";
            String boat = (prof.boatType==null || prof.boatType.isEmpty()) ? "(mặc định)" : prettyMat(prof.boatType);
            im.displayName(Text.item("&f&lXem trước"));
            List<String> lore = new ArrayList<>();
            lore.add("&7Màu: &f" + prof.color.name());
            lore.add("&7Số: &f" + num);
            lore.add("&7Biểu tượng: &f" + (icon.isEmpty()?"(trống)":icon));
            lore.add("&7Thuyền: &f" + boat);
            String u = (prof.speedUnit==null || prof.speedUnit.isEmpty()) ? "(theo cấu hình)" : (prof.speedUnit.equalsIgnoreCase("kmh")?"km/h": (prof.speedUnit.equalsIgnoreCase("bps")?"bps":"bph"));
            lore.add("&7Đơn vị tốc độ: &f" + u);
            im.lore(Text.lore(lore));
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    private static Material dyeFor(DyeColor color) {
        if (color == null) return Material.WHITE_DYE;
        return switch (color) {
            case WHITE -> Material.WHITE_DYE;
            case BLACK -> Material.BLACK_DYE;
            case RED -> Material.RED_DYE;
            case BLUE -> Material.BLUE_DYE;
            case GREEN -> Material.GREEN_DYE;
            case YELLOW -> Material.YELLOW_DYE;
            case ORANGE -> Material.ORANGE_DYE;
            case PURPLE -> Material.PURPLE_DYE;
            case PINK -> Material.PINK_DYE;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_DYE;
            default -> Material.WHITE_DYE;
        };
    }

    private static Material boatMatFor(String boatType) {
        if (boatType == null || boatType.isEmpty()) return Material.OAK_BOAT;
        try {
            Material m = Material.valueOf(boatType);
            // Basic safety: only allow boat/raft-ish materials to show here.
            String n = m.name();
            if (n.endsWith("_BOAT") || n.endsWith("_CHEST_BOAT") || n.endsWith("_RAFT") || n.endsWith("_CHEST_RAFT")) return m;
        } catch (Exception ignored) {}
        return Material.OAK_BOAT;
    }

    // Limit colors to a curated set
    private static final DyeColor[] ALLOWED_COLORS = new DyeColor[] {
        DyeColor.WHITE, DyeColor.BLACK, DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN,
        DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.PURPLE, DyeColor.PINK, DyeColor.LIGHT_BLUE
    };

    public void openColorPicker(Player p) {
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_COLOR);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        int slot = 10;
        for (DyeColor dc : ALLOWED_COLORS) {
            Material mat = paneForColor(dc);
            ItemStack it = new ItemStack(mat);
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.displayName(Text.item("&f" + dc.name()));
                im.lore(Text.lore(List.of("&7Bấm: &fChọn màu này")));
                im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, Action.COLOR.name());
                im.getPersistentDataContainer().set(KEY_COLOR, PersistentDataType.STRING, dc.name());
                im.addItemFlags(ItemFlag.values());
                it.setItemMeta(im);
            }
            inv.setItem(slot, it);
            slot++;
            if ((slot + 1) % 9 == 0) slot += 2; // spacing
            if (slot >= size - 9) break;
        }
        inv.setItem(size-1, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
    }

    // Predefined icon set
    private static final String[] ALLOWED_ICONS = new String[] {
        "★","☆","✦","✧","❖","◆","◇","❤","✚","⚡","☀","☂","☕","⚓","♪","♫","🚤","⛵"
    };

    // Allowed boats list resolved dynamically
    private static final org.bukkit.Material[] ALLOWED_BOATS = resolveAllowedBoats();

    private static org.bukkit.Material[] resolveAllowedBoats() {
        java.util.List<org.bukkit.Material> list = new java.util.ArrayList<>();
        addIfPresent(list, "OAK_BOAT");
        addIfPresent(list, "SPRUCE_BOAT");
        addIfPresent(list, "BIRCH_BOAT");
        addIfPresent(list, "JUNGLE_BOAT");
        addIfPresent(list, "ACACIA_BOAT");
        addIfPresent(list, "DARK_OAK_BOAT");
        addIfPresent(list, "MANGROVE_BOAT");
        addIfPresent(list, "CHERRY_BOAT");
        addIfPresent(list, "PALE_OAK_BOAT");
        addIfPresent(list, "BAMBOO_RAFT");
        addIfPresent(list, "OAK_CHEST_BOAT");
        addIfPresent(list, "SPRUCE_CHEST_BOAT");
        addIfPresent(list, "BIRCH_CHEST_BOAT");
        addIfPresent(list, "JUNGLE_CHEST_BOAT");
        addIfPresent(list, "ACACIA_CHEST_BOAT");
        addIfPresent(list, "DARK_OAK_CHEST_BOAT");
        addIfPresent(list, "MANGROVE_CHEST_BOAT");
        addIfPresent(list, "CHERRY_CHEST_BOAT");
        addIfPresent(list, "PALE_OAK_CHEST_BOAT");
        addIfPresent(list, "BAMBOO_CHEST_RAFT");
        return list.toArray(new org.bukkit.Material[0]);
    }

    private static void addIfPresent(java.util.List<org.bukkit.Material> out, String name) {
        org.bukkit.Material m = org.bukkit.Material.matchMaterial(name);
        if (m != null) out.add(m);
    }

    public void openIconPicker(Player p) {
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_ICON);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);
        int slot = 10;
        for (String ic : ALLOWED_ICONS) {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.displayName(Text.item("&f" + ic));
                im.lore(Text.lore(List.of("&7Bấm: &fChọn biểu tượng này")));
                im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, Action.ICON.name());
                im.getPersistentDataContainer().set(KEY_ICON, PersistentDataType.STRING, ic);
                im.addItemFlags(ItemFlag.values());
                it.setItemMeta(im);
            }
            inv.setItem(slot, it);
            slot++;
            if ((slot + 1) % 9 == 0) slot += 2;
            if (slot >= size - 9) break;
        }
        inv.setItem(size-1, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
    }

    public void openBoatPicker(Player p) {
        int rows = ((ALLOWED_BOATS.length - 1) / 9) + 2;
        int size = Math.min(54, Math.max(18, rows * 9));
        Inventory inv = Bukkit.createInventory(null, size, Text.title("Chọn thuyền"));
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);
        int slot = 0;
        for (org.bukkit.Material m : ALLOWED_BOATS) {
            if (slot >= size - 9) break;
            ItemStack it = new ItemStack(m);
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.displayName(Text.item("&f" + prettyMat(m.name())));
                im.lore(Text.lore(List.of("&7Bấm: &fChọn")));
                im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, Action.BOAT.name());
                im.getPersistentDataContainer().set(KEY_BOAT, PersistentDataType.STRING, m.name());
                im.addItemFlags(ItemFlag.values());
                it.setItemMeta(im);
            }
            inv.setItem(slot++, it);
        }
        inv.setItem(size-1, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE, List.of("&7Đóng"), true));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
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

    private static Material paneForColor(DyeColor color) {
        try { return Material.valueOf(color.name() + "_STAINED_GLASS_PANE"); } catch (IllegalArgumentException ex) { return Material.WHITE_STAINED_GLASS_PANE; }
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

    private void promptNumber(Player p) {
        new AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.plain(Text.title("Nhập số đua (1-99)")))
            .text("7")
            .itemLeft(new ItemStack(Material.NAME_TAG))
            .onClick((slot, state) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) return List.of();
                String input = state.getText() == null ? "" : state.getText().trim();
                if (!input.matches("\\d{1,2}")) {
                    Text.msg(p, "&cSố không hợp lệ. Chỉ 1-2 chữ số.");
                    return List.of(AnvilGUI.ResponseAction.close());
                }
                int n = Integer.parseInt(input);
                if (n < 1 || n > 99) {
                    Text.msg(p, "&cSố phải 1-99.");
                    return List.of(AnvilGUI.ResponseAction.close());
                }
                plugin.getProfileManager().setNumber(p.getUniqueId(), n);
                Bukkit.getScheduler().runTask(plugin, () -> open(p));
                return List.of(AnvilGUI.ResponseAction.close());
            })
            .open(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory() == null) return;
        String title = Text.plain(e.getView().title());
        boolean inMain = title.equals(Text.plain(TITLE));
        boolean inColor = title.equals(Text.plain(TITLE_COLOR));
        boolean inIcon = title.equals(Text.plain(TITLE_ICON));
        boolean inBoat = title.equals(Text.plain(Text.title("Chọn thuyền")));
        if (!inMain && !inColor && !inIcon && !inBoat) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player)) return;
        Player p = (Player) who;

        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        String actStr = im.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
        if (actStr == null) return;

        Action action;
        try { action = Action.valueOf(actStr); } catch (IllegalArgumentException ex) { return; }
        switch (action) {
            case COLOR -> {
                if (inMain) { openColorPicker(p); return; }
                String colorName = im.getPersistentDataContainer().get(KEY_COLOR, PersistentDataType.STRING);
                if (colorName != null) {
                    try { plugin.getProfileManager().setColor(p.getUniqueId(), DyeColor.valueOf(colorName)); } catch (Exception ignored) {}
                }
                open(p);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            }
            case NUMBER -> { p.closeInventory(); promptNumber(p); }
            case ICON -> {
                if (inMain) { openIconPicker(p); return; }
                String ic = im.getPersistentDataContainer().get(KEY_ICON, PersistentDataType.STRING);
                if (ic != null) plugin.getProfileManager().setIcon(p.getUniqueId(), ic);
                open(p);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            }
            case BOAT -> {
                if (inMain) { openBoatPicker(p); return; }
                String bt = im.getPersistentDataContainer().get(KEY_BOAT, PersistentDataType.STRING);
                if (bt != null) plugin.getProfileManager().setBoatType(p.getUniqueId(), bt);
                open(p);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            }
            case SPEEDUNIT -> {
                // Cycle kmh -> bps -> bph -> kmh (empty means inherit; we store explicit value)
                var pm = plugin.getProfileManager();
                String cur = pm.get(p.getUniqueId()).speedUnit;
                String next;
                if (cur==null || cur.isEmpty()) next = "kmh";
                else if (cur.equalsIgnoreCase("kmh")) next = "bps";
                else if (cur.equalsIgnoreCase("bps")) next = "bph";
                else next = "kmh";
                pm.setSpeedUnit(p.getUniqueId(), next);
                open(p);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            }
            case CLOSE -> p.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView() == null) return;
        String title = Text.plain(e.getView().title());
        if (title.equals(Text.plain(TITLE)) || title.equals(Text.plain(TITLE_COLOR)) || title.equals(Text.plain(TITLE_ICON)) || title.equals(Text.plain(Text.title("Chọn thuyền")))) {
            e.setCancelled(true);
        }
    }

    private static String prettyMat(String name) {
        if (name == null || name.isEmpty()) return "";
        String s = name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // no-op
    }
}

