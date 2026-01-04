package dev.belikhun.boatracing.track;

import org.bukkit.*;
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
		if (finish == null) {
			warn(logger, "Centerline build aborted: missing finish (track=" + safe(cfg.getCurrentName()) + ")");
			return null;
		}

		Location finishCenter = null;
		try { finishCenter = centerOf(finish); } catch (Throwable ignored) { finishCenter = null; }
		if (finishCenter == null) {
			warn(logger, "Centerline build aborted: invalid finish region (track=" + safe(cfg.getCurrentName()) + ")");
			return null;
		}

		if (verbose) {
			info(logger, "Centerline build start: track=" + safe(cfg.getCurrentName()) +
					" world=" + safe(cfg.getWorldName()) +
					" checkpoints=" + cfg.getCheckpoints().size() +
					" margin=" + corridorMargin +
					" allowed=" + ALLOWED);
		}
		// Build a loop that starts/ends at the finish line (start line):
		// finish -> checkpoints -> finish
		// If there are no checkpoints, fall back to: finish -> startCenter -> finish
		// IMPORTANT: finish/checkpoints are areas. We select an on-surface anchor point inside each
		// region based on prev/next hints to avoid A* choosing a different parallel lane on return.
		java.util.List<Object> specs = new java.util.ArrayList<>();
		specs.add(finish);
		if (cfg.getCheckpoints() != null && !cfg.getCheckpoints().isEmpty()) {
			for (Region cp : cfg.getCheckpoints()) {
				specs.add(cp);
			}
		} else {
			if (start == null) {
				warn(logger, "Centerline build aborted: missing startCenter and checkpoints (track=" + safe(cfg.getCurrentName()) + ")");
				return null;
			}
			specs.add(start);
		}
		specs.add(finish);

		java.util.List<Location> waypoints = new java.util.ArrayList<>();
		java.util.ArrayList<Boolean> isFinishGoal = new java.util.ArrayList<>();
		java.util.ArrayList<Object> waypointSpecs = new java.util.ArrayList<>();
		java.util.ArrayList<String> waypointLabels = new java.util.ArrayList<>();
		Location firstFinishAnchor = null;
		for (int i = 0; i < specs.size(); i++) {
			Object spec = specs.get(i);
			String label;
			if (i == 0) label = "finish(start)";
			else if (i == specs.size() - 1) label = "finish(close)";
			else label = (cfg.getCheckpoints() != null && !cfg.getCheckpoints().isEmpty()) ? ("checkpoint#" + i) : "startCenter";

			Location prev = waypoints.isEmpty() ? null : waypoints.get(waypoints.size() - 1);
			Location nextHint = null;
			if (i + 1 < specs.size()) {
				Object nx = specs.get(i + 1);
				try {
					if (nx instanceof Region nr) nextHint = centerOf(nr);
					else if (nx instanceof Location nl) nextHint = nl;
				} catch (Throwable ignored) { nextHint = null; }
			}

			Location resolved;
			if (spec instanceof Region r) {
				// Special-case finish: always prefer the finish center (snapped to ice) as the anchor.
				// We still treat the closing finish as a region-goal for A* so it can stop as soon as it crosses.
				boolean isFinishSpec = (r == finish);
				if (isFinishSpec) {
					Location c = null;
					try { c = centerOf(r); } catch (Throwable ignored) { c = null; }
					if (c == null) {
						warn(logger, "Centerline build aborted: invalid region for " + label + " (track=" + safe(cfg.getCurrentName()) + ")");
						return null;
					}
					int y = findSurfaceY(c.getWorld(), c.getBlockX(), c.getBlockZ(), c.getBlockY(), logger, label, verbose);
					if (y == Integer.MIN_VALUE) {
						// Fallback: pick any valid on-surface anchor inside the finish region.
						resolved = pickAnchorInRegion(r, null, null, logger, label, verbose);
					} else {
						resolved = new Location(c.getWorld(), c.getBlockX() + 0.5, y + 1.0, c.getBlockZ() + 0.5);
					}
				} else {
					resolved = pickAnchorInRegion(r, prev, nextHint, logger, label, verbose);
				}
				if (resolved == null) {
					// Fallback to center snap (legacy behavior).
					Location c;
					try { c = centerOf(r); } catch (Throwable ignored) { c = null; }
					if (c == null) {
						warn(logger, "Centerline build aborted: invalid region for " + label + " (track=" + safe(cfg.getCurrentName()) + ")");
						return null;
					}
					int y = findSurfaceY(c.getWorld(), c.getBlockX(), c.getBlockZ(), c.getBlockY(), logger, label, verbose);
					if (y == Integer.MIN_VALUE) {
						warn(logger, "Centerline build failed: no allowed surface near waypoint " + label +
								" at x=" + c.getBlockX() + " z=" + c.getBlockZ() + " yHint=" + c.getBlockY() +
								" (allowed=" + ALLOWED + ")");
						return null;
					}
					resolved = new Location(c.getWorld(), c.getBlockX() + 0.5, y + 1.0, c.getBlockZ() + 0.5);
				}

				if (i == 0) firstFinishAnchor = resolved;
				if (i == specs.size() - 1 && firstFinishAnchor != null) {
					// Keep the explicit loop closure at the same finish coordinate.
					resolved = firstFinishAnchor.clone();
				}
			} else {
				Location l = (Location) spec;
				int y = findSurfaceY(l.getWorld(), l.getBlockX(), l.getBlockZ(), l.getBlockY(), logger, label, verbose);
				if (y == Integer.MIN_VALUE) {
					warn(logger, "Centerline build failed: no allowed surface near waypoint " + label +
							" at x=" + l.getBlockX() + " z=" + l.getBlockZ() + " yHint=" + l.getBlockY() +
							" (allowed=" + ALLOWED + ")");
					return null;
				}
				resolved = new Location(l.getWorld(), l.getBlockX() + 0.5, y + 1.0, l.getBlockZ() + 0.5);
			}

			waypoints.add(resolved);
			waypointSpecs.add(spec);
			waypointLabels.add(label);
			// Only the *closing* finish should be treated as a region-goal for A*.
			boolean finishGoal = (spec instanceof Region) && (spec == finish) && (i == specs.size() - 1);
			isFinishGoal.add(finishGoal);
			if (verbose) info(logger, "Waypoint " + label + " -> " + fmt(resolved));
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
			List<Location> segment;
			boolean usedGlobal = false;
			final int localMaxMargin = 128;
			if (i + 1 < isFinishGoal.size() && Boolean.TRUE.equals(isFinishGoal.get(i + 1))) {
				// Stop searching as soon as we cross into the finish area, then snap to finish center at ice level.
				Location finishSnap = null;
				try {
					Location fc = centerOf(finish);
					if (fc != null) {
						int fy = findSurfaceY(fc.getWorld(), fc.getBlockX(), fc.getBlockZ(), fc.getBlockY(), logger, "finish(snap)", verbose);
						if (fy != Integer.MIN_VALUE) finishSnap = new Location(fc.getWorld(), fc.getBlockX() + 0.5, fy + 1.0, fc.getBlockZ() + 0.5);
					}
				} catch (Throwable ignored) { finishSnap = null; }
				segment = aStar2DToRegionWithRetries(a, finish, finishSnap, corridorMargin, localMaxMargin, globalMinX, globalMaxX, globalMinZ, globalMaxZ, logger, "segment#" + i + "(finish)", verbose);
			} else {
				// Prefer local corridor search. Global fallback can create a huge detour that looks like "two loops".
				segment = aStar2DWithRetriesLocalOnly(a, b, corridorMargin, localMaxMargin, logger, "segment#" + i, verbose);
				if (segment == null || segment.isEmpty()) {
					// If the target waypoint is a checkpoint REGION, try re-picking a better anchor inside it.
					Object endSpec = (i + 1 < waypointSpecs.size()) ? waypointSpecs.get(i + 1) : null;
					boolean canReAnchorEnd = (endSpec instanceof Region) && (endSpec != finish);
					if (canReAnchorEnd) {
						Region endRegion = (Region) endSpec;
						Location nextHint = (i + 2 < waypoints.size()) ? waypoints.get(i + 2) : null;
						String endLabel = (i + 1 < waypointLabels.size()) ? waypointLabels.get(i + 1) : ("checkpoint#" + (i + 1));
						java.util.List<Location> candidates = listAnchorCandidatesInRegion(endRegion, a, nextHint, 18, logger, endLabel, verbose);
						for (Location cand : candidates) {
							List<Location> segTry = aStar2DWithRetriesLocalOnly(a, cand, corridorMargin, localMaxMargin, logger, "segment#" + i + "(reanchor)", verbose);
							if (segTry == null || segTry.isEmpty())
								continue;
							// Look-ahead: ensure the next segment is also feasible locally (prevents shifting the problem).
							if (nextHint != null) {
								boolean nextIsFinishGoal = (i + 2 < isFinishGoal.size()) && Boolean.TRUE.equals(isFinishGoal.get(i + 2));
								if (!nextIsFinishGoal) {
									List<Location> nextTry = aStar2DWithRetriesLocalOnly(cand, nextHint, corridorMargin, localMaxMargin, logger, "segment#" + (i + 1) + "(lookahead)", false);
									if (nextTry == null || nextTry.isEmpty())
										continue;
								}
							}
							// Accept this anchor.
							if (verbose) info(logger, "Re-anchor " + endLabel + " -> " + fmt(cand) + " (to avoid global detour)");
							waypoints.set(i + 1, cand);
							b = cand;
							segment = segTry;
							break;
						}
					}
				}

				// As a last resort, allow global search, but reject suspiciously long paths.
				if (segment == null || segment.isEmpty()) {
					if (verbose) info(logger, "A* segment#" + i + " fallback: trying global bounds search (last resort)");
					segment = aStar2DInBounds(a, b, globalMinX, globalMaxX, globalMinZ, globalMaxZ, logger, "segment#" + i + "(global)", verbose);
					usedGlobal = true;
					if (segment != null && !segment.isEmpty() && isSuspiciousSegmentDetour(a, b, segment)) {
						warn(logger, "Centerline build failed: segment#" + i + " global detour too long (nodes=" + segment.size() + ") from=" + fmt(a) + " to=" + fmt(b));
						segment = null;
					}
				}
			}
			if (segment == null || segment.isEmpty()) {
				warn(logger, "Centerline build failed: A* returned no path for segment#" + i +
						" (" + fmt(a) + " -> " + fmt(b) + ")");
				return null;
			}
			// decimate slightly to reduce node count
			appendDecimated(centerline, segment, 0.5);
			if (verbose) info(logger, "Segment " + i + " ok: nodes=" + segment.size() + " (after decimation total=" + centerline.size() + ")" + (usedGlobal ? " [GLOBAL]" : ""));
		}

		// NOTE: We already generate an explicit loop by using finish->...->finish waypoints.
		// Avoid the older generic "close" logic here because it can occasionally pick the long way around
		// and effectively add a second lap before returning to the finish.


		// Rotate the loop to start at the finish line and ensure explicit closure at the same node.
		try { ensureStartEndAtFinish(centerline, finish, finishCenter, logger, verbose); } catch (Throwable ignored) {}
		// Ensure we only generate ONE lap: stop on first finish re-entry after the last checkpoint.
		try { capAtFinishAfterLastCheckpoint(centerline, finish, cfg.getCheckpoints(), finishCenter, logger, verbose); } catch (Throwable ignored) {}
		// Remove small self-loops/detours that can show up as "two lines" on straights.
		// Keep anything that touches finish/checkpoint regions so we don't skip required course gates.
		try { pruneLocalSelfLoops(centerline, finish, cfg.getCheckpoints(), logger, verbose); } catch (Throwable ignored) {}
		// Re-normalize start/end around finish after any truncation/pruning.
		try { ensureStartEndAtFinish(centerline, finish, finishCenter, logger, verbose); } catch (Throwable ignored) {}

		if (verbose) info(logger, "Centerline build complete: totalNodes=" + centerline.size());
		return centerline;
	}

	private static void capAtFinishAfterLastCheckpoint(
			List<Location> centerline,
			Region finish,
			java.util.List<Region> checkpoints,
			Location finishCenter,
			Logger logger,
			boolean verbose
	) {
		if (centerline == null || centerline.size() < 4 || finish == null)
			return;

		World w = null;
		try {
			if (finishCenter == null) finishCenter = centerOf(finish);
			w = (finishCenter != null) ? finishCenter.getWorld() : null;
		} catch (Throwable ignored) { w = null; }
		if (w == null)
			return;

		Location finishSnap = null;
		try {
			int fy = findSurfaceY(w, finishCenter.getBlockX(), finishCenter.getBlockZ(), finishCenter.getBlockY(), logger, "finish(cap)", verbose);
			if (fy != Integer.MIN_VALUE) finishSnap = new Location(w, finishCenter.getBlockX() + 0.5, fy + 1.0, finishCenter.getBlockZ() + 0.5);
		} catch (Throwable ignored) { finishSnap = null; }

		int cpCount = (checkpoints != null) ? checkpoints.size() : 0;
		int nextCpIdx = 0;
		boolean inTargetCp = false;
		boolean leftFinish = false;

		final int minNodesBeforeStop = 24;
		java.util.ArrayList<Location> out = new java.util.ArrayList<>(centerline.size());

		for (Location p : centerline) {
			if (p == null || p.getWorld() == null || !p.getWorld().equals(w))
				continue;

			boolean inFinish = false;
			try { inFinish = finish.containsXZ(p); } catch (Throwable ignored) { inFinish = false; }
			if (!leftFinish && !inFinish)
				leftFinish = true;

			// Progress checkpoints in order.
			if (cpCount > 0 && nextCpIdx < cpCount) {
				Region target = checkpoints.get(nextCpIdx);
				boolean inside = false;
				try { inside = (target != null) && target.containsXZ(p); } catch (Throwable ignored) { inside = false; }
				if (!inTargetCp && inside) {
					nextCpIdx++;
					inTargetCp = true;
				} else if (inTargetCp && !inside) {
					inTargetCp = false;
				}
			}

			out.add(p);

			// Stop only after we've left the initial finish, crossed all checkpoints, and re-entered finish.
			if (leftFinish && inFinish && nextCpIdx >= cpCount && out.size() >= minNodesBeforeStop) {
				break;
			}
		}

		// If we didn't cap anything, keep original.
		if (out.size() >= centerline.size())
			return;

		// Snap final node to finish center on ice to avoid edge snapping.
		if (finishSnap != null) {
			try {
				Location last = out.get(out.size() - 1);
				double dx = last.getX() - finishSnap.getX();
				double dz = last.getZ() - finishSnap.getZ();
				if ((dx * dx + dz * dz) <= (18.0 * 18.0)) {
					out.set(out.size() - 1, finishSnap);
				} else {
					out.add(finishSnap);
				}
			} catch (Throwable ignored) {}
		}

		centerline.clear();
		centerline.addAll(out);
		if (verbose) info(logger, "Centerline cap: nodes=" + centerline.size() + " checkpointsReached=" + nextCpIdx + "/" + cpCount);
	}

	private static List<Location> aStar2DToRegionWithRetries(
			Location a,
			Region goalRegion,
			Location snapTo,
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
		if (goalRegion == null) return null;
		int margin = Math.max(0, baseMargin);
		int cap = Math.max(margin, maxMargin);

		while (true) {
			if (verbose) info(logger, "A* " + label + " attempt: margin=" + margin);
			List<Location> result = aStar2DToRegion(a, goalRegion, margin, logger, label, verbose);
			if (result != null && !result.isEmpty()) {
				return snapTail(result, snapTo);
			}
			if (margin >= cap) break;
			margin = (margin == 0) ? 8 : Math.min(cap, margin * 2);
		}

		if (verbose) info(logger, "A* " + label + " fallback: trying global bounds search");
		List<Location> r = aStar2DToRegionInBounds(a, goalRegion, globalMinX, globalMaxX, globalMinZ, globalMaxZ, logger, label + "(global)", verbose);
		return snapTail(r, snapTo);
	}

	private static List<Location> snapTail(List<Location> path, Location snapTo) {
		if (path == null || path.isEmpty() || snapTo == null || snapTo.getWorld() == null)
			return path;
		try {
			Location last = path.get(path.size() - 1);
			if (last != null && last.getWorld() != null && last.getWorld().equals(snapTo.getWorld())) {
				// Replace if already close; otherwise append (stays within the finish area).
				double dx = last.getX() - snapTo.getX();
				double dz = last.getZ() - snapTo.getZ();
				if ((dx * dx + dz * dz) <= (12.0 * 12.0)) {
					path.set(path.size() - 1, snapTo.clone());
					return path;
				}
			}
			path.add(snapTo.clone());
		} catch (Throwable ignored) {}
		return path;
	}

	private static List<Location> aStar2DToRegion(Location a, Region goalRegion, int margin, Logger logger, String label, boolean verbose) {
		// Use the region center only to derive a corridor rectangle.
		Location c;
		try { c = centerOf(goalRegion); } catch (Throwable ignored) { c = null; }
		if (c == null) return null;
		int minX = Math.min(a.getBlockX(), c.getBlockX()) - margin;
		int maxX = Math.max(a.getBlockX(), c.getBlockX()) + margin;
		int minZ = Math.min(a.getBlockZ(), c.getBlockZ()) - margin;
		int maxZ = Math.max(a.getBlockZ(), c.getBlockZ()) + margin;
		return aStar2DToRegionInBounds(a, goalRegion, minX, maxX, minZ, maxZ, logger, label, verbose);
	}

	private static List<Location> aStar2DToRegionInBounds(Location a, Region goalRegion, int minX, int maxX, int minZ, int maxZ, Logger logger, String label, boolean verbose) {
		World w = a.getWorld();
		if (w == null || goalRegion.getWorldName() == null || !w.getName().equals(goalRegion.getWorldName())) {
			warn(logger, "A* " + label + " aborted: world mismatch (a=" + fmt(a) + ", goalWorld=" + safe(goalRegion.getWorldName()) + ")");
			return null;
		}

		int startHintY = a.getBlockY() - 1;
		int startY = findSurfaceYNear(w, a.getBlockX(), a.getBlockZ(), startHintY, 8);
		if (startY == Integer.MIN_VALUE) {
			warn(logger, "A* " + label + " aborted: could not resolve start surface Y.");
			return null;
		}

		Material aType = w.getBlockAt(a.getBlockX(), startY, a.getBlockZ()).getType();
		if (!ALLOWED.contains(aType) || !isClearAbove(w, a.getBlockX(), startY, a.getBlockZ())) {
			warn(logger, "A* " + label + " aborted: start not on allowed surface/clearance. aType=" + aType);
			return null;
		}

		Node start = new Node(a.getBlockX(), startY, a.getBlockZ());
		WalkGrid grid = WalkGrid.build(w, minX, maxX, minZ, maxZ, startY, 12, logger, label, verbose);
		if (grid == null) {
			if (verbose) warn(logger, "A* " + label + " switching to on-demand search (grid too large)");
			return aStar2DToRegionInBoundsOnDemand(w, start, goalRegion, minX, maxX, minZ, maxZ, logger, label, verbose);
		}
		if (!grid.isWalkable(start.x, start.z)) {
			warn(logger, "A* " + label + " aborted: start not walkable in grid.");
			return null;
		}

		java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		java.util.Map<Long, Node> all = new java.util.HashMap<>();
		start.g = 0.0;
		start.f = 0.0;
		open.add(start);
		all.put(key(start.x, start.y, start.z), start);

		int expanded = 0;
		final int maxStepUpDown = 2;
		final int maxExpanded = 200_000;
		final long startNs = System.nanoTime();
		final long maxNs = 600_000_000L;

		while (!open.isEmpty()) {
			Node cur = open.poll();
			// Success if we reached inside the goal region (XZ) on surface.
			try {
				Location here = new Location(w, cur.x + 0.5, cur.y + 1.0, cur.z + 0.5);
				if (goalRegion.containsXZ(here)) {
					if (verbose) info(logger, "A* " + label + " success(region): expanded=" + expanded + " visited=" + all.size());
					return reconstruct(w, cur);
				}
			} catch (Throwable ignored) {}

			cur.closed = true;
			expanded++;
			if (expanded >= maxExpanded) {
				warn(logger, "A* " + label + " failed(region): expansion cap reached (" + maxExpanded + ")");
				return null;
			}
			if ((expanded & 0x3FF) == 0 && (System.nanoTime() - startNs) > maxNs) {
				warn(logger, "A* " + label + " failed(region): time budget exceeded (" + (maxNs / 1_000_000L) + "ms)");
				return null;
			}

			for (int[] d : DIRS) {
				int nx = cur.x + d[0];
				int nz = cur.z + d[1];
				double stepCost = d[2] / 10.0;
				if (!grid.inBounds(nx, nz))
					continue;
				if (!grid.isWalkable(nx, nz))
					continue;

				int ny = grid.surfaceY(nx, nz);
				if (ny == Integer.MIN_VALUE)
					continue;
				if (Math.abs(ny - cur.y) > maxStepUpDown)
					continue;

				long kk = key(nx, ny, nz);
				Node nb = all.get(kk);
				if (nb == null) { nb = new Node(nx, ny, nz); all.put(kk, nb); }
				if (nb.closed)
					continue;

				int dy = Math.abs(ny - cur.y);
				int distToEdge = grid.distToEdge(nx, nz);
				if (distToEdge < 0) distToEdge = 0;
				double centerPenalty = CENTER_BIAS / (distToEdge + 1.0);
				double tg = cur.g + stepCost + (0.2 * dy) + centerPenalty;
				if (tg < nb.g) {
					nb.parent = cur;
					nb.g = tg;
					// Use 0 heuristic; region goal isn't a single point.
					nb.f = tg;
					open.remove(nb);
					open.add(nb);
				}
			}
		}

		warn(logger, "A* " + label + " failed(region): no path. expanded=" + expanded + " visited=" + all.size() +
				" corridor=[x:" + minX + ".." + maxX + ", z:" + minZ + ".." + maxZ + "]" +
				" from=" + fmt(a) + " goalRegionWorld=" + safe(goalRegion.getWorldName()));
		return null;
	}

	private static List<Location> aStar2DToRegionInBoundsOnDemand(
			World w,
			Node start,
			Region goalRegion,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			Logger logger,
			String label,
			boolean verbose
	) {
		java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		java.util.Map<Long, Node> all = new java.util.HashMap<>();
		java.util.Map<Long, Integer> surfaceCache = new java.util.HashMap<>();

		start.g = 0.0;
		start.f = 0.0;
		open.add(start);
		all.put(key(start.x, start.y, start.z), start);

		int expanded = 0;
		final int maxStepUpDown = 2;
		final int maxExpanded = 200_000;
		final long startNs = System.nanoTime();
		final long maxNs = 600_000_000L;

		while (!open.isEmpty()) {
			Node cur = open.poll();
			try {
				Location here = new Location(w, cur.x + 0.5, cur.y + 1.0, cur.z + 0.5);
				if (goalRegion.containsXZ(here)) {
					if (verbose) info(logger, "A* " + label + " success(on-demand,region): expanded=" + expanded + " visited=" + all.size());
					return reconstruct(w, cur);
				}
			} catch (Throwable ignored) {}

			cur.closed = true;
			expanded++;
			if (expanded >= maxExpanded) {
				warn(logger, "A* " + label + " failed(on-demand,region): expansion cap reached (" + maxExpanded + ")");
				return null;
			}
			if ((expanded & 0x3FF) == 0 && (System.nanoTime() - startNs) > maxNs) {
				warn(logger, "A* " + label + " failed(on-demand,region): time budget exceeded (" + (maxNs / 1_000_000L) + "ms)");
				return null;
			}

			for (int[] d : DIRS) {
				int nx = cur.x + d[0];
				int nz = cur.z + d[1];
				double stepCost = d[2] / 10.0;
				if (nx < minX || nx > maxX || nz < minZ || nz > maxZ)
					continue;

				int ny = cachedSurfaceY(w, nx, nz, cur.y, 12, surfaceCache);
				if (ny == Integer.MIN_VALUE)
					continue;
				if (!isClearAbove(w, nx, ny, nz))
					continue;
				Material t = w.getBlockAt(nx, ny, nz).getType();
				if (!ALLOWED.contains(t))
					continue;
				if (Math.abs(ny - cur.y) > maxStepUpDown)
					continue;

				long kk = key(nx, ny, nz);
				Node nb = all.get(kk);
				if (nb == null) {
					nb = new Node(nx, ny, nz);
					all.put(kk, nb);
				}
				if (nb.closed)
					continue;

				int dy = Math.abs(ny - cur.y);
				int distToCorridorEdge = Math.min(Math.min(nx - minX, maxX - nx), Math.min(nz - minZ, maxZ - nz));
				if (distToCorridorEdge < 0)
					distToCorridorEdge = 0;
				double centerPenalty = CENTER_BIAS / (distToCorridorEdge + 1.0);
				double tg = cur.g + stepCost + (0.2 * dy) + centerPenalty;
				if (tg < nb.g) {
					nb.parent = cur;
					nb.g = tg;
					nb.f = tg;
					open.remove(nb);
					open.add(nb);
				}
			}
		}

		warn(logger, "A* " + label + " failed(on-demand,region): no path. expanded=" + expanded + " visited=" + all.size() +
				" corridor=[x:" + minX + ".." + maxX + ", z:" + minZ + ".." + maxZ + "]" +
				" start=" + w.getName() + "(" + start.x + "," + start.y + "," + start.z + ")" +
				" goalRegionWorld=" + safe(goalRegion.getWorldName()));
		return null;
	}

	private static void pruneLocalSelfLoops(
			List<Location> centerline,
			Region finish,
			java.util.List<Region> checkpoints,
			Logger logger,
			boolean verbose
	) {
		if (centerline == null || centerline.size() < 4)
			return;

		java.util.ArrayList<Location> out = new java.util.ArrayList<>(centerline.size());
		java.util.HashMap<Long, Integer> seen = new java.util.HashMap<>();

		int removed = 0;
		for (Location p : centerline) {
			if (p == null || p.getWorld() == null)
				continue;

			long k = (((long) p.getBlockX()) << 32) ^ (p.getBlockZ() & 0xffffffffL);
			Integer prevIdx = seen.get(k);
			if (prevIdx != null) {
				int loopLen = out.size() - prevIdx;
				// Ignore tiny duplicates (decimation/endpoints can create these).
				if (loopLen <= 3)
					continue;
				// Only prune meaningful loops.
				if (loopLen >= 10) {
					boolean touchesGate = false;
					for (int i = prevIdx; i < out.size(); i++) {
						Location q = out.get(i);
						if (q == null || q.getWorld() == null)
							continue;
						if (isInAnyGateRegionXZ(q, finish, checkpoints)) {
							touchesGate = true;
							break;
						}
					}
					if (!touchesGate) {
						// Remove the detour between prevIdx..end (keep the earlier node).
						for (int i = out.size() - 1; i > prevIdx; i--) {
							Location rm = out.remove(i);
							if (rm != null) {
								long rk = (((long) rm.getBlockX()) << 32) ^ (rm.getBlockZ() & 0xffffffffL);
								seen.remove(rk);
							}
							removed++;
						}
						continue;
					}
				}

				// If we can't prune, keep going but don't add duplicates.
				continue;
			}

			seen.put(k, out.size());
			out.add(p);
		}

		if (removed > 0) {
			centerline.clear();
			centerline.addAll(out);
			if (verbose) info(logger, "Centerline prune: removedNodes=" + removed + " finalNodes=" + centerline.size());
		}
	}

	private static boolean isInAnyGateRegionXZ(Location p, Region finish, java.util.List<Region> checkpoints) {
		if (p == null || p.getWorld() == null)
			return false;
		try {
			if (finish != null && finish.containsXZ(p))
				return true;
		} catch (Throwable ignored) {}
		if (checkpoints != null) {
			for (Region cp : checkpoints) {
				if (cp == null)
					continue;
				try {
					if (cp.containsXZ(p))
						return true;
				} catch (Throwable ignored) {}
			}
		}
		return false;
	}

	private static void ensureStartEndAtFinish(List<Location> centerline, Region finish, Location finishCenter, Logger logger, boolean verbose) {
		if (centerline == null || centerline.isEmpty() || finish == null) return;
		if (finishCenter == null) {
			try { finishCenter = centerOf(finish); } catch (Throwable ignored) { finishCenter = null; }
		}
		if (finishCenter == null) return;
		World w = finishCenter.getWorld();
		if (w == null) return;

		// Prefer an anchor node that is actually inside the finish region (so we don't create fake straight segments).
		int bestIdx = -1;
		double bestD = Double.POSITIVE_INFINITY;
		for (int i = 0; i < centerline.size(); i++) {
			Location p = centerline.get(i);
			if (p == null || p.getWorld() == null || !p.getWorld().equals(w)) continue;
			boolean in = false;
			try { in = finish.containsXZ(p); } catch (Throwable ignored) { in = false; }
			if (!in) continue;
			double dx = p.getX() - finishCenter.getX();
			double dz = p.getZ() - finishCenter.getZ();
			double d = dx * dx + dz * dz;
			if (d < bestD) {
				bestD = d;
				bestIdx = i;
			}
		}

		// Fallback: closest to finish center (if path doesn't enter the finish region for some reason).
		if (bestIdx < 0) {
			bestIdx = 0;
			bestD = Double.POSITIVE_INFINITY;
			for (int i = 0; i < centerline.size(); i++) {
				Location p = centerline.get(i);
				if (p == null || p.getWorld() == null || !p.getWorld().equals(w)) continue;
				double dx = p.getX() - finishCenter.getX();
				double dz = p.getZ() - finishCenter.getZ();
				double d = dx * dx + dz * dz;
				if (d < bestD) {
					bestD = d;
					bestIdx = i;
				}
			}
		}

		if (bestIdx != 0) {
			java.util.ArrayList<Location> rotated = new java.util.ArrayList<>(centerline.size());
			rotated.addAll(centerline.subList(bestIdx, centerline.size()));
			rotated.addAll(centerline.subList(0, bestIdx));
			centerline.clear();
			centerline.addAll(rotated);
		}

		// Ensure explicit closure at the exact same coordinate as the first node.
		// IMPORTANT: Do NOT blindly append/replace the last node with the first node, because that can
		// create a huge closing segment (rendered as a diagonal "jump" on the minimap).
		Location first = centerline.get(0);
		if (first == null || first.getWorld() == null) return;
		Location last = centerline.get(centerline.size() - 1);
		if (last == null || last.getWorld() == null || !last.getWorld().equals(first.getWorld())) return;

		double dx = last.getX() - first.getX();
		double dy = last.getY() - first.getY();
		double dz = last.getZ() - first.getZ();
		double distSq3 = dx * dx + dy * dy + dz * dz;
		double distSqXZ = dx * dx + dz * dz;

		// Already exactly closed -> normalize the last node to be a clone of the first.
		if (distSq3 <= 1.0e-9) {
			centerline.set(centerline.size() - 1, first.clone());
			return;
		}

		// If the seam is small, just snap it (avoids tiny gaps from surface snapping).
		// Use a small threshold so we never introduce an artificial long connector.
		final double snapSq = 0.35 * 0.35;
		if (distSqXZ <= snapSq) {
			centerline.add(first.clone());
			return;
		}

		// Seam is non-trivial: try closing on-surface.
		boolean closed = false;
		try {
			closed = tryStitchLoopOnSurface(centerline, last, first, logger, verbose);
		} catch (Throwable ignored) { closed = false; }

		if (!closed) {
			// Run A* to close the loop on allowed surface instead of creating a fake straight segment.
			// IMPORTANT: constrain the search to a local area around the finish.
			// Using global bounds can cause A* to take the entire course again (a "second lap") if the
			// immediate seam near finish isn't walkable for whatever reason.
			try {
				int fx = finishCenter.getBlockX();
				int fz = finishCenter.getBlockZ();
				int pad = 24;
				int minX = fx - pad;
				int maxX = fx + pad;
				int minZ = fz - pad;
				int maxZ = fz + pad;

				List<Location> segment = aStar2DWithRetries(last, first, 12, 32, minX, maxX, minZ, maxZ, logger, "segment#close(finish)", verbose);
				if (segment != null && !segment.isEmpty()) {
					appendDecimated(centerline, segment, 0.5);
					closed = true;
				}
			} catch (Throwable ignored) { closed = false; }
		}

		// If closure succeeded, ensure the end is exactly the start (no epsilon drift).
		if (closed) {
			Location end = centerline.get(centerline.size() - 1);
			if (end != null && end.getWorld() != null && end.getWorld().equals(first.getWorld())) {
				double ex = end.getX() - first.getX();
				double ey = end.getY() - first.getY();
				double ez = end.getZ() - first.getZ();
				if ((ex * ex + ey * ey + ez * ez) <= 0.25) {
					centerline.add(first.clone());
				}
			}
		} else if (verbose) {
			warn(logger, "Centerline: skip forced closure to avoid diagonal jump (seamDistSqXZ=" + String.format(java.util.Locale.ROOT, "%.3f", distSqXZ) + ")");
		}
	}

	private static Location centerOf(Region r) {
		BoundingBox b = r.getBox();
		World w = Bukkit.getWorld(r.getWorldName());
		return new Location(w, b.getCenterX(), b.getCenterY(), b.getCenterZ());
	}

	/**
	 * Pick an anchor point inside a region (finish/checkpoint) on allowed surface.
	 * Uses prev/next hints to keep the centerline consistent (avoids parallel "two lines").
	 */
	private static Location pickAnchorInRegion(Region r, Location prev, Location next, Logger logger, String label, boolean verbose) {
		if (r == null || r.getBox() == null) return null;
		World w = Bukkit.getWorld(r.getWorldName());
		if (w == null) {
			warn(logger, "Centerline: region world not loaded for " + label + " (world=" + safe(r.getWorldName()) + ")");
			return null;
		}

		BoundingBox b = r.getBox();
		double minXf = Math.min(b.getMinX(), b.getMaxX());
		double maxXf = Math.max(b.getMinX(), b.getMaxX());
		double minZf = Math.min(b.getMinZ(), b.getMaxZ());
		double maxZf = Math.max(b.getMinZ(), b.getMaxZ());
		int minX = (int) Math.floor(minXf);
		int maxX = (int) Math.floor(maxXf);
		int minZ = (int) Math.floor(minZf);
		int maxZ = (int) Math.floor(maxZf);

		int width = Math.max(1, (maxX - minX + 1));
		int height = Math.max(1, (maxZ - minZ + 1));
		long area = (long) width * (long) height;
		int step = 1;
		if (area > 16_384L) step = 4;
		else if (area > 4_096L) step = 2;

		// Determine a reasonable Y hint.
		int yHint = (int) Math.floor(b.getCenterY());
		try {
			if (prev != null && prev.getWorld() != null && prev.getWorld().equals(w)) yHint = prev.getBlockY() - 1;
			else if (next != null && next.getWorld() != null && next.getWorld().equals(w)) yHint = next.getBlockY() - 1;
		} catch (Throwable ignored) {}
		if (yHint < w.getMinHeight()) yHint = w.getMinHeight();
		if (yHint > w.getMaxHeight() - 1) yHint = w.getMaxHeight() - 1;

		double cx = b.getCenterX();
		double cz = b.getCenterZ();
		double bestScore = Double.POSITIVE_INFINITY;
		int bestX = Integer.MIN_VALUE;
		int bestZ = Integer.MIN_VALUE;
		int bestY = Integer.MIN_VALUE;
		java.util.Map<Long, Integer> surfaceCache = new java.util.HashMap<>();

		for (int x = minX; x <= maxX; x += step) {
			for (int z = minZ; z <= maxZ; z += step) {
				int surfaceY = cachedSurfaceY(w, x, z, yHint, 8, surfaceCache);
				if (surfaceY == Integer.MIN_VALUE) continue;
				if (!isClearAbove(w, x, surfaceY, z)) continue;
				Material t = w.getBlockAt(x, surfaceY, z).getType();
				if (!ALLOWED.contains(t)) continue;

				double px = x + 0.5;
				double pz = z + 0.5;
				double score = 0.0;

				if (prev != null && prev.getWorld() != null && prev.getWorld().equals(w)) {
					double dx = px - prev.getX();
					double dz = pz - prev.getZ();
					score += (dx * dx + dz * dz);
				}
				if (next != null && next.getWorld() != null && next.getWorld().equals(w)) {
					double dx = px - next.getX();
					double dz = pz - next.getZ();
					score += 0.25 * (dx * dx + dz * dz);
				}

				// Gentle pull toward region center to avoid hugging edges when hints are far.
				double dcx = px - cx;
				double dcz = pz - cz;
				score += 0.02 * (dcx * dcx + dcz * dcz);

				if (score < bestScore) {
					bestScore = score;
					bestX = x;
					bestZ = z;
					bestY = surfaceY;
				} else if (score == bestScore) {
					// Deterministic tie-break.
					if (x < bestX || (x == bestX && z < bestZ)) {
						bestX = x;
						bestZ = z;
						bestY = surfaceY;
					}
				}
			}
		}

		if (bestY == Integer.MIN_VALUE) {
			if (verbose) warn(logger, "Centerline: no surface anchor found inside " + label + " region; falling back to center");
			return null;
		}

		Location out = new Location(w, bestX + 0.5, bestY + 1.0, bestZ + 0.5);
		if (verbose) info(logger, "Anchor " + label + ": x=" + bestX + " z=" + bestZ + " y=" + bestY + " -> " + fmt(out));
		return out;
	}

	/**
	 * Return a small ordered list of viable anchors inside a region.
	 * Used to re-pick checkpoint anchors when the initial anchor causes a global detour.
	 */
	private static java.util.List<Location> listAnchorCandidatesInRegion(
			Region r,
			Location prev,
			Location next,
			int maxCandidates,
			Logger logger,
			String label,
			boolean verbose
	) {
		if (r == null || r.getBox() == null || maxCandidates <= 0)
			return java.util.Collections.emptyList();
		World w = Bukkit.getWorld(r.getWorldName());
		if (w == null)
			return java.util.Collections.emptyList();

		BoundingBox b = r.getBox();
		double minXf = Math.min(b.getMinX(), b.getMaxX());
		double maxXf = Math.max(b.getMinX(), b.getMaxX());
		double minZf = Math.min(b.getMinZ(), b.getMaxZ());
		double maxZf = Math.max(b.getMinZ(), b.getMaxZ());
		int minX = (int) Math.floor(minXf);
		int maxX = (int) Math.floor(maxXf);
		int minZ = (int) Math.floor(minZf);
		int maxZ = (int) Math.floor(maxZf);

		int width = Math.max(1, (maxX - minX + 1));
		int height = Math.max(1, (maxZ - minZ + 1));
		long area = (long) width * (long) height;
		int step = 1;
		if (area > 16_384L) step = 4;
		else if (area > 4_096L) step = 2;

		int yHint = (int) Math.floor(b.getCenterY());
		try {
			if (prev != null && prev.getWorld() != null && prev.getWorld().equals(w)) yHint = prev.getBlockY() - 1;
			else if (next != null && next.getWorld() != null && next.getWorld().equals(w)) yHint = next.getBlockY() - 1;
		} catch (Throwable ignored) {}
		if (yHint < w.getMinHeight()) yHint = w.getMinHeight();
		if (yHint > w.getMaxHeight() - 1) yHint = w.getMaxHeight() - 1;

		double cx = b.getCenterX();
		double cz = b.getCenterZ();
		java.util.Map<Long, Integer> surfaceCache = new java.util.HashMap<>();
		java.util.ArrayList<long[]> scored = new java.util.ArrayList<>();

		for (int x = minX; x <= maxX; x += step) {
			for (int z = minZ; z <= maxZ; z += step) {
				int surfaceY = cachedSurfaceY(w, x, z, yHint, 8, surfaceCache);
				if (surfaceY == Integer.MIN_VALUE) continue;
				if (!isClearAbove(w, x, surfaceY, z)) continue;
				Material t = w.getBlockAt(x, surfaceY, z).getType();
				if (!ALLOWED.contains(t)) continue;

				double px = x + 0.5;
				double pz = z + 0.5;
				double score = 0.0;
				if (prev != null && prev.getWorld() != null && prev.getWorld().equals(w)) {
					double dx = px - prev.getX();
					double dz = pz - prev.getZ();
					score += (dx * dx + dz * dz);
				}
				if (next != null && next.getWorld() != null && next.getWorld().equals(w)) {
					double dx = px - next.getX();
					double dz = pz - next.getZ();
					score += 0.25 * (dx * dx + dz * dz);
				}
				double dcx = px - cx;
				double dcz = pz - cz;
				score += 0.02 * (dcx * dcx + dcz * dcz);

				long packed = (((long) x) & 0x3FFFFFL) << 42;
				packed |= (((long) z) & 0x3FFFFFL) << 20;
				packed |= ((long) surfaceY) & 0xFFFFFL;
				long sBits = Double.doubleToRawLongBits(score);
				scored.add(new long[] { sBits, packed });
			}
		}

		if (scored.isEmpty())
			return java.util.Collections.emptyList();

		scored.sort((a, bArr) -> {
			double sa = Double.longBitsToDouble(a[0]);
			double sb = Double.longBitsToDouble(bArr[0]);
			int c = Double.compare(sa, sb);
			if (c != 0) return c;
			// Stable deterministic tie-break by packed coordinate.
			return Long.compare(a[1], bArr[1]);
		});

		int outN = Math.min(maxCandidates, scored.size());
		java.util.ArrayList<Location> out = new java.util.ArrayList<>(outN);
		for (int i = 0; i < outN; i++) {
			long packed = scored.get(i)[1];
			int x = (int) ((packed >> 42) & 0x3FFFFFL);
			int z = (int) ((packed >> 20) & 0x3FFFFFL);
			int y = (int) (packed & 0xFFFFFL);
			// Restore signed values (stored in 22-bit for x/z).
			if (x >= (1 << 21)) x -= (1 << 22);
			if (z >= (1 << 21)) z -= (1 << 22);
			out.add(new Location(w, x + 0.5, y + 1.0, z + 0.5));
		}

		if (verbose && !out.isEmpty())
			info(logger, "Anchor candidates for " + label + ": " + out.size());
		return out;
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

	// Same as aStar2DWithRetries, but NEVER falls back to global bounds.
	private static List<Location> aStar2DWithRetriesLocalOnly(
			Location a,
			Location b,
			int baseMargin,
			int maxMargin,
			Logger logger,
			String label,
			boolean verbose
	) {
		int margin = Math.max(0, baseMargin);
		int cap = Math.max(margin, maxMargin);
		while (true) {
			if (verbose) info(logger, "A* " + label + " attempt: margin=" + margin);
			List<Location> result = aStar2D(a, b, margin, logger, label, verbose);
			if (result != null && !result.isEmpty()) return result;
			if (margin >= cap) break;
			margin = (margin == 0) ? 8 : Math.min(cap, margin * 2);
		}
		return null;
	}

	private static boolean isSuspiciousSegmentDetour(Location a, Location b, java.util.List<Location> path) {
		if (a == null || b == null || path == null || path.isEmpty())
			return false;
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		// Heuristic: a segment shouldn't be "many laps" longer than straight-line.
		// Allows some curvature but rejects accidental whole-track detours.
		int maxNodes = (int) Math.ceil((dist * 6.0) + 200.0);
		if (maxNodes < 400) maxNodes = 400;
		return path.size() > maxNodes;
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
			if (verbose) warn(logger, "A* " + label + " switching to on-demand search (grid too large)");
			return aStar2DInBoundsOnDemand(w, start, goal, minX, maxX, minZ, maxZ, logger, label, verbose);
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

	private static List<Location> aStar2DInBoundsOnDemand(
			World w,
			Node start,
			Node goal,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			Logger logger,
			String label,
			boolean verbose
	) {
		java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		java.util.Map<Long, Node> all = new java.util.HashMap<>();
		java.util.Map<Long, Integer> surfaceCache = new java.util.HashMap<>();

		start.g = 0.0;
		start.f = start.g + h(start, goal);
		open.add(start);
		all.put(key(start.x, start.y, start.z), start);

		int expanded = 0;
		final int maxStepUpDown = 2;
		final int maxExpanded = 200_000;
		final long startNs = System.nanoTime();
		final long maxNs = 600_000_000L; // 600ms budget to avoid long main-thread stalls

		while (!open.isEmpty()) {
			Node cur = open.poll();
			if (cur.x == goal.x && cur.z == goal.z && Math.abs(cur.y - goal.y) <= maxStepUpDown) {
				if (verbose) info(logger, "A* " + label + " success(on-demand): expanded=" + expanded + " visited=" + all.size());
				return reconstruct(w, cur);
			}
			cur.closed = true;
			expanded++;
			if (expanded >= maxExpanded) {
				warn(logger, "A* " + label + " failed(on-demand): expansion cap reached (" + maxExpanded + ")");
				return null;
			}
			if ((expanded & 0x3FF) == 0 && (System.nanoTime() - startNs) > maxNs) {
				warn(logger, "A* " + label + " failed(on-demand): time budget exceeded (" + (maxNs / 1_000_000L) + "ms)");
				return null;
			}

			for (int[] d : DIRS) {
				int nx = cur.x + d[0];
				int nz = cur.z + d[1];
				double stepCost = d[2] / 10.0;
				if (nx < minX || nx > maxX || nz < minZ || nz > maxZ)
					continue;

				int ny = cachedSurfaceY(w, nx, nz, cur.y, 12, surfaceCache);
				if (ny == Integer.MIN_VALUE)
					continue;
				if (!isClearAbove(w, nx, ny, nz))
					continue;
				Material t = w.getBlockAt(nx, ny, nz).getType();
				if (!ALLOWED.contains(t))
					continue;
				if (Math.abs(ny - cur.y) > maxStepUpDown)
					continue;

				long k = key(nx, ny, nz);
				Node nb = all.get(k);
				if (nb == null) {
					nb = new Node(nx, ny, nz);
					all.put(k, nb);
				}
				if (nb.closed)
					continue;

				int dy = Math.abs(ny - cur.y);
				int distToCorridorEdge = Math.min(Math.min(nx - minX, maxX - nx), Math.min(nz - minZ, maxZ - nz));
				if (distToCorridorEdge < 0)
					distToCorridorEdge = 0;
				double centerPenalty = CENTER_BIAS / (distToCorridorEdge + 1.0);
				double tg = cur.g + stepCost + (0.2 * dy) + centerPenalty;
				if (tg < nb.g) {
					nb.parent = cur;
					nb.g = tg;
					nb.f = tg + h(nb, goal);
					open.remove(nb);
					open.add(nb);
				}
			}
		}

		warn(logger, "A* " + label + " failed(on-demand): no path. expanded=" + expanded + " visited=" + all.size() +
				" corridor=[x:" + minX + ".." + maxX + ", z:" + minZ + ".." + maxZ + "]" +
				" start=" + w.getName() + "(" + start.x + "," + start.y + "," + start.z + ")" +
				" goal=" + w.getName() + "(" + goal.x + "," + goal.y + "," + goal.z + ")" +
				" allowed=" + ALLOWED);
		return null;
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
				// Caller must fall back to an on-demand search to avoid allocating a massive grid.
				return null;
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
		if (dst == null || src == null || src.isEmpty())
			return;

		final double minStepSq = minStep * minStep;
		Location last = dst.isEmpty() ? null : dst.get(dst.size() - 1);

		// Always keep the first node of the segment unless it's effectively a duplicate of the previous.
		Location first = src.get(0);
		if (first != null) {
			if (last == null || first.distanceSquared(last) > 1.0e-6) {
				dst.add(first);
				last = first;
			}
		}

		// Decimate interior nodes.
		for (int i = 1; i < src.size() - 1; i++) {
			Location l = src.get(i);
			if (l == null)
				continue;
			if (last == null || l.distanceSquared(last) >= minStepSq) {
				dst.add(l);
				last = l;
			}
		}

		// Always keep the last node of the segment (critical for finish/loop closure).
		if (src.size() >= 2) {
			Location end = src.get(src.size() - 1);
			if (end != null) {
				if (last == null || end.distanceSquared(last) > 1.0e-6) {
					dst.add(end);
				}
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

