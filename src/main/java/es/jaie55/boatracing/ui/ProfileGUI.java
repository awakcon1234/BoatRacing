package es.jaie55.boatracing.ui;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.profile.PlayerProfileManager;
import es.jaie55.boatracing.util.Text;
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
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProfileGUI implements Listener {
    private static final Component TITLE = Text.title("Hồ sơ tay đua");
    private static final Component TITLE_COLOR = Text.title("Chọn màu");
    private final BoatRacingPlugin plugin;
    private final NamespacedKey KEY_ACTION;
    private final NamespacedKey KEY_COLOR;

    private enum Action { COLOR, NUMBER, ICON, CLOSE }

    public ProfileGUI(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.KEY_ACTION = new NamespacedKey(plugin, "profile-action");
        this.KEY_COLOR = new NamespacedKey(plugin, "profile-color");
    }

    public void open(Player p) {
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        PlayerProfileManager pm = plugin.getProfileManager();
        var prof = pm.get(p.getUniqueId());

        // Preview item
        inv.setItem(10, previewItem(prof));

        inv.setItem(12, buttonWithLore(Material.LEATHER_CHESTPLATE, Text.item("&b&lMàu"), Action.COLOR,
                List.of("&7Chọn màu đại diện của bạn."), true));
        inv.setItem(14, buttonWithLore(Material.NAME_TAG, Text.item("&a&lSố đua"), Action.NUMBER,
                List.of("&7Nhập số đua (1-99)."), true));
        inv.setItem(16, buttonWithLore(Material.FLOWER_BANNER_PATTERN, Text.item("&d&lBiểu tượng"), Action.ICON,
                List.of("&7Nhập kí tự biểu tượng (Unicode)."), true));

        inv.setItem(26, buttonWithLore(Material.BARRIER, Text.item("&c&lĐóng"), Action.CLOSE,
                List.of("&7Đóng"), true));

        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
    }

    private ItemStack previewItem(PlayerProfileManager.Profile prof) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            String icon = prof.icon == null ? "" : prof.icon;
            String num = prof.number > 0 ? ("#" + prof.number) : "(chưa có số)";
            im.displayName(Text.item("&f&lXem trước"));
            List<String> lore = new ArrayList<>();
            lore.add("&7Màu: &f" + prof.color.name());
            lore.add("&7Số: &f" + num);
            lore.add("&7Biểu tượng: &f" + (icon.isEmpty()?"(trống)":icon));
            im.lore(Text.lore(lore));
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    public void openColorPicker(Player p) {
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_COLOR);
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        int slot = 10;
        for (DyeColor dc : DyeColor.values()) {
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

    private void promptIcon(Player p) {
        new AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.plain(Text.title("Nhập biểu tượng (Unicode)")))
            .text("★")
            .itemLeft(new ItemStack(Material.FLOWER_BANNER_PATTERN))
            .onClick((slot, state) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) return List.of();
                String input = state.getText() == null ? "" : state.getText().trim();
                if (input.isEmpty()) input = "";
                // limit to short icon (1-3 chars)
                if (input.length() > 3) input = input.substring(0, 3);
                plugin.getProfileManager().setIcon(p.getUniqueId(), input);
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
        if (!inMain && !inColor) return;
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
            case ICON -> { p.closeInventory(); promptIcon(p); }
            case CLOSE -> p.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView() == null) return;
        String title = Text.plain(e.getView().title());
        if (title.equals(Text.plain(TITLE)) || title.equals(Text.plain(TITLE_COLOR))) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // no-op
    }
}
