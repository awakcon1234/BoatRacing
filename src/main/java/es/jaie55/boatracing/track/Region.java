package dev.belikhun.boatracing.track;

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

    public boolean containsXZ(Location loc) {
        World w = loc.getWorld();
        if (w == null || worldName == null) return false;
        if (!w.getName().equals(worldName)) return false;
        if (box == null) return false;

        // Treat regions as *block selections* in X/Z:
        // - min/max in config typically come from clicked block coords
        // - a single-line selection (minZ == maxZ) should still cover one block of thickness
        // Use half-open upper bounds with +1 to include the full block volume.
        double minX = Math.min(box.getMinX(), box.getMaxX());
        double maxX = Math.max(box.getMinX(), box.getMaxX()) + 1.0;
        double minZ = Math.min(box.getMinZ(), box.getMaxZ());
        double maxZ = Math.max(box.getMinZ(), box.getMaxZ()) + 1.0;

        double x = loc.getX();
        double z = loc.getZ();
        return x >= minX && x < maxX
                && z >= minZ && z < maxZ;
    }

    /**
     * Swept test in X/Z to avoid missing thin checkpoints at high speed.
     * Uses the same block-selection semantics as {@link #containsXZ(Location)}.
     */
    public boolean intersectsXZ(Location from, Location to) {
        World wf = from.getWorld();
        World wt = to.getWorld();
        if (wf == null || wt == null || worldName == null) return false;
        if (!wf.equals(wt)) return false;
        if (!wf.getName().equals(worldName)) return false;
        if (box == null) return false;

        double minX = Math.min(box.getMinX(), box.getMaxX());
        double maxX = Math.max(box.getMinX(), box.getMaxX()) + 1.0;
        double minZ = Math.min(box.getMinZ(), box.getMaxZ());
        double maxZ = Math.max(box.getMinZ(), box.getMaxZ()) + 1.0;

        double x1 = from.getX(), z1 = from.getZ();
        double x2 = to.getX(), z2 = to.getZ();

        // Fast path: either endpoint is inside.
        if (x1 >= minX && x1 < maxX && z1 >= minZ && z1 < maxZ) return true;
        if (x2 >= minX && x2 < maxX && z2 >= minZ && z2 < maxZ) return true;

        // Slab intersection (AABB vs segment) in 2D.
        double dx = x2 - x1;
        double dz = z2 - z1;
        double t0 = 0.0;
        double t1 = 1.0;

        if (dx == 0.0) {
            if (x1 < minX || x1 >= maxX) return false;
        } else {
            double inv = 1.0 / dx;
            double tx1 = (minX - x1) * inv;
            double tx2 = (maxX - x1) * inv;
            double tmin = Math.min(tx1, tx2);
            double tmax = Math.max(tx1, tx2);
            t0 = Math.max(t0, tmin);
            t1 = Math.min(t1, tmax);
            if (t0 > t1) return false;
        }

        if (dz == 0.0) {
            if (z1 < minZ || z1 >= maxZ) return false;
        } else {
            double inv = 1.0 / dz;
            double tz1 = (minZ - z1) * inv;
            double tz2 = (maxZ - z1) * inv;
            double tmin = Math.min(tz1, tz2);
            double tmax = Math.max(tz1, tz2);
            t0 = Math.max(t0, tmin);
            t1 = Math.min(t1, tmax);
            if (t0 > t1) return false;
        }

        return t0 <= t1;
    }
}

