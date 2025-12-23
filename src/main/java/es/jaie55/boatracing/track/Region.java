package es.jaie55.boatracing.track;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

/**
 * Placeholder region type.
 */
public class Region {
    private final String worldName;
    private final BoundingBox box;

    public Region(String worldName, BoundingBox box) {
        this.worldName = worldName;
        this.box = box;
    }

    public String getWorldName() { return worldName; }
    public BoundingBox getBox() { return box; }

    public boolean contains(Location loc) {
        World w = loc.getWorld();
        if (w == null || worldName == null) return false;
        if (!w.getName().equals(worldName)) return false;
        return box != null && box.contains(loc.getX(), loc.getY(), loc.getZ());
    }
}
