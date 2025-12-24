package es.jaie55.boatracing.track;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.logging.Logger;

/**
 * Builds a centerline polyline for a track using constrained A* along allowed surface blocks.
 * Phase 1: simple 2D path on a fixed Y level between segment endpoints (start center -> checkpoints -> finish).
 */
public final class CenterlineBuilder {
    private CenterlineBuilder() {}

    // Allowed surface blocks for pathing (can be made configurable later)
    private static final Set<Material> ALLOWED = EnumSet.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE);

    public static List<Location> build(TrackConfig cfg, int corridorMargin) {
        return build(cfg, corridorMargin, null, false);
    }

    public static List<Location> build(TrackConfig cfg, int corridorMargin, Logger logger, boolean verbose) {
        Location start = cfg.getStartCenter();
        Region finish = cfg.getFinish();
        if (start == null || finish == null) {
            warn(logger, "Centerline build aborted: missing startCenter or finish (track=" + safe(cfg.getCurrentName()) + ")");
            return null;
        }

        if (verbose) {
            info(logger, "Centerline build start: track=" + safe(cfg.getCurrentName()) +
                    " world=" + safe(cfg.getWorldName()) +
                    " checkpoints=" + cfg.getCheckpoints().size() +
                    " margin=" + corridorMargin +
                    " allowed=" + ALLOWED);
        }
        List<Location> waypoints = new ArrayList<>();
        waypoints.add(start);
        for (Region cp : cfg.getCheckpoints()) {
            waypoints.add(centerOf(cp));
        }
        waypoints.add(centerOf(finish));

        // Normalize Y for waypoints by snapping to nearest allowed surface
        for (int i = 0; i < waypoints.size(); i++) {
            Location l = waypoints.get(i);
            String label = (i == 0) ? "start" : (i == waypoints.size() - 1) ? "finish" : ("checkpoint#" + i);
            int y = findSurfaceY(l.getWorld(), l.getBlockX(), l.getBlockZ(), l.getBlockY(), logger, label, verbose);
            if (y == Integer.MIN_VALUE) {
                warn(logger, "Centerline build failed: no allowed surface near waypoint " + label +
                        " at x=" + l.getBlockX() + " z=" + l.getBlockZ() + " yHint=" + l.getBlockY() +
                        " (allowed=" + ALLOWED + ")");
                return null;
            }
            waypoints.set(i, new Location(l.getWorld(), l.getBlockX() + 0.5, y + 1.0, l.getBlockZ() + 0.5));
            if (verbose) {
                info(logger, "Waypoint " + label + " snapped to y=" + y + " -> " + fmt(waypoints.get(i)));
            }
        }

        // Build segments
        List<Location> centerline = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Location a = waypoints.get(i);
            Location b = waypoints.get(i + 1);
            if (verbose) info(logger, "A* segment " + i + ": " + fmt(a) + " -> " + fmt(b));
            List<Location> segment = aStar2DWithRetries(a, b, corridorMargin, 64, logger, "segment#" + i, verbose);
            if (segment == null || segment.isEmpty()) {
                warn(logger, "Centerline build failed: A* returned no path for segment#" + i +
                        " (" + fmt(a) + " -> " + fmt(b) + ")");
                return null;
            }
            // decimate slightly to reduce node count
            appendDecimated(centerline, segment, 0.5);
            if (verbose) info(logger, "Segment " + i + " ok: nodes=" + segment.size() + " (after decimation total=" + centerline.size() + ")");
        }
        if (verbose) info(logger, "Centerline build complete: totalNodes=" + centerline.size());
        return centerline;
    }

    private static Location centerOf(Region r) {
        BoundingBox b = r.getBox();
        World w = Bukkit.getWorld(r.getWorldName());
        return new Location(w, b.getCenterX(), b.getCenterY(), b.getCenterZ());
    }

    private static int findSurfaceY(World w, int x, int z, int yHint, Logger logger, String label, boolean verbose) {
        if (w == null) {
            warn(logger, "findSurfaceY: null world for " + label);
            return Integer.MIN_VALUE;
        }
        // search within +/- 6 blocks around hint
        for (int dy = 0; dy <= 6; dy++) {
            int y1 = yHint - dy; int y2 = yHint + dy;
            if (y1 >= w.getMinHeight()) {
                if (ALLOWED.contains(w.getBlockAt(x, y1, z).getType())) return y1;
            }
            if (y2 <= w.getMaxHeight()-1) {
                if (ALLOWED.contains(w.getBlockAt(x, y2, z).getType())) return y2;
            }
        }
        // fallback: look downward from world surface
        int y = w.getHighestBlockYAt(x, z);
        for (int yy = y; yy >= w.getMinHeight(); yy--) {
            if (ALLOWED.contains(w.getBlockAt(x, yy, z).getType())) return yy;
        }

        // Debug: report some sampled materials in the column.
        if (logger != null) {
            Material atHint = safeType(w, x, yHint, z);
            Material atTop = safeType(w, x, y, z);
            Material atTopMinus1 = safeType(w, x, y - 1, z);
            Material atHintMinus6 = safeType(w, x, yHint - 6, z);
            Material atHintPlus6 = safeType(w, x, yHint + 6, z);
            warn(logger, "findSurfaceY failed for " + label + " at " + w.getName() + " (x=" + x + " z=" + z +
                    ") yHint=" + yHint + " highestY=" + y +
                    " samples: hint=" + atHint + ", hint-6=" + atHintMinus6 + ", hint+6=" + atHintPlus6 +
                    ", top=" + atTop + ", top-1=" + atTopMinus1 +
                    " allowed=" + ALLOWED);
            if (verbose) {
                // Light extra hint: show up to 8 unique materials seen going down from top.
                java.util.Set<Material> seen = new java.util.LinkedHashSet<>();
                for (int yy = y; yy >= Math.max(w.getMinHeight(), y - 64); yy--) {
                    Material t = safeType(w, x, yy, z);
                    if (t != null && t != Material.AIR) seen.add(t);
                    if (seen.size() >= 8) break;
                }
                info(logger, "findSurfaceY: non-air materials near top (up to 64 blocks): " + seen);
            }
        }
        return Integer.MIN_VALUE;
    }

    // Simple 2D A* path on fixed Y plane across allowed blocks within corridor
    private static List<Location> aStar2DWithRetries(Location a, Location b, int baseMargin, int maxMargin, Logger logger, String label, boolean verbose) {
        int margin = Math.max(0, baseMargin);
        int cap = Math.max(margin, maxMargin);

        // Try increasing the corridor when tracks curve outside the initial bounding box.
        while (true) {
            if (verbose) info(logger, "A* " + label + " attempt: margin=" + margin);
            List<Location> result = aStar2D(a, b, margin, logger, label, verbose);
            if (result != null && !result.isEmpty()) return result;
            if (margin >= cap) return null;
            // grow (8 -> 16 -> 32 -> 64)
            margin = (margin == 0) ? 8 : Math.min(cap, margin * 2);
        }
    }

    private static List<Location> aStar2D(Location a, Location b, int margin, Logger logger, String label, boolean verbose) {
        World w = a.getWorld();
        if (w == null || b.getWorld() == null || !w.equals(b.getWorld())) {
            warn(logger, "A* " + label + " aborted: world mismatch (a=" + fmt(a) + ", b=" + fmt(b) + ")");
            return null;
        }

        // Waypoints are stored one block ABOVE the surface (surfaceY + 1).
        // A* must path on the surface itself, otherwise it reads AIR and aborts.
        int y = Math.min(a.getBlockY(), b.getBlockY()) - 1;
        if (y < w.getMinHeight()) y = w.getMinHeight();
        // Corridor bounds
        int minX = Math.min(a.getBlockX(), b.getBlockX()) - margin;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) + margin;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) - margin;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + margin;

        // Ensure start/target are on allowed surface at same y
        Material aType = w.getBlockAt(a.getBlockX(), y, a.getBlockZ()).getType();
        Material bType = w.getBlockAt(b.getBlockX(), y, b.getBlockZ()).getType();
        if (!ALLOWED.contains(aType) || !ALLOWED.contains(bType)) {
            warn(logger, "A* " + label + " aborted: endpoints not on allowed surface at y=" + y +
                " aType=" + aType + " bType=" + bType +
                " (allowed=" + ALLOWED + ")");
            return null;
        }

        Node start = new Node(a.getBlockX(), y, a.getBlockZ());
        Node goal = new Node(b.getBlockX(), y, b.getBlockZ());

        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        java.util.Map<Long, Node> all = new java.util.HashMap<>();

        start.g = 0.0;
        start.f = start.g + h(start, goal);
        open.add(start);
        all.put(key(start.x, start.y, start.z), start);

        int expanded = 0;
        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (cur.x == goal.x && cur.z == goal.z) {
                if (verbose) info(logger, "A* " + label + " success: expanded=" + expanded + " visited=" + all.size());
                return reconstruct(w, cur);
            }
            cur.closed = true;
            expanded++;
            for (int[] d : DIRS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                Block bl = w.getBlockAt(nx, y, nz);
                if (!ALLOWED.contains(bl.getType())) continue;
                long k = key(nx, y, nz);
                Node nb = all.get(k);
                if (nb == null) { nb = new Node(nx, y, nz); all.put(k, nb); }
                if (nb.closed) continue;
                double tg = cur.g + 1.0; // uniform cost
                if (tg < nb.g) {
                    nb.parent = cur;
                    nb.g = tg;
                    nb.f = tg + h(nb, goal);
                    // update in PQ
                    open.remove(nb);
                    open.add(nb);
                }
            }
        }
        warn(logger, "A* " + label + " failed: no path. expanded=" + expanded + " visited=" + all.size() +
                " corridor=[x:" + minX + ".." + maxX + ", z:" + minZ + ".." + maxZ + "] y=" + y +
                " from=" + fmt(a) + " to=" + fmt(b) + " allowed=" + ALLOWED);
        return null; // no path
    }

    private static final int[][] DIRS = new int[][] { {1,0},{-1,0},{0,1},{0,-1} };

    private static double h(Node a, Node b) {
        int dx = a.x - b.x; int dz = a.z - b.z;
        return Math.sqrt(dx*dx + dz*dz);
    }

    private static long key(int x, int y, int z) { return (((long)x & 0x3FFFFFF) << 38) | (((long)z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    private static List<Location> reconstruct(World w, Node goal) {
        java.util.LinkedList<Location> out = new java.util.LinkedList<>();
        Node n = goal;
        while (n != null) {
            out.addFirst(new Location(w, n.x + 0.5, n.y + 1.0, n.z + 0.5));
            n = n.parent;
        }
        return out;
    }

    private static void appendDecimated(List<Location> dst, List<Location> src, double minStep) {
        Location last = dst.isEmpty() ? null : dst.get(dst.size()-1);
        for (Location l : src) {
            if (last == null || l.distanceSquared(last) >= (minStep*minStep)) {
                dst.add(l);
                last = l;
            }
        }
    }

    private static final class Node {
        final int x,y,z;
        double g = Double.POSITIVE_INFINITY;
        double f = Double.POSITIVE_INFINITY;
        boolean closed = false;
        Node parent;
        Node(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
        @Override public boolean equals(Object o){if(!(o instanceof Node n)) return false;return n.x==x&&n.y==y&&n.z==z;}
        @Override public int hashCode(){return java.util.Objects.hash(x,y,z);}        
    }

    private static String fmt(Location l) {
        if (l == null) return "null";
        String w = (l.getWorld() == null) ? "?" : l.getWorld().getName();
        return w + "(" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ")";
    }

    private static Material safeType(World w, int x, int y, int z) {
        try {
            if (w == null) return null;
            if (y < w.getMinHeight() || y > w.getMaxHeight() - 1) return null;
            return w.getBlockAt(x, y, z).getType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void info(Logger logger, String msg) {
        if (logger != null && msg != null) logger.info(msg);
    }

    private static void warn(Logger logger, String msg) {
        if (logger != null && msg != null) logger.warning(msg);
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s;
    }
}
