package es.jaie55.boatracing.track;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.*;

/**
 * Builds a centerline polyline for a track using constrained A* along allowed surface blocks.
 * Phase 1: simple 2D path on a fixed Y level between segment endpoints (start center -> checkpoints -> finish).
 */
public final class CenterlineBuilder {
    private CenterlineBuilder() {}

    // Allowed surface blocks for pathing (can be made configurable later)
    private static final Set<Material> ALLOWED = EnumSet.of(Material.PACKED_ICE, Material.BLUE_ICE);

    public static List<Location> build(TrackConfig cfg, int corridorMargin) {
        Location start = cfg.getStartCenter();
        Region finish = cfg.getFinish();
        if (start == null || finish == null) return null;
        List<Location> waypoints = new ArrayList<>();
        waypoints.add(start);
        for (Region cp : cfg.getCheckpoints()) {
            waypoints.add(centerOf(cp));
        }
        waypoints.add(centerOf(finish));

        // Normalize Y for waypoints by snapping to nearest allowed surface
        for (int i = 0; i < waypoints.size(); i++) {
            Location l = waypoints.get(i);
            int y = findSurfaceY(l.getWorld(), l.getBlockX(), l.getBlockZ(), l.getBlockY());
            if (y == Integer.MIN_VALUE) return null;
            waypoints.set(i, new Location(l.getWorld(), l.getBlockX() + 0.5, y + 1.0, l.getBlockZ() + 0.5));
        }

        // Build segments
        List<Location> centerline = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Location a = waypoints.get(i);
            Location b = waypoints.get(i + 1);
            List<Location> segment = aStar2D(a, b, corridorMargin);
            if (segment == null || segment.isEmpty()) return null;
            // decimate slightly to reduce node count
            appendDecimated(centerline, segment, 0.5);
        }
        return centerline;
    }

    private static Location centerOf(Region r) {
        BoundingBox b = r.getBox();
        World w = Bukkit.getWorld(r.getWorldName());
        return new Location(w, b.getCenterX(), b.getCenterY(), b.getCenterZ());
    }

    private static int findSurfaceY(World w, int x, int z, int yHint) {
        if (w == null) return Integer.MIN_VALUE;
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
        return Integer.MIN_VALUE;
    }

    // Simple 2D A* path on fixed Y plane across allowed blocks within corridor
    private static List<Location> aStar2D(Location a, Location b, int margin) {
        World w = a.getWorld();
        if (w == null || b.getWorld() == null || !w.equals(b.getWorld())) return null;
        int y = a.getBlockY();
        // Corridor bounds
        int minX = Math.min(a.getBlockX(), b.getBlockX()) - margin;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) + margin;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) - margin;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + margin;

        // Ensure start/target are on allowed surface at same y
        if (!ALLOWED.contains(w.getBlockAt(a.getBlockX(), y, a.getBlockZ()).getType())) return null;
        if (!ALLOWED.contains(w.getBlockAt(b.getBlockX(), y, b.getBlockZ()).getType())) return null;

        Node start = new Node(a.getBlockX(), y, a.getBlockZ());
        Node goal = new Node(b.getBlockX(), y, b.getBlockZ());

        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        java.util.Map<Long, Node> all = new java.util.HashMap<>();

        start.g = 0.0;
        start.f = start.g + h(start, goal);
        open.add(start);
        all.put(key(start.x, start.y, start.z), start);

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (cur.x == goal.x && cur.z == goal.z) {
                return reconstruct(w, cur);
            }
            cur.closed = true;
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
}
