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
        if (p == null) return;

        List<String> tracks = new ArrayList<>();
        try {
            if (plugin.getTrackLibrary() != null) tracks.addAll(plugin.getTrackLibrary().list());
        } catch (Throwable ignored) {}

        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, TITLE);

        // Fill with simple panes.
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        int idx = 0;
        for (String name : tracks) {
            if (name == null || name.isBlank()) continue;
            if (idx >= size) break;

            ItemStack it = trackItem(name);
            inv.setItem(idx, it);
            idx++;
        }

        p.openInventory(inv);
        try { p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f); } catch (Throwable ignored) {}
    }

    private ItemStack trackItem(String trackName) {
        Material mat = Material.FILLED_MAP;
        List<String> lore = new ArrayList<>();

        RaceManager rm = null;
        try { rm = plugin.getRaceService().getOrCreate(trackName); } catch (Throwable ignored) { rm = null; }

        boolean ready = false;
        if (rm != null && rm.getTrackConfig() != null) {
            try { ready = rm.getTrackConfig().isReady(); } catch (Throwable ignored) { ready = false; }
        }

        if (rm == null) {
            mat = Material.BARRIER;
            lore.add("&cKhông thể tải đường đua này.");
            lore.add("&7Vui lòng thử lại hoặc kiểm tra file cấu hình.");
        } else if (!ready) {
            mat = Material.RED_CONCRETE;
            lore.add("&cĐường đua chưa sẵn sàng.");
            try {
                List<String> miss = rm.getTrackConfig().missingRequirements();
                if (miss != null && !miss.isEmpty()) lore.add("&7Thiếu: &f" + String.join(", ", miss));
            } catch (Throwable ignored) {}
        } else {
            mat = Material.FILLED_MAP;
            lore.add("&aSẵn sàng để thi đấu");
            lore.add("&7● &fNhấp trái&7: &aTham gia đăng ký");
            lore.add("&7● &fNhấp phải&7: &eXem trạng thái");
        }

        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(Text.item("&e" + trackName));
            im.lore(Text.lore(lore));
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
        if (e.getView() == null) return false;
        String title = Text.plain(e.getView().title());
        return title.equals(Text.plain(TITLE));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!isThis(e)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player p)) return;

        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        String track = im.getPersistentDataContainer().get(KEY_TRACK, PersistentDataType.STRING);
        if (track == null || track.isBlank()) return;

        boolean right = e.getClick() == ClickType.RIGHT;
        if (right) {
            // Use existing status command output.
            try { p.closeInventory(); } catch (Throwable ignored) {}
            try { Bukkit.dispatchCommand(p, "boatracing race status " + track); } catch (Throwable ignored) {}
            return;
        }

        // left click = join
        try { p.closeInventory(); } catch (Throwable ignored) {}
        boolean ok = false;
        try {
            ok = plugin.getRaceService().join(track, p);
        } catch (Throwable ignored) { ok = false; }
        if (!ok) {
            Text.msg(p, "&cKhông thể tham gia đăng ký ngay lúc này.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView() == null) return;
        String title = Text.plain(e.getView().title());
        if (title.equals(Text.plain(TITLE))) e.setCancelled(true);
    }
}
