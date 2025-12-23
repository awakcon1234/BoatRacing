package es.jaie55.boatracing.track;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selection manager: keeps per-player corner selections and provides
 * helper methods used by SelectionUtils and commands.
 */
public final class SelectionManager {
    private SelectionManager() {}

    private static final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public static void init(Plugin plugin) {
        // no-op for now; reserved for lifecycle work
    }

    public static void giveWand(org.bukkit.entity.Player p) {
        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta m = stick.getItemMeta();
        if (m != null) {
            m.setDisplayName("§6BoatRacing Selector");
            stick.setItemMeta(m);
        }
        p.getInventory().addItem(stick);
    }

    public static void setCornerA(org.bukkit.entity.Player p, Location loc) {
        selections.computeIfAbsent(p.getUniqueId(), k -> new Selection()).a = loc.clone();
    }

    public static void setCornerB(org.bukkit.entity.Player p, Location loc) {
        selections.computeIfAbsent(p.getUniqueId(), k -> new Selection()).b = loc.clone();
    }

    public static Selection getSelection(org.bukkit.entity.Player p) {
        return selections.get(p.getUniqueId());
    }

    public static final class Selection {
        public volatile Location a;
        public volatile Location b;
    }
}
