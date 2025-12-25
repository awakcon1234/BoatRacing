package es.jaie55.boatracing.track;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.logging.Logger;

/**
 * Builds a centerline polyline for a track using constrained A* along allowed surface blocks.
 * Surface-following A* that can move up/down for sloped tracks.
 */
public final class CenterlineBuilder {
    private CenterlineBuilder() {}

    // Allowed surface blocks for pathing (can be made configurable later)
    private static final Set<Material> ALLOWED = EnumSet.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE);

    // Larger values pull the path toward the middle (away from edges/fences).
    private static final double CENTER_BIAS = 2.0;

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

        // Compute global bounds across all waypoints (used as a fallback if a segment detours far outside its local rectangle).
        int globalMinX = Integer.MAX_VALUE, globalMaxX = Integer.MIN_VALUE;
        int globalMinZ = Integer.MAX_VALUE, globalMaxZ = Integer.MIN_VALUE;
        for (Location wp : waypoints) {
            globalMinX = Math.min(globalMinX, wp.getBlockX());
            globalMaxX = Math.max(globalMaxX, wp.getBlockX());
            globalMinZ = Math.min(globalMinZ, wp.getBlockZ());
            globalMaxZ = Math.max(globalMaxZ, wp.getBlockZ());
        }
        int globalPad = Math.max(32, corridorMargin * 4);
        globalMinX -= globalPad; globalMaxX += globalPad;
        globalMinZ -= globalPad; globalMaxZ += globalPad;
        if (verbose) {
            info(logger, "Centerline global bounds: x=" + globalMinX + ".." + globalMaxX + " z=" + globalMinZ + ".." + globalMaxZ + " (pad=" + globalPad + ")");
        }

        // Build segments
        List<Location> centerline = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Location a = waypoints.get(i);
            Location b = waypoints.get(i + 1);
            if (verbose) info(logger, "A* segment " + i + ": " + fmt(a) + " -> " + fmt(b));
            List<Location> segment = aStar2DWithRetries(a, b, corridorMargin, 64, globalMinX, globalMaxX, globalMinZ, globalMaxZ, logger, "segment#" + i, verbose);
            if (segment == null || segment.isEmpty()) {
                warn(logger, "Centerline build failed: A* returned no path for segment#" + i +
                        " (" + fmt(a) + " -> " + fmt(b) + ")");
                return null;
            }
            // decimate slightly to reduce node count
            appendDecimated(centerline, segment, 0.5);
            if (verbose) info(logger, "Segment " + i + " ok: nodes=" + segment.size() + " (after decimation total=" + centerline.size() + ")");
        }

        // If the finish is very close to the start (typical circuit), close the loop for a continuous centerline.
        // This avoids the visualizer showing two separate ends at the finish line.
        try {
            if (!centerline.isEmpty()) {
                Location a = centerline.get(centerline.size() - 1); // last
                Location b = centerline.get(0); // first
                if (a == null || b == null) throw new IllegalStateException("null endpoints");
                if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) throw new IllegalStateException("world mismatch");

                double dx = a.getX() - b.getX();
                double dz = a.getZ() - b.getZ();
                double distSqXZ = dx * dx + dz * dz;
                double closeDist = 32.0; // blocks
                if (distSqXZ <= closeDist * closeDist) {
                    // Prefer a deterministic stitch along the surface. This avoids A* sometimes choosing a nearby parallel lane.
                    boolean stitched = tryStitchLoopOnSurface(centerline, a, b, logger, verbose);
                    if (!stitched) {
                        if (verbose) info(logger, "A* segment close: " + fmt(a) + " -> " + fmt(b));
                        List<Location> segment = aStar2DWithRetries(a, b, corridorMargin, 64, globalMinX, globalMaxX, globalMinZ, globalMaxZ, logger, "segment#close", verbose);
                        if (segment == null || segment.isEmpty()) {
                            warn(logger, "Centerline loop close failed: A* returned no path for close segment (" + fmt(a) + " -> " + fmt(b) + ")");
                        } else {
                            // Avoid duplicating the final node if already present.
                            if (!segment.isEmpty()) {
                                Location last = centerline.get(centerline.size() - 1);
                                Location first = segment.get(0);
                                if (last != null && first != null && last.getWorld() != null && last.getWorld().equals(first.getWorld())) {
                                    double ddx = last.getX() - first.getX();
                                    double ddy = last.getY() - first.getY();
                                    double ddz = last.getZ() - first.getZ();
                                    if ((ddx * ddx + ddy * ddy + ddz * ddz) < 0.01) {
                                        segment = new ArrayList<>(segment.subList(1, segment.size()));
                                    }
                                }
                            }
                            appendDecimated(centerline, segment, 0.5);
                            if (verbose) info(logger, "Segment close ok: nodes=" + segment.size() + " (after decimation total=" + centerline.size() + ")");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

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

    // Surface-following A* across allowed blocks within corridor.
    // It can change Y by scanning the neighbor column around the current surface Y.
    private static List<Location> aStar2DWithRetries(
            Location a,
            Location b,
            int baseMargin,
            int maxMargin,
            int globalMinX,
            int globalMaxX,
            int globalMinZ,
            int globalMaxZ,
            Logger logger,
            String label,
            boolean verbose
    ) {
        int margin = Math.max(0, baseMargin);
        int cap = Math.max(margin, maxMargin);

        // Try increasing the corridor when tracks curve outside the initial bounding box.
        while (true) {
            if (verbose) info(logger, "A* " + label + " attempt: margin=" + margin);
            List<Location> result = aStar2D(a, b, margin, logger, label, verbose);
            if (result != null && !result.isEmpty()) return result;
            if (margin >= cap) break;
            // grow (8 -> 16 -> 32 -> 64)
            margin = (margin == 0) ? 8 : Math.min(cap, margin * 2);
        }

        // Fallback: allow detours outside the segment rectangle by using global bounds.
        if (verbose) info(logger, "A* " + label + " fallback: trying global bounds search");
        return aStar2DInBounds(a, b, globalMinX, globalMaxX, globalMinZ, globalMaxZ, logger, label + "(global)", verbose);
    }

    private static List<Location> aStar2D(Location a, Location b, int margin, Logger logger, String label, boolean verbose) {
        // Corridor bounds based on segment endpoints
        int minX = Math.min(a.getBlockX(), b.getBlockX()) - margin;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) + margin;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) - margin;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + margin;
        return aStar2DInBounds(a, b, minX, maxX, minZ, maxZ, logger, label, verbose);
    }

    private static List<Location> aStar2DInBounds(Location a, Location b, int minX, int maxX, int minZ, int maxZ, Logger logger, String label, boolean verbose) {
        World w = a.getWorld();
        if (w == null || b.getWorld() == null || !w.equals(b.getWorld())) {
            warn(logger, "A* " + label + " aborted: world mismatch (a=" + fmt(a) + ", b=" + fmt(b) + ")");
            return null;
        }

        // Waypoints are stored one block ABOVE the surface (surfaceY + 1).
        // Determine surface Y for start/goal and allow movement across slopes.
        int startHintY = a.getBlockY() - 1;
        int goalHintY = b.getBlockY() - 1;
        int startY = findSurfaceYNear(w, a.getBlockX(), a.getBlockZ(), startHintY, 8);
        int goalY = findSurfaceYNear(w, b.getBlockX(), b.getBlockZ(), goalHintY, 8);
        if (startY == Integer.MIN_VALUE || goalY == Integer.MIN_VALUE) {
            warn(logger, "A* " + label + " aborted: could not resolve surface Y. startHint=" + startHintY + " -> " + startY +
                    ", goalHint=" + goalHintY + " -> " + goalY);
            return null;
        }
        // bounds provided by caller

        // Ensure start/target are on allowed surface and have clearance above
        Material aType = w.getBlockAt(a.getBlockX(), startY, a.getBlockZ()).getType();
        Material bType = w.getBlockAt(b.getBlockX(), goalY, b.getBlockZ()).getType();
        if (verbose) {
            Material aAbove = w.getBlockAt(a.getBlockX(), startY + 1, a.getBlockZ()).getType();
            Material bAbove = w.getBlockAt(b.getBlockX(), goalY + 1, b.getBlockZ()).getType();
            info(logger, "A* " + label + " endpoints: startSurface=" + aType + "@" + w.getName() + "(" + a.getBlockX() + "," + startY + "," + a.getBlockZ() + ") above=" + aAbove +
                " | goalSurface=" + bType + "@" + w.getName() + "(" + b.getBlockX() + "," + goalY + "," + b.getBlockZ() + ") above=" + bAbove);
        }
        if (!ALLOWED.contains(aType) || !ALLOWED.contains(bType)) {
            warn(logger, "A* " + label + " aborted: endpoints not on allowed surface. startY=" + startY + " aType=" + aType +
                    ", goalY=" + goalY + " bType=" + bType +
                    " (allowed=" + ALLOWED + ")");
            return null;
        }
        if (!isClearAbove(w, a.getBlockX(), startY, a.getBlockZ()) || !isClearAbove(w, b.getBlockX(), goalY, b.getBlockZ())) {
            warn(logger, "A* " + label + " aborted: no clearance above surface at endpoints. startClear=" +
                    isClearAbove(w, a.getBlockX(), startY, a.getBlockZ()) + ", goalClear=" +
                    isClearAbove(w, b.getBlockX(), goalY, b.getBlockZ()));
            return null;
        }

        Node start = new Node(a.getBlockX(), startY, a.getBlockZ());
        Node goal = new Node(b.getBlockX(), goalY, b.getBlockZ());

        WalkGrid grid = WalkGrid.build(w, minX, maxX, minZ, maxZ, startY, 12, logger, label, verbose);
        if (grid == null) {
            warn(logger, "A* " + label + " aborted: failed to build walk grid");
            return null;
        }
        if (!grid.isWalkable(start.x, start.z) || !grid.isWalkable(goal.x, goal.z)) {
            warn(logger, "A* " + label + " aborted: endpoints not walkable in grid. startWalkable=" + grid.isWalkable(start.x, start.z) + " goalWalkable=" + grid.isWalkable(goal.x, goal.z));
            return null;
        }

        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        java.util.Map<Long, Node> all = new java.util.HashMap<>();

        start.g = 0.0;
        start.f = start.g + h(start, goal);
        open.add(start);
        all.put(key(start.x, start.y, start.z), start);

        int expanded = 0;
        final int maxStepUpDown = 2;

        // Debug counters (reported only on failure)
        int skippedOutOfBounds = 0;
        int skippedNoSurface = 0;
        int skippedTooSteep = 0;
        int skippedNotAllowed = 0;
        int skippedNoClearance = 0;
        int skippedNotWalkable = 0;
        int skippedClosed = 0;
        int skippedImprovement = 0;
        java.util.List<String> samples = verbose ? new java.util.ArrayList<>() : java.util.Collections.emptyList();
        final int maxSamples = 12;

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (cur.x == goal.x && cur.z == goal.z && Math.abs(cur.y - goal.y) <= maxStepUpDown) {
                if (verbose) info(logger, "A* " + label + " success: expanded=" + expanded + " visited=" + all.size());
                return reconstruct(w, cur);
            }
            cur.closed = true;
            expanded++;
            for (int[] d : DIRS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                double stepCost = d[2] / 10.0;
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) { skippedOutOfBounds++; continue; }

                if (!grid.isWalkable(nx, nz)) {
                    skippedNotWalkable++;
                    int nyRaw = grid.surfaceY(nx, nz);
                    if (nyRaw == Integer.MIN_VALUE) {
                        skippedNoSurface++;
                        if (samples.size() < maxSamples) {
                            int highest = w.getHighestBlockYAt(nx, nz);
                            Material atHint = safeType(w, nx, cur.y, nz);
                            Material atTop = safeType(w, nx, highest, nz);
                            samples.add("no allowed surface near " + w.getName() + "(" + nx + ",? ," + nz + ") yHint=" + cur.y +
                                    " samples: hint=" + atHint + ", topY=" + highest + " top=" + atTop);
                        }
                    }
                    continue;
                }

                int ny = grid.surfaceY(nx, nz);
                if (Math.abs(ny - cur.y) > maxStepUpDown) {
                    skippedTooSteep++;
                    if (samples.size() < maxSamples) {
                        samples.add("too steep at " + w.getName() + "(" + nx + "," + ny + "," + nz + ") fromY=" + cur.y + " (dy=" + Math.abs(ny - cur.y) + ")");
                    }
                    continue;
                }

                // (walkability already enforces allowed surface + clearance)

                long k = key(nx, ny, nz);
                Node nb = all.get(k);
                if (nb == null) { nb = new Node(nx, ny, nz); all.put(k, nb); }
                if (nb.closed) { skippedClosed++; continue; }

                int dy = Math.abs(ny - cur.y);
                int distToEdge = grid.distToEdge(nx, nz);
                if (distToEdge < 0) distToEdge = 0;
                double centerPenalty = CENTER_BIAS / (distToEdge + 1.0);
                double tg = cur.g + stepCost + (0.2 * dy) + centerPenalty; // prefer smooth slopes + center
                if (tg < nb.g) {
                    nb.parent = cur;
                    nb.g = tg;
                    nb.f = tg + h(nb, goal);
                    // update in PQ
                    open.remove(nb);
                    open.add(nb);
                } else {
                    skippedImprovement++;
                }
            }
        }
        warn(logger, "A* " + label + " failed: no path. expanded=" + expanded + " visited=" + all.size() +
            " corridor=[x:" + minX + ".." + maxX + ", z:" + minZ + ".." + maxZ + "]" +
            " startY=" + startY + " goalY=" + goalY +
            " from=" + fmt(a) + " to=" + fmt(b) + " allowed=" + ALLOWED);
        if (verbose) {
            info(logger, "A* " + label + " skipCounts: outOfBounds=" + skippedOutOfBounds +
                    " noSurface=" + skippedNoSurface +
                    " tooSteep=" + skippedTooSteep +
                    " notAllowed=" + skippedNotAllowed +
                    " noClearance=" + skippedNoClearance +
                    " notWalkable=" + skippedNotWalkable +
                    " closed=" + skippedClosed +
                    " noImprove=" + skippedImprovement);
            for (String s : samples) {
                info(logger, "A* " + label + " sample: " + s);
            }
        }
        return null; // no path
    }

    private static final class WalkGrid {
        final int minX, minZ, width, height;
        final int[] surfaceY; // Integer.MIN_VALUE for none
        final boolean[] walkable;
        final int[] dist; // distance-to-edge for walkable cells, -1 for not walkable

        private WalkGrid(int minX, int minZ, int width, int height) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            int n = width * height;
            this.surfaceY = new int[n];
            this.walkable = new boolean[n];
            this.dist = new int[n];
            java.util.Arrays.fill(this.surfaceY, Integer.MIN_VALUE);
            java.util.Arrays.fill(this.dist, -1);
        }

        int idx(int x, int z) { return (z - minZ) * width + (x - minX); }
        boolean inBounds(int x, int z) { return x >= minX && x < (minX + width) && z >= minZ && z < (minZ + height); }

        boolean isWalkable(int x, int z) {
            if (!inBounds(x, z)) return false;
            return walkable[idx(x, z)];
        }

        int surfaceY(int x, int z) {
            if (!inBounds(x, z)) return Integer.MIN_VALUE;
            return surfaceY[idx(x, z)];
        }

        int distToEdge(int x, int z) {
            if (!inBounds(x, z)) return -1;
            return dist[idx(x, z)];
        }

        static WalkGrid build(World w, int minX, int maxX, int minZ, int maxZ, int yHint, int scan, Logger logger, String label, boolean verbose) {
            int width = (maxX - minX) + 1;
            int height = (maxZ - minZ) + 1;
            long cells = (long) width * (long) height;
            // Safety cap to avoid huge allocations on very large bounds.
            long cap = 1_200_000L;
            if (cells <= 0 || cells > cap) {
                if (verbose) warn(logger, "A* " + label + " grid skipped: bounds too large (" + width + "x" + height + "=" + cells + ")");
                // Fallback: no grid centering
                WalkGrid g = new WalkGrid(minX, minZ, width, height);
                // minimal walkability computed on-demand would be nicer, but we avoid allocation.
                // Returning null forces caller to abort; caller can keep previous behavior if needed.
                return g;
            }

            WalkGrid g = new WalkGrid(minX, minZ, width, height);
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int y = findSurfaceYNear(w, x, z, yHint, scan);
                    int id = g.idx(x, z);
                    g.surfaceY[id] = y;
                    if (y == Integer.MIN_VALUE) {
                        g.walkable[id] = false;
                        g.dist[id] = -1;
                        continue;
                    }
                    Material t = w.getBlockAt(x, y, z).getType();
                    boolean ok = ALLOWED.contains(t) && isClearAbove(w, x, y, z);
                    g.walkable[id] = ok;
                    g.dist[id] = ok ? Integer.MAX_VALUE : -1;
                }
            }

            // Multi-source BFS from edges/boundaries to compute distance-to-edge.
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int id = g.idx(x, z);
                    if (!g.walkable[id]) continue;
                    boolean boundary = (x == minX || x == maxX || z == minZ || z == maxZ);
                    if (!boundary) {
                        // 4-neighbor boundary check is sufficient for "edge hugging".
                        if (!g.isWalkable(x + 1, z) || !g.isWalkable(x - 1, z) || !g.isWalkable(x, z + 1) || !g.isWalkable(x, z - 1)) {
                            boundary = true;
                        }
                    }
                    if (boundary) {
                        g.dist[id] = 0;
                        q.add(id);
                    }
                }
            }

            while (!q.isEmpty()) {
                int cur = q.poll();
                int cx = (cur % g.width) + g.minX;
                int cz = (cur / g.width) + g.minZ;
                int cd = g.dist[cur];
                // 4-neighbor expansion
                int[] nx = new int[] { cx + 1, cx - 1, cx, cx };
                int[] nz = new int[] { cz, cz, cz + 1, cz - 1 };
                for (int i = 0; i < 4; i++) {
                    int x = nx[i], z = nz[i];
                    if (!g.inBounds(x, z)) continue;
                    int id = g.idx(x, z);
                    if (!g.walkable[id]) continue;
                    if (g.dist[id] > cd + 1) {
                        g.dist[id] = cd + 1;
                        q.add(id);
                    }
                }
            }

            if (verbose) {
                // Quick sanity: report max distance in this grid.
                int maxD = 0;
                for (int d : g.dist) if (d > maxD && d < Integer.MAX_VALUE) maxD = d;
                info(logger, "A* " + label + " grid built: size=" + width + "x" + height + " maxDistToEdge=" + maxD);
            }
            return g;
        }
    }

        // 8-direction movement (cardinal cost=1.0, diagonal cost≈1.4)
        // Cost is encoded as tenths in the third element to avoid doubles in the array.
        private static final int[][] DIRS = new int[][] {
            { 1, 0, 10 }, { -1, 0, 10 }, { 0, 1, 10 }, { 0, -1, 10 },
            { 1, 1, 14 }, { 1, -1, 14 }, { -1, 1, 14 }, { -1, -1, 14 }
        };

    private static double h(Node a, Node b) {
        int dx = a.x - b.x; int dz = a.z - b.z; int dy = a.y - b.y;
        return Math.sqrt(dx*dx + dz*dz + (dy*dy * 0.25));
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

    private static int cachedSurfaceY(World w, int x, int z, int yHint, int scan, java.util.Map<Long, Integer> cache) {
        long k = key(x, 0, z);
        Integer cached = cache.get(k);
        if (cached != null) return cached;
        int y = findSurfaceYNear(w, x, z, yHint, scan);
        cache.put(k, y);
        return y;
    }

    private static int findSurfaceYNear(World w, int x, int z, int yHint, int maxDelta) {
        if (w == null) return Integer.MIN_VALUE;
        int hint = Math.min(w.getMaxHeight() - 1, Math.max(w.getMinHeight(), yHint));
        for (int dy = 0; dy <= maxDelta; dy++) {
            int y1 = hint - dy;
            int y2 = hint + dy;
            if (y1 >= w.getMinHeight()) {
                if (ALLOWED.contains(w.getBlockAt(x, y1, z).getType())) return y1;
            }
            if (y2 <= w.getMaxHeight() - 1) {
                if (ALLOWED.contains(w.getBlockAt(x, y2, z).getType())) return y2;
            }
        }

        // Fallback: look downward from the highest block in this column.
        // This prevents false "no surface" when the hint Y is off.
        int top = w.getHighestBlockYAt(x, z);
        int min = w.getMinHeight();
        int maxSteps = 128;
        for (int yy = top; yy >= min && (top - yy) <= maxSteps; yy--) {
            if (ALLOWED.contains(w.getBlockAt(x, yy, z).getType())) return yy;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isClearAbove(World w, int x, int surfaceY, int z) {
        int y = surfaceY + 1;
        if (w == null) return false;
        if (y < w.getMinHeight() || y > w.getMaxHeight() - 1) return false;
        return w.getBlockAt(x, y, z).getType().isAir();
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

    private static boolean tryStitchLoopOnSurface(List<Location> centerline, Location from, Location to, Logger logger, boolean verbose) {
        if (centerline == null || centerline.isEmpty() || from == null || to == null) return false;
        World w = from.getWorld();
        if (w == null || to.getWorld() == null || !w.equals(to.getWorld())) return false;

        int x0 = from.getBlockX();
        int z0 = from.getBlockZ();
        int x1 = to.getBlockX();
        int z1 = to.getBlockZ();
        if (x0 == x1 && z0 == z1) return true;

        // Only stitch very short seams; otherwise rely on A*.
        int dx = x1 - x0;
        int dz = z1 - z0;
        if ((dx * dx + dz * dz) > (12 * 12)) return false;

        java.util.List<int[]> line = bresenhamXZ(x0, z0, x1, z1);
        if (line.size() <= 1) return false;

        java.util.ArrayList<Location> stitch = new java.util.ArrayList<>(line.size());
        java.util.Map<Long, Integer> cache = new java.util.HashMap<>();
        int hintY = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, from.getBlockY() - 1));

        // Skip the first point (already present as the last node).
        for (int i = 1; i < line.size(); i++) {
            int[] p = line.get(i);
            int x = p[0];
            int z = p[1];
            int surfaceY = cachedSurfaceY(w, x, z, hintY, 4, cache);
            if (surfaceY == Integer.MIN_VALUE) return false;
            Material surface = w.getBlockAt(x, surfaceY, z).getType();
            if (!ALLOWED.contains(surface)) return false;
            if (!isClearAbove(w, x, surfaceY, z)) return false;

            hintY = surfaceY;
            stitch.add(new Location(w, x + 0.5, surfaceY + 1.0, z + 0.5));
        }

        // Avoid duplicating the first node if the last stitch point is already at the first node.
        if (!stitch.isEmpty()) {
            Location last = stitch.get(stitch.size() - 1);
            if (last.getBlockX() == to.getBlockX() && last.getBlockZ() == to.getBlockZ()) {
                // Keep it (it is the intended closure), but ensure we don't add a duplicate within epsilon.
                Location firstNode = centerline.get(0);
                double ddx = last.getX() - firstNode.getX();
                double ddy = last.getY() - firstNode.getY();
                double ddz2 = last.getZ() - firstNode.getZ();
                if ((ddx * ddx + ddy * ddy + ddz2 * ddz2) < 0.01) {
                    stitch.remove(stitch.size() - 1);
                }
            }
        }

        appendDecimated(centerline, stitch, 0.25);
        if (verbose) info(logger, "Loop stitch ok: nodes=" + stitch.size() + " (total=" + centerline.size() + ")");
        return true;
    }

    private static java.util.List<int[]> bresenhamXZ(int x0, int z0, int x1, int z1) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        int x = x0;
        int z = z0;
        while (true) {
            out.add(new int[] { x, z });
            if (x == x1 && z == z1) break;
            int e2 = err << 1;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
        return out;
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
