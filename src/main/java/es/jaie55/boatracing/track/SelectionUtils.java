package dev.belikhun.boatracing.track;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Selection helpers implemented using SelectionManager backing storage.
 */
public final class SelectionUtils {
    private SelectionUtils() {}

    public static class SelectionDetails {
        public final String worldName;
        public final BoundingBox box;
        public SelectionDetails(String worldName, BoundingBox box) {
            this.worldName = worldName;
            this.box = box;
        }
    }

    public static SelectionDetails getSelectionDetailed(Player p) {
        SelectionManager.Selection s = SelectionManager.getSelection(p);
        if (s == null || s.a == null || s.b == null) return null;
        Location a = s.a;
        Location b = s.b;
        if (a.getWorld() == null || b.getWorld() == null) return null;
        if (!a.getWorld().getName().equals(b.getWorld().getName())) return null;
        BoundingBox box = BoundingBox.of(a, b);
        return new SelectionDetails(a.getWorld().getName(), box);
    }

    public static List<String> debugSelection(Player p) {
        List<String> lines = new ArrayList<>();
        SelectionManager.Selection s = SelectionManager.getSelection(p);
        if (s == null) { lines.add("no selection"); return lines; }
        lines.add("Corner A: " + (s.a == null ? "unset" : dev.belikhun.boatracing.util.Text.fmtPos(s.a)));
        lines.add("Corner B: " + (s.b == null ? "unset" : dev.belikhun.boatracing.util.Text.fmtPos(s.b)));
        return lines;
    }
}

