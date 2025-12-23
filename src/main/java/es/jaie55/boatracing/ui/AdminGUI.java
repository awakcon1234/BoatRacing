package es.jaie55.boatracing.ui;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.util.Text;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Simple Admin GUI: provides entry points to other management screens.
 */
public class AdminGUI implements Listener {
    private static final Component TITLE = Text.title("Quản trị");
    private static final Component TITLE_RACE_HELP = Text.title("Điều khiển cuộc đua");
    private final BoatRacingPlugin plugin;
    private final NamespacedKey KEY_ACTION;

    private enum Action {
        TRACKS,
        RACE,
        SETUP_WIZARD,
        RELOAD,
        CLOSE
    }

    public AdminGUI(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.KEY_ACTION = new NamespacedKey(plugin, "admin-action");
    }

    public void openMain(Player p) {
        int size = 27; // 3 rows
        Inventory inv = Bukkit.createInventory(null, size, TITLE);

        // Fill background with gray panes
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        boolean canSetup = p.hasPermission("boatracing.setup");
        boolean canRaceAdmin = p.hasPermission("boatracing.race.admin") || canSetup;

        // Controls grid with helpful lore and permission hints
        // Teams removed

        inv.setItem(12, buttonWithLore(
            Material.MAP,
            Text.item("&a&lĐường đua"),
            Action.TRACKS,
            java.util.Arrays.asList(
                "&7Quản lý thư viện đường đua.",
                "&7Tạo, chọn, xoá đường đua.",
                canSetup ? " " : " ",
                canSetup ? "&eBấm: &fMở giao diện đường đua" : "&cCần quyền: &fboatracing.setup"
            ),
            canSetup
        ));

        inv.setItem(14, buttonWithLore(
            Material.REDSTONE_TORCH,
            Text.item("&6&lCuộc đua"),
            Action.RACE,
            java.util.Arrays.asList(
                "&7Điều khiển đăng ký và bắt đầu cuộc đua.",
                canRaceAdmin ? " " : " ",
                canRaceAdmin ? "&eBấm: &fXem lệnh nhanh" : "&cCần quyền: &fboatracing.race.admin"
            ),
            canRaceAdmin
        ));

        inv.setItem(16, buttonWithLore(
            Material.BOOK,
            Text.item("&d&lTrợ lý thiết lập"),
            Action.SETUP_WIZARD,
            java.util.Arrays.asList(
                "&7Hỗ trợ thiết lập đường đua theo từng bước.",
                canSetup ? " " : " ",
                canSetup ? "&eBấm: &fKhởi chạy trợ lý thiết lập" : "&cCần quyền: &fboatracing.setup"
            ),
            canSetup
        ));

        inv.setItem(22, buttonWithLore(
            Material.REPEATER,
            Text.item("&e&lTải lại config"),
            Action.RELOAD,
            java.util.Arrays.asList(
                "&7Tải lại cấu hình plugin từ file.",
                " ",
                "&eBấm: &fThực hiện /boatracing reload"
            ),
            true
        ));
        inv.setItem(26, buttonWithLore(
            Material.BARRIER,
            Text.item("&c&lĐóng"),
            Action.CLOSE,
            java.util.Arrays.asList(
                "&7Đóng bảng quản trị."
            ),
            true
        ));

        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
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

    private ItemStack button(Material mat, Component name, Action action) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(name);
            im.addItemFlags(ItemFlag.values());
            PersistentDataContainer pdc = im.getPersistentDataContainer();
            pdc.set(KEY_ACTION, PersistentDataType.STRING, action.name());
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack buttonWithLore(Material mat, Component name, Action action, java.util.List<String> lore, boolean enabled) {
        ItemStack it = new ItemStack(enabled ? mat : Material.RED_STAINED_GLASS_PANE);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(name);
            if (lore != null && !lore.isEmpty()) im.lore(Text.lore(lore));
            im.addItemFlags(ItemFlag.values());
            // Always store action; permission re-checked on click
            im.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action.name());
            it.setItemMeta(im);
        }
        return it;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top == null) return;
        String title = Text.plain(e.getView().title());
        boolean inMain = title.equals(Text.plain(TITLE));
        boolean inRaceHelp = title.equals(Text.plain(TITLE_RACE_HELP));
        if (!inMain && !inRaceHelp) return;

        // Prevent item movement while our GUI is open
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;

        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;

        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        String actStr = im.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
        if (actStr == null) return;

        Action action;
        try { action = Action.valueOf(actStr); } catch (IllegalArgumentException ex) { return; }

        switch (action) {
            // Teams removed
            case TRACKS -> {
                if (!p.hasPermission("boatracing.setup")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                plugin.getTracksGUI().open(p);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.25f);
            }
            case RACE -> {
                // Open the dedicated Admin Race GUI
                if (!p.hasPermission("boatracing.race.admin") && !p.hasPermission("boatracing.setup")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                plugin.getAdminRaceGUI().open(p);
            }
            case SETUP_WIZARD -> {
                if (!p.hasPermission("boatracing.setup")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                p.closeInventory();
                plugin.getServer().dispatchCommand(p, "boatracing setup wizard");
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            }
            case RELOAD -> {
                p.closeInventory();
                plugin.getServer().dispatchCommand(p, "boatracing reload");
            }
            case CLOSE -> {
                p.closeInventory();
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 0.9f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView() == null) return;
        String title = Text.plain(e.getView().title());
        if (title.equals(Text.plain(TITLE)) || title.equals(Text.plain(TITLE_RACE_HELP))) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // No state to track for now
    }
}
