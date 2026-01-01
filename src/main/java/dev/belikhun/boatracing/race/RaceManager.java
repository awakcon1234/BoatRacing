package dev.belikhun.boatracing.race;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.cinematic.CinematicCameraService;
import dev.belikhun.boatracing.cinematic.CinematicKeyframe;
import dev.belikhun.boatracing.cinematic.CinematicSequence;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.track.Region;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Race manager with minimal yet functional placement and countdown.
 */
public class RaceManager {
	private Plugin plugin;
	@SuppressWarnings("unused")
	private final TrackConfig trackConfig;

	// Stable id for tagging world entities belonging to this race/track (used
	// across reloads).
	// TrackConfig.currentName is set by TrackConfig.load(name).
	private final String trackId;

	// Cached keys (avoid re-allocating NamespacedKey objects in hot paths).
	private final NamespacedKey keySpawnedBoat;
	private final NamespacedKey keyCheckpointDisplay;
	private final NamespacedKey keyCheckpointDisplayIndex;
	private final NamespacedKey keyCheckpointDisplayTrack;

	// Dashboard caches (reduce per-tick allocations/recalcs).
	private final java.util.Set<UUID> dashboardActiveTmp = new java.util.LinkedHashSet<>();
	private final java.util.List<UUID> dashboardRemoveTmp = new java.util.ArrayList<>();
	private int dashboardBarCacheCells = -1;
	private String[] dashboardBarCacheRed;
	private String[] dashboardBarCacheYellow;
	private String[] dashboardBarCacheGreen;
	private int dashboardYellowKmhCached = 5;
	private int dashboardGreenKmhCached = 20;
	private long dashboardThresholdsNextRefreshMs = 0L;
	private int dashboardBarCellsCached = 30;
	private long dashboardBarCellsNextRefreshMs = 0L;

	// Reused list for removing offline racers in race ticker.
	private final java.util.List<UUID> offlineRacerTmp = new java.util.ArrayList<>();
	private boolean running = false;
	private boolean registering = false;
	private final Set<UUID> registered = new HashSet<>();
	private int totalLaps = 3;
	// Pit mechanic removed: no mandatory pitstops

	// runtime participant state
	private final java.util.Map<UUID, ParticipantState> participants = new java.util.HashMap<>();
	private final java.util.Map<UUID, Player> participantPlayers = new java.util.HashMap<>();
	private final java.util.Map<UUID, UUID> spawnedBoatByPlayer = new java.util.HashMap<>();
	private final java.util.Map<UUID, org.bukkit.GameMode> previousGameModes = new java.util.HashMap<>();
	private final java.util.Set<UUID> countdownPlayers = new java.util.HashSet<>();
	private long raceStartMillis = 0L;
	// Total racers that started this race instance (used for win qualification).
	private int raceStartRacerCount = 0;
	// Countdown end (millis) for the start countdown; 0 if no countdown active
	private volatile long countdownEndMillis = 0L;
	// Waiting end (millis) for registration waiting phase; 0 if none
	private volatile long waitingEndMillis = 0L;
	// Intro end (millis) for the pre-race cinematic; 0 if none
	private volatile long introEndMillis = 0L;
	private final java.util.Set<UUID> introPlayers = new java.util.HashSet<>();
	// Post-finish cleanup end (millis) after everyone finished; 0 if not in ending
	// window
	private volatile long postFinishCleanupEndMillis = 0L;

	// Centerline-based live position
	private java.util.List<org.bukkit.Location> path = java.util.Collections.emptyList();
	private int[] gateIndex = new int[0]; // indices along path for each checkpoint and finish
	private boolean pathReady = false;

	// Arc-length cache along the centerline path (used for track-following distances).
	// arcCum[i] = distance along path from index 0 to i (forward, without wrap).
	private double[] arcCum = new double[0];
	private double arcLapLength = 0.0;
	private boolean arcReady = false;

	private BukkitRunnable raceTickTask;
	private BukkitTask registrationStartTask;
	private BukkitRunnable countdownTask;
	private BukkitRunnable countdownFreezeTask;
	private BukkitTask startLightsBlinkTask;
	private BukkitTask postFinishCleanupTask;
	private BukkitTask allFinishedFireworksTask;
	private BukkitTask resultsTopBoardTask;
	private BukkitTask resultsRestBoardTask;
	private BukkitTask dashboardTask;
	private final java.util.Map<UUID, org.bukkit.entity.TextDisplay> dashboardByPlayer = new java.util.HashMap<>();
	private final java.util.Map<UUID, org.bukkit.Location> dashboardLastBoatLoc = new java.util.HashMap<>();
	private final java.util.Map<UUID, Long> dashboardLastBoatLocTime = new java.util.HashMap<>();
	private final java.util.Map<UUID, Double> dashboardLastBps = new java.util.HashMap<>();
	private final java.util.Map<UUID, Integer> dashboardStationaryTicks = new java.util.HashMap<>();
	private final java.util.Map<UUID, org.bukkit.Location> countdownLockLocation = new java.util.HashMap<>();
	private final java.util.Map<UUID, Long> countdownDebugLastLog = new java.util.HashMap<>();
	private final java.util.Map<Block, BlockData> countdownBarrierRestore = new java.util.HashMap<>();

	private void cancelResultsBoards() {
		if (resultsTopBoardTask != null) {
			try {
				resultsTopBoardTask.cancel();
			} catch (Throwable ignored) {
			}
			resultsTopBoardTask = null;
		}
		if (resultsRestBoardTask != null) {
			try {
				resultsRestBoardTask.cancel();
			} catch (Throwable ignored) {
			}
			resultsRestBoardTask = null;
		}
	}

	private static String placementTagLegacy(int pos) {
		int p = Math.max(1, pos);
		// Minecraft-safe: use colored #n instead of medal emojis.
		return switch (p) {
			case 1 -> "&6#1";
			case 2 -> "&7#2";
			case 3 -> "&c#3";
			default -> "&f#" + p;
		};
	}

	private java.util.List<String> buildResultsTop3BoardLines(java.util.List<ParticipantState> standings) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		String track = safeTrackName();
		lines.add("&6&l┏━━━━━━ &eBẢNG XẾP HẠNG &6&l━━━━━━┓");
		lines.add("&7Đường đua: &f" + track + " &8● &7Số vòng: &f" + getTotalLaps());
		lines.add("&eTop 3");

		int shown = 0;
		for (ParticipantState s : standings) {
			if (s == null || !s.finished)
				continue;
			int place = s.finishPosition > 0 ? s.finishPosition : (shown + 1);
			if (place > 3)
				continue;
			long rawMs = Math.max(0L, s.finishTimeMillis - getRaceStartMillis());
			long penaltyMs = Math.max(0L, s.penaltySeconds) * 1000L;
			long totalMs = rawMs + penaltyMs;
			String racer = racerDisplayLegacy(s.id, null);
			lines.add(placementTagLegacy(place) + " &f" + racer + " &8● &7⌚ &f" + Time.formatStopwatchMillis(totalMs));
			shown++;
			if (shown >= 3)
				break;
		}

		if (shown == 0) {
			lines.add("&7Không có dữ liệu xếp hạng.");
		}
		lines.add("&6&l┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛");
		return lines;
	}

	private java.util.List<String> buildResultsRestBoardLines(java.util.List<ParticipantState> standings) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		String track = safeTrackName();
		lines.add("&6&l┏━━━━━━ &eXẾP HẠNG #4+ &6&l━━━━━━┓");
		lines.add("&7Đường đua: &f" + track + " &8● &7⌚ &f" + Time.formatStopwatchMillis(getRaceElapsedMillis()));

		java.util.List<String> entries = new java.util.ArrayList<>();
		for (ParticipantState s : standings) {
			if (s == null || !s.finished)
				continue;
			int place = s.finishPosition > 0 ? s.finishPosition : 0;
			if (place > 0 && place <= 3)
				continue;
			if (place <= 0)
				continue;

			long rawMs = Math.max(0L, s.finishTimeMillis - getRaceStartMillis());
			long penaltyMs = Math.max(0L, s.penaltySeconds) * 1000L;
			long totalMs = rawMs + penaltyMs;
			String racer = racerDisplayLegacy(s.id, null);
			entries.add("&7" + placementTagLegacy(place) + " &f" + racer + " &8(⌚ &f" + Time.formatStopwatchMillis(totalMs) + "&8)");
		}

		if (entries.isEmpty()) {
			lines.add("&7Không có tay đua nào ngoài top 3.");
			lines.add("&6&l┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛");
			return lines;
		}

		// Table layout: 2 racers per line (safer length-wise); last line may have 1.
		for (int i = 0; i < entries.size(); i += 2) {
			String a = entries.get(i);
			String b = (i + 1 < entries.size()) ? entries.get(i + 1) : null;
			if (b != null)
				lines.add(a + " &8● " + b);
			else
				lines.add(a);
		}

		lines.add("&6&l┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛");
		return lines;
	}

	private void broadcastChatBoard(java.util.List<String> lines) {
		if (lines == null || lines.isEmpty())
			return;
		for (UUID id : getInvolved()) {
			try {
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;
				for (String line : lines) {
					Text.tell(p, line);
				}
			} catch (Throwable ignored) {
				}
				// End of try-catch block
		}
	}

	// Checkpoint markers: rotating ItemDisplay + TextDisplay label at each
	// checkpoint
	private final java.util.List<org.bukkit.entity.Display> checkpointDisplays = new java.util.ArrayList<>();
	private BukkitTask checkpointDisplayTask;
	private long checkpointDisplayTick = 0L;
	private final org.joml.Quaternionf checkpointSpin = new org.joml.Quaternionf();
	// NOTE: We intentionally do not use Boat physics setters
	// (maxSpeed/deceleration/workOnLand)
	// because they are deprecated in modern Paper. Countdown freezing is enforced
	// via snapping.

	private static final class PreferredBoatData {
		final boolean chest;
		final boolean raft;
		final String baseType; // Boat.Type name (e.g. OAK, SPRUCE, BAMBOO)
		final String raw;

		PreferredBoatData(boolean chest, boolean raft, String baseType, String raw) {
			this.chest = chest;
			this.raft = raft;
			this.baseType = baseType;
			this.raw = raw;
		}
	}

	// Debug helpers
	private boolean debugTeleport() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("racing.debug.teleport", false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean debugCheckpoints() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("racing.debug.checkpoints", false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean debugCountdownFreeze() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("racing.debug.countdown-freeze", false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean debugBoatSelection() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("racing.debug.boat-selection", false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean debugDashboard() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("racing.debug.dashboard", false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private final java.util.Map<UUID, Long> dashboardDebugLastLog = new java.util.HashMap<>();
	private final java.util.Set<UUID> dashboardDisabledPlayers = new java.util.HashSet<>();

	private boolean countdownBarriersEnabled() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("racing.countdown.barrier.enabled", true);
		} catch (Throwable ignored) {
			return true;
		}
	}

	private PreferredBoatData resolvePreferredBoat(UUID id) {
		if (id == null)
			return new PreferredBoatData(false, false, null, null);
		if (!(plugin instanceof dev.belikhun.boatracing.BoatRacingPlugin br) || br.getProfileManager() == null) {
			return new PreferredBoatData(false, false, null, null);
		}
		String bt;
		try {
			bt = br.getProfileManager().getBoatType(id);
		} catch (Throwable ignored) {
			bt = null;
		}

		if (bt == null || bt.isBlank())
			return new PreferredBoatData(false, false, null, null);

		final String raw = bt;

		Material pref = null;
		try {
			String norm = bt.trim().toUpperCase(java.util.Locale.ROOT);
			try {
				pref = Material.valueOf(norm);
			} catch (IllegalArgumentException ignored) {
				pref = null;
			}
			if (pref == null)
				pref = Material.matchMaterial(bt);
		} catch (Throwable ignored) {
			pref = null;
		}

		boolean chest = false;
		boolean raft = false;
		String base = null;

		if (pref != null) {
			chest = pref.name().endsWith("_CHEST_BOAT") || pref.name().endsWith("_CHEST_RAFT");
			raft = pref.name().endsWith("_RAFT") || pref.name().endsWith("_CHEST_RAFT");
			base = pref.name()
					.replace("_CHEST_BOAT", "").replace("_BOAT", "")
					.replace("_CHEST_RAFT", "").replace("_RAFT", "");
		} else {
			// Back-compat: accept older stored values like "OAK" or "SPRUCE".
			String norm = bt.trim().toUpperCase(java.util.Locale.ROOT);
			chest = norm.endsWith("_CHEST_BOAT") || norm.endsWith("_CHEST_RAFT") || norm.contains("CHEST_BOAT")
					|| norm.contains("CHEST_RAFT");
			raft = norm.endsWith("_RAFT") || norm.endsWith("_CHEST_RAFT") || norm.contains("RAFT");
			try {
				base = org.bukkit.entity.Boat.Type.valueOf(norm).name();
			} catch (IllegalArgumentException ignored) {
				base = null;
			}
		}

		if (debugBoatSelection()) {
			try {
				dbg("[BOATDBG] resolvePreferredBoat player=" + id
						+ " raw='" + raw + "' material=" + (pref == null ? "null" : pref.name())
						+ " chest=" + chest + " raft=" + raft + " base=" + base);
			} catch (Throwable ignored) {
			}
		}

		return new PreferredBoatData(chest, raft, base, raw);
	}

	private static EntityType resolveSpawnEntityType(PreferredBoatData pref) {
		boolean chest = pref != null && pref.chest;
		boolean raft = pref != null && pref.raft;
		String base = pref != null ? pref.baseType : null;

		// Modern Paper exposes per-variant entity types (e.g. OAK_BOAT, OAK_CHEST_BOAT,
		// BAMBOO_RAFT, BAMBOO_CHEST_RAFT).
		// Prefer spawning the exact entity type to avoid defaulting to OAK.
		if (base != null && !base.isBlank()) {
			String candidate = null;

			// Bamboo uses RAFT variants instead of BOAT variants in modern Minecraft.
			if (raft || "BAMBOO".equalsIgnoreCase(base)) {
				candidate = "BAMBOO_" + (chest ? "CHEST_RAFT" : "RAFT");
			} else {
				candidate = base.toUpperCase(java.util.Locale.ROOT) + (chest ? "_CHEST_BOAT" : "_BOAT");
			}

			try {
				return EntityType.valueOf(candidate);
			} catch (Throwable ignored) {
			}
		}

		// Fallbacks (older API servers may still have BOAT/CHEST_BOAT only).
		try {
			return EntityType.valueOf(chest ? "CHEST_BOAT" : "BOAT");
		} catch (Throwable ignored) {
		}

		// Last resort: OAK variants exist virtually everywhere.
		try {
			return EntityType.valueOf(chest ? "OAK_CHEST_BOAT" : "OAK_BOAT");
		} catch (Throwable ignored) {
			return EntityType.BOAT;
		}
	}

	private static boolean isBoatLike(Entity e) {
		if (e == null)
			return false;
		if (e instanceof org.bukkit.entity.Boat || e instanceof org.bukkit.entity.ChestBoat)
			return true;
		try {
			String t = e.getType() != null ? e.getType().name() : null;
			if (t == null)
				return false;
			return t.endsWith("_BOAT") || t.endsWith("_CHEST_BOAT") || t.endsWith("_RAFT") || t.endsWith("_CHEST_RAFT")
					|| t.equals("BOAT") || t.equals("CHEST_BOAT");
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static BlockFace yawToFace(float yaw) {
		float y = yaw;
		y = (y % 360.0f + 360.0f) % 360.0f;
		// Vanilla-ish mapping: 0=south, 90=west, 180=north, 270=east
		if (y >= 315.0f || y < 45.0f)
			return BlockFace.SOUTH;
		if (y < 135.0f)
			return BlockFace.WEST;
		if (y < 225.0f)
			return BlockFace.NORTH;
		return BlockFace.EAST;
	}

	private static BlockFace rotateLeft(BlockFace f) {
		return switch (f) {
			case NORTH -> BlockFace.WEST;
			case WEST -> BlockFace.SOUTH;
			case SOUTH -> BlockFace.EAST;
			case EAST -> BlockFace.NORTH;
			default -> BlockFace.WEST;
		};
	}

	private static BlockFace rotateRight(BlockFace f) {
		return switch (f) {
			case NORTH -> BlockFace.EAST;
			case EAST -> BlockFace.SOUTH;
			case SOUTH -> BlockFace.WEST;
			case WEST -> BlockFace.NORTH;
			default -> BlockFace.EAST;
		};
	}

	private void clearCountdownBarriers() {
		if (countdownBarrierRestore.isEmpty())
			return;
		for (var e : new java.util.HashMap<>(countdownBarrierRestore).entrySet()) {
			try {
				Block b = e.getKey();
				BlockData prev = e.getValue();
				if (b != null && prev != null) {
					b.setBlockData(prev, false);
				}
			} catch (Throwable ignored) {
			}
		}
		countdownBarrierRestore.clear();
	}

	private void placeCountdownBarriers() {
		if (plugin == null)
			return;
		if (!countdownBarriersEnabled())
			return;

		// Make sure we aren't leaving old barriers behind.
		clearCountdownBarriers();

		for (var en : countdownLockLocation.entrySet()) {
			final java.util.UUID playerId = en.getKey();
			org.bukkit.Location lock = en.getValue();
			if (lock == null || lock.getWorld() == null)
				continue;
			try {
				BlockFace front = yawToFace(lock.getYaw());
				BlockFace left = rotateLeft(front);
				BlockFace right = rotateRight(front);
				BlockFace back = front.getOppositeFace();

				// Build a tighter barrier cage around the boat:
				// - A full ring around (front/back/left/right + diagonals)
				// - Extra reinforcement 2 blocks in front
				// - Two vertical layers at and above waterline
				//
				// Note: placing barriers at the boat's block Y may replace WATER; we only do
				// this when the block is
				// AIR/WATER to avoid griefing solid builds, and we restore on countdown end.

				// Determine the "footprint" blocks under the boat.
				// Old implementation assumed starts were centered (.5), which maps nicely to 1 block.
				// Some tracks start at block corners (.0), meaning the boat sits across 4 blocks.
				// We detect that and build the cage around a 2x2 footprint.
				final double eps = 0.20;
				double fx = lock.getX() - Math.floor(lock.getX());
				double fz = lock.getZ() - Math.floor(lock.getZ());
				int bx = lock.getBlockX();
				int bz = lock.getBlockZ();
				int minX;
				int maxX;
				int minZ;
				int maxZ;

				if (fx <= eps) {
					minX = bx - 1;
					maxX = bx;
				} else if ((1.0 - fx) <= eps) {
					minX = bx;
					maxX = bx + 1;
				} else {
					minX = bx;
					maxX = bx;
				}

				if (fz <= eps) {
					minZ = bz - 1;
					maxZ = bz;
				} else if ((1.0 - fz) <= eps) {
					minZ = bz;
					maxZ = bz + 1;
				} else {
					minZ = bz;
					maxZ = bz;
				}

				int midX = (minX + maxX) / 2;
				int midZ = (minZ + maxZ) / 2;
				int y = lock.getBlockY();
				org.bukkit.World w = lock.getWorld();

				// Visual stopper: place a stair directly in front of the boat during countdown.
				// Restored via countdownBarrierRestore after countdown ends.
				java.util.List<Block> stoppers = new java.util.ArrayList<>();
				if (front == BlockFace.NORTH) {
					int z = minZ - 1;
					for (int x = minX; x <= maxX; x++)
						stoppers.add(w.getBlockAt(x, y, z));
				} else if (front == BlockFace.SOUTH) {
					int z = maxZ + 1;
					for (int x = minX; x <= maxX; x++)
						stoppers.add(w.getBlockAt(x, y, z));
				} else if (front == BlockFace.WEST) {
					int x = minX - 1;
					for (int z = minZ; z <= maxZ; z++)
						stoppers.add(w.getBlockAt(x, y, z));
				} else if (front == BlockFace.EAST) {
					int x = maxX + 1;
					for (int z = minZ; z <= maxZ; z++)
						stoppers.add(w.getBlockAt(x, y, z));
				} else {
					// Fallback: keep legacy behavior (single stopper)
					stoppers.add(w.getBlockAt(midX + front.getModX(), y, midZ + front.getModZ()));
				}

				java.util.Set<Block> targets = new java.util.LinkedHashSet<>();

				// Build a ring around the footprint (1-block padding).
				int ringMinX = minX - 1;
				int ringMaxX = maxX + 1;
				int ringMinZ = minZ - 1;
				int ringMaxZ = maxZ + 1;
				for (int x = ringMinX; x <= ringMaxX; x++) {
					for (int z = ringMinZ; z <= ringMaxZ; z++) {
						boolean isBorder = x == ringMinX || x == ringMaxX || z == ringMinZ || z == ringMaxZ;
						if (!isBorder)
							continue;
						// Do not place barriers inside the footprint blocks.
						boolean inFootprint = x >= minX && x <= maxX && z >= minZ && z <= maxZ;
						if (inFootprint)
							continue;
						targets.add(w.getBlockAt(x, y, z));
					}
				}

				// Extra reinforcement: add a second wall 2 blocks in front.
				if (front == BlockFace.NORTH) {
					int z = ringMinZ - 1;
					for (int x = ringMinX; x <= ringMaxX; x++)
						targets.add(w.getBlockAt(x, y, z));
				} else if (front == BlockFace.SOUTH) {
					int z = ringMaxZ + 1;
					for (int x = ringMinX; x <= ringMaxX; x++)
						targets.add(w.getBlockAt(x, y, z));
				} else if (front == BlockFace.WEST) {
					int x = ringMinX - 1;
					for (int z = ringMinZ; z <= ringMaxZ; z++)
						targets.add(w.getBlockAt(x, y, z));
				} else if (front == BlockFace.EAST) {
					int x = ringMaxX + 1;
					for (int z = ringMinZ; z <= ringMaxZ; z++)
						targets.add(w.getBlockAt(x, y, z));
				}

				// Side reinforcement (helps prevent sideways drift)
				try {
					int l2x = midX + left.getModX() * 2;
					int l2z = midZ + left.getModZ() * 2;
					int r2x = midX + right.getModX() * 2;
					int r2z = midZ + right.getModZ() * 2;
					targets.add(w.getBlockAt(l2x, y, l2z));
					targets.add(w.getBlockAt(r2x, y, r2z));
				} catch (Throwable ignored) {
				}

				for (Block base : targets) {
					if (base == null)
						continue;

					// Place at waterline (dy=0) and above (dy=1..2)
					for (int dy = 0; dy <= 2; dy++) {
						Block target = base.getRelative(BlockFace.UP, dy);
						Material t = target.getType();

						boolean canReplace;
						if (dy == 0) {
							// Waterline: allow AIR or WATER only.
							canReplace = t == Material.AIR || t == Material.WATER;
						} else {
							// Above: only place in air.
							canReplace = t == Material.AIR;
						}

						if (!canReplace)
							continue;

						if (!countdownBarrierRestore.containsKey(target)) {
							try {
								countdownBarrierRestore.put(target, target.getBlockData().clone());
							} catch (Throwable ignored) {
								countdownBarrierRestore.put(target, target.getBlockData());
							}
						}
						target.setType(Material.BARRIER, false);
					}
				}

				// Place the stair stopper(s) after barriers so they remain visible.
				try {
					PreferredBoatData pref = resolvePreferredBoat(playerId);
					Material stairMat = resolveStopperStairMaterial(pref);
					for (Block stopper : stoppers) {
						if (stopper == null)
							continue;
						// Only place on the waterline block.
						Material cur = stopper.getType();
						boolean canReplace = cur == Material.AIR || cur == Material.WATER || cur == Material.BARRIER;
						if (!canReplace)
							continue;

						if (!countdownBarrierRestore.containsKey(stopper)) {
							try {
								countdownBarrierRestore.put(stopper, stopper.getBlockData().clone());
							} catch (Throwable ignored) {
								countdownBarrierRestore.put(stopper, stopper.getBlockData());
							}
						}

						org.bukkit.block.data.BlockData prev = countdownBarrierRestore.get(stopper);
						boolean wasWater = prev != null && prev.getMaterial() == Material.WATER;

						org.bukkit.block.data.type.Stairs stairs = (org.bukkit.block.data.type.Stairs) Bukkit
								.createBlockData(stairMat);
						// Face the stair toward the boat so the "flat" side looks like a stopper.
						try {
							stairs.setFacing(back);
						} catch (Throwable ignored) {
						}
						try {
							stairs.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
						} catch (Throwable ignored) {
						}
						try {
							if (stairs instanceof org.bukkit.block.data.Waterlogged wl)
								wl.setWaterlogged(wasWater);
						} catch (Throwable ignored) {
						}

						stopper.setBlockData(stairs, false);
					}
				} catch (Throwable ignored) {
				}
			} catch (Throwable ignored) {
			}
		}
	}

	private static Material resolveStopperStairMaterial(PreferredBoatData pref) {
		String base = (pref != null ? pref.baseType : null);
		if (base == null || base.isBlank())
			return Material.OAK_STAIRS;

		String name = base.toUpperCase(java.util.Locale.ROOT) + "_STAIRS";
		try {
			Material m;
			try {
				m = Material.valueOf(name);
			} catch (IllegalArgumentException ignored) {
				m = Material.matchMaterial(name);
			}
			if (m != null && m.isBlock() && m.name().endsWith("_STAIRS"))
				return m;
		} catch (Throwable ignored) {
		}
		return Material.OAK_STAIRS;
	}

	private static org.bukkit.Material checkpointMarkerMaterialForIndex(int zeroBasedIndex) {
		// Deterministic alternating palette so each checkpoint is visually distinct.
		// Keep colors bright and readable in most biomes.
		final org.bukkit.Material[] palette = new org.bukkit.Material[] {
				org.bukkit.Material.LIME_CONCRETE,
				org.bukkit.Material.YELLOW_CONCRETE,
				org.bukkit.Material.CYAN_CONCRETE,
				org.bukkit.Material.ORANGE_CONCRETE,
				org.bukkit.Material.PINK_CONCRETE,
				org.bukkit.Material.LIGHT_BLUE_CONCRETE
		};
		int idx = zeroBasedIndex;
		if (idx < 0)
			idx = 0;
		return palette[idx % palette.length];
	}

	private static float absAngleDelta(float a, float b) {
		float d = (a - b) % 360.0f;
		if (d > 180.0f)
			d -= 360.0f;
		if (d < -180.0f)
			d += 360.0f;
		return Math.abs(d);
	}

	private void restoreCountdownBoatPhysics() {
		// Kept for compatibility with existing call sites; no-op (see note above).
	}

	// (NMS force snap moved to util.EntityForceTeleport)
	private void dbg(String msg) {
		try {
			if (plugin != null)
				plugin.getLogger().info(msg);
		} catch (Throwable ignored) {
		}
	}

	public RaceManager(Plugin plugin, TrackConfig trackConfig) {
		this.plugin = plugin;
		this.trackConfig = trackConfig;
		String n = null;
		try {
			n = trackConfig != null ? trackConfig.getCurrentName() : null;
		} catch (Throwable ignored) {
			n = null;
		}
		this.trackId = (n == null ? "" : n);

		// Cache namespaced keys for this plugin instance.
		this.keySpawnedBoat = (plugin != null ? new NamespacedKey(plugin, "boatracing_spawned_boat") : null);
		this.keyCheckpointDisplay = (plugin != null ? new NamespacedKey(plugin, "boatracing_checkpoint_display") : null);
		this.keyCheckpointDisplayIndex = (plugin != null ? new NamespacedKey(plugin, "boatracing_checkpoint_display_index") : null);
		this.keyCheckpointDisplayTrack = (plugin != null ? new NamespacedKey(plugin, "boatracing_checkpoint_display_track") : null);
	}

	// test-only constructor that avoids needing a Plugin instance in unit tests
	public RaceManager(TrackConfig trackConfig) {
		this.plugin = null;
		this.trackConfig = trackConfig;
		String n = null;
		try {
			n = trackConfig != null ? trackConfig.getCurrentName() : null;
		} catch (Throwable ignored) {
			n = null;
		}
		this.trackId = (n == null ? "" : n);
		this.keySpawnedBoat = null;
		this.keyCheckpointDisplay = null;
		this.keyCheckpointDisplayIndex = null;
		this.keyCheckpointDisplayTrack = null;
	}

	private void ensureCheckpointHolos() {
		// Kept method name to avoid touching other call sites; implementation now uses
		// Display entities.
		ensureCheckpointDisplays();
	}

	// ===================== Boat dashboard (speedometer) =====================
	private void ensureDashboardTask() {
		if (plugin == null)
			return;
		if (dashboardTask != null)
			return;

		// Configurable update rate. Lower = tighter rotation lock, higher = less CPU.
		// Note: slower updates can make the dashboard look like it "falls behind" when
		// turning.
		int periodTicks = 1;
		try {
			periodTicks = plugin.getConfig().getInt("racing.dashboard.update-ticks", 1);
		} catch (Throwable ignored) {
			periodTicks = 1;
		}
		periodTicks = Math.max(1, periodTicks);

		final int finalPeriodTicks = periodTicks;
		dashboardTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
			// Only show dashboards while countdown/race is active.
			if (!running && (countdownTask == null || countdownPlayers.isEmpty())) {
				clearAllDashboards();
				stopDashboardTask();
				return;
			}

			// Reuse a single Set/List each tick to avoid allocations.
			dashboardActiveTmp.clear();
			dashboardActiveTmp.addAll(countdownPlayers);
			// Only show for racers that are still racing (not finished).
			for (var en : participants.entrySet()) {
				UUID id = en.getKey();
				ParticipantState st = en.getValue();
				if (id == null || st == null || st.finished)
					continue;
				dashboardActiveTmp.add(id);
			}

			// Remove dashboards for players who are no longer active.
			dashboardRemoveTmp.clear();
			for (UUID id : dashboardByPlayer.keySet()) {
				if (id != null && !dashboardActiveTmp.contains(id))
					dashboardRemoveTmp.add(id);
			}
			for (UUID id : dashboardRemoveTmp) {
				removeDashboard(id);
			}

			// Update/create dashboards.
			for (UUID id : dashboardActiveTmp) {
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline()) {
					removeDashboard(id);
					continue;
				}
				updateDashboard(p, finalPeriodTicks);
			}
		}, 1L, finalPeriodTicks);
	}

	private void refreshDashboardThresholdsIfNeeded(long nowMs) {
		if (plugin == null)
			return;
		if (nowMs < dashboardThresholdsNextRefreshMs)
			return;
		// Refresh at most once per second.
		dashboardThresholdsNextRefreshMs = nowMs + 1000L;
		try {
			dashboardYellowKmhCached = plugin.getConfig().getInt("scoreboard.speed.yellow_kmh", 5);
		} catch (Throwable ignored) {
			dashboardYellowKmhCached = 5;
		}
		try {
			dashboardGreenKmhCached = plugin.getConfig().getInt("scoreboard.speed.green_kmh", 20);
		} catch (Throwable ignored) {
			dashboardGreenKmhCached = 20;
		}
		if (dashboardGreenKmhCached < dashboardYellowKmhCached) {
			int t = dashboardGreenKmhCached;
			dashboardGreenKmhCached = dashboardYellowKmhCached;
			dashboardYellowKmhCached = t;
		}
	}

	private int getDashboardBarCellsCached(long nowMs) {
		if (plugin == null)
			return Math.max(1, Math.min(80, dashboardBarCellsCached));
		if (nowMs >= dashboardBarCellsNextRefreshMs) {
			dashboardBarCellsNextRefreshMs = nowMs + 1000L;
			int v = 30;
			try {
				v = plugin.getConfig().getInt("racing.dashboard.bar-cells", 30);
			} catch (Throwable ignored) {
				v = 30;
			}
			dashboardBarCellsCached = Math.max(1, Math.min(80, v));
		}
		return dashboardBarCellsCached;
	}

	private void stopDashboardTask() {
		if (dashboardTask != null) {
			try {
				dashboardTask.cancel();
			} catch (Throwable ignored) {
			}
			dashboardTask = null;
		}
	}

	private void clearAllDashboards() {
		dashboardRemoveTmp.clear();
		dashboardRemoveTmp.addAll(dashboardByPlayer.keySet());
		for (UUID id : dashboardRemoveTmp) {
			removeDashboard(id);
		}
		dashboardRemoveTmp.clear();
	}

	private void removeDashboard(UUID playerId) {
		if (playerId == null)
			return;
		org.bukkit.entity.TextDisplay d = dashboardByPlayer.remove(playerId);
		if (d != null) {
			try {
				if (d.isValid())
					d.remove();
			} catch (Throwable ignored) {
			}
		}
		try {
			dashboardLastBoatLoc.remove(playerId);
		} catch (Throwable ignored) {
		}
		try {
			dashboardLastBoatLocTime.remove(playerId);
		} catch (Throwable ignored) {
		}
		try {
			dashboardLastBps.remove(playerId);
		} catch (Throwable ignored) {
		}
		try {
			dashboardStationaryTicks.remove(playerId);
		} catch (Throwable ignored) {
		}
		try {
			dashboardDebugLastLog.remove(playerId);
		} catch (Throwable ignored) {
		}
	}

	private static void trySetTeleportDuration(org.bukkit.entity.Display d, int ticks) {
		if (d == null)
			return;
		int t = Math.max(0, ticks);
		try {
			// Paper: Display has setTeleportDuration(int). Use reflection to avoid API
			// mismatch.
			java.lang.reflect.Method m = d.getClass().getMethod("setTeleportDuration", int.class);
			m.invoke(d, t);
		} catch (Throwable ignored) {
		}
	}

	private static void trySetTextDisplayBackgroundArgb(org.bukkit.entity.TextDisplay d, int argb) {
		if (d == null)
			return;

		// Preferred (Paper/Bukkit): setBackgroundColor(int argb)
		try {
			java.lang.reflect.Method m = d.getClass().getMethod("setBackgroundColor", int.class);
			m.invoke(d, argb);
			return;
		} catch (Throwable ignored) {
		}

		// Fallback (some APIs): setBackgroundOpacity(byte) + setBackgroundColor(Color
		// rgb)
		try {
			int a = (argb >>> 24) & 0xFF;
			java.lang.reflect.Method m = d.getClass().getMethod("setBackgroundOpacity", byte.class);
			m.invoke(d, (byte) a);
		} catch (Throwable ignored) {
		}

		try {
			int r = (argb >>> 16) & 0xFF;
			int g = (argb >>> 8) & 0xFF;
			int b = (argb) & 0xFF;
			org.bukkit.Color c = org.bukkit.Color.fromRGB(r, g, b);
			java.lang.reflect.Method m = d.getClass().getMethod("setBackgroundColor", org.bukkit.Color.class);
			m.invoke(d, c);
		} catch (Throwable ignored) {
		}
	}

	private String dashboardSpeedColorLegacy(double kmh) {
		// Color tiering for the dashboard bar.
		// Uses the same config keys as the scoreboard speed coloring.
		double v = Double.isFinite(kmh) ? Math.max(0.0, kmh) : 0.0;
		long now = System.currentTimeMillis();
		refreshDashboardThresholdsIfNeeded(now);
		int yellow = dashboardYellowKmhCached;
		int green = dashboardGreenKmhCached;

		if (v < (double) yellow)
			return "&c"; // red
		if (v < (double) green)
			return "&e"; // yellow
		return "&a"; // green
	}

	private void ensureDashboardBarCache(int cells) {
		int n = Math.max(1, Math.min(80, cells));
		if (dashboardBarCacheCells == n && dashboardBarCacheRed != null && dashboardBarCacheYellow != null
				&& dashboardBarCacheGreen != null)
			return;

		dashboardBarCacheCells = n;
		dashboardBarCacheRed = new String[n + 1];
		dashboardBarCacheYellow = new String[n + 1];
		dashboardBarCacheGreen = new String[n + 1];

		String all = "▎".repeat(n);
		for (int i = 0; i <= n; i++) {
			if (i <= 0) {
				String s = "&8" + all;
				dashboardBarCacheRed[i] = s;
				dashboardBarCacheYellow[i] = s;
				dashboardBarCacheGreen[i] = s;
				continue;
			}
			if (i >= n) {
				dashboardBarCacheRed[i] = "&c" + all;
				dashboardBarCacheYellow[i] = "&e" + all;
				dashboardBarCacheGreen[i] = "&a" + all;
				continue;
			}
			String fill = "▎".repeat(i);
			String rem = "▎".repeat(n - i);
			dashboardBarCacheRed[i] = "&c" + fill + "&8" + rem;
			dashboardBarCacheYellow[i] = "&e" + fill + "&8" + rem;
			dashboardBarCacheGreen[i] = "&a" + fill + "&8" + rem;
		}
	}

	private String buildDashboardBar(double kmh, int cells) {
		// User preference: use ONLY the "▎" glyph for both filled and empty portions.
		int n = Math.max(1, Math.min(80, cells));
		ensureDashboardBarCache(n);

		// Scale: keep roughly the same max range as before (≈ 0..80 km/h), but
		// distribute across more cells for finer granularity.
		// Example: 20 cells => 4 km/h per cell.
		double kmhPerCell = 80.0 / (double) n;
		if (!Double.isFinite(kmhPerCell) || kmhPerCell <= 0.0)
			kmhPerCell = 4.0;

		double v = Double.isFinite(kmh) ? Math.max(0.0, kmh) : 0.0;
		int filled = (int) Math.round(v / kmhPerCell);
		filled = Math.max(0, Math.min(n, filled));

		long now = System.currentTimeMillis();
		refreshDashboardThresholdsIfNeeded(now);
		if (v < (double) dashboardYellowKmhCached)
			return dashboardBarCacheRed[filled];
		if (v < (double) dashboardGreenKmhCached)
			return dashboardBarCacheYellow[filled];
		return dashboardBarCacheGreen[filled];
	}

	private void updateDashboard(Player p, int periodTicks) {
		if (plugin == null || p == null || !p.isOnline())
			return;
		UUID id = p.getUniqueId();

		// If this server/vehicle combo can't keep the dashboard mounted, do not
		// recreate it.
		if (dashboardDisabledPlayers.contains(id))
			return;

		Entity boat = null;
		try {
			Entity veh = p.getVehicle();
			if (isBoatLike(veh))
				boat = veh;
		} catch (Throwable ignored) {
		}
		if (boat == null) {
			try {
				UUID boatId = spawnedBoatByPlayer.get(id);
				if (boatId != null) {
					Entity e = Bukkit.getEntity(boatId);
					if (isBoatLike(e))
						boat = e;
				}
			} catch (Throwable ignored) {
			}
		}
		if (boat == null || !boat.isValid()) {
			removeDashboard(id);
			return;
		}

		org.bukkit.Location bl = boat.getLocation();
		if (bl == null || bl.getWorld() == null)
			return;

		// Compute speed from movement delta so it updates reliably even when entity
		// velocity is flaky.
		// Also: boat location sampling can occasionally return the same position for a
		// tick (scheduler/physics ordering, chunk sync, etc.) which used to cause the
		// dashboard to flash 0. We hold the last speed briefly, and only drop to 0 after
		// a short stationary streak.
		long nowMs = System.currentTimeMillis();
		double bps = 0.0;
		// Secondary source: boat horizontal velocity (blocks/tick -> blocks/sec).
		double velBps = 0.0;
		try {
			org.bukkit.util.Vector v = boat.getVelocity();
			if (v != null) {
				double hv = Math.sqrt((v.getX() * v.getX()) + (v.getZ() * v.getZ()));
				if (Double.isFinite(hv) && hv >= 0.0)
					velBps = hv * 20.0;
			}
		} catch (Throwable ignored) {
			velBps = 0.0;
		}
		try {
			org.bukkit.Location prev = dashboardLastBoatLoc.get(id);
			Long prevT = dashboardLastBoatLocTime.get(id);
			double last = 0.0;
			try {
				Double v = dashboardLastBps.get(id);
				last = (v != null && Double.isFinite(v)) ? Math.max(0.0, v) : 0.0;
			} catch (Throwable ignored2) {
				last = 0.0;
			}
			int stillTicks = 0;
			try {
				Integer v = dashboardStationaryTicks.get(id);
				stillTicks = (v != null ? Math.max(0, v.intValue()) : 0);
			} catch (Throwable ignored2) {
				stillTicks = 0;
			}

			if (prev != null && prevT != null
					&& prev.getWorld() != null && bl.getWorld() != null
					&& prev.getWorld().equals(bl.getWorld())) {
				long dtMsRaw = nowMs - prevT;
				if (dtMsRaw > 0L) {
					// Expected update delta based on scheduler period.
					long expectedMs = Math.max(1L, (long) periodTicks * 50L);
					long dtMs = Math.max(dtMsRaw, expectedMs);
					double dist = 0.0;
					try {
						double dx = bl.getX() - prev.getX();
						double dz = bl.getZ() - prev.getZ();
						dist = Math.sqrt((dx * dx) + (dz * dz));
					} catch (Throwable ignored2) {
						dist = 0.0;
					}

					// Very small deltas are often just "no new sample this tick".
					final double eps = 1.0E-4;
					final int holdTicks = 2;
					final double velMoveEpsBps = 0.20; // treat as moving if velocity exceeds this

					// Ignore big jumps (teleports / chunk re-sync / countdown snaps).
					// A reasonable bound: a few blocks per tick. Anything larger is not real
					// movement.
					double maxDist = 5.0 * (double) Math.max(1, periodTicks);
					if (!Double.isFinite(dist) || dist < 0.0) {
						// Bad sample: prefer velocity if available, otherwise keep last.
						bps = (velBps > velMoveEpsBps ? velBps : last);
						stillTicks = 0;
						try {
							dashboardLastBoatLoc.put(id, bl.clone());
							dashboardLastBoatLocTime.put(id, nowMs);
						} catch (Throwable ignored3) {
						}
					} else if (dist <= eps) {
						// Stale position sample: if velocity says we're moving, accept velocity.
						if (velBps > velMoveEpsBps) {
							bps = velBps;
							stillTicks = 0;
							try {
								dashboardLastBoatLoc.put(id, bl.clone());
								dashboardLastBoatLocTime.put(id, nowMs);
							} catch (Throwable ignored3) {
							}
						} else {
							stillTicks++;
							if (stillTicks <= holdTicks && last > 0.0) {
								// Hold last speed briefly.
								bps = last;
							} else {
								// Truly stopped (or held long enough): allow 0 and advance sample.
								bps = 0.0;
								try {
									dashboardLastBoatLoc.put(id, bl.clone());
									dashboardLastBoatLocTime.put(id, nowMs);
								} catch (Throwable ignored3) {
								}
							}
						}
					} else if (dist > maxDist) {
						// Teleport-like jump: ignore delta. Prefer velocity if it looks sane.
						bps = (velBps > velMoveEpsBps ? velBps : last);
						stillTicks = 0;
						try {
							dashboardLastBoatLoc.put(id, bl.clone());
							dashboardLastBoatLocTime.put(id, nowMs);
						} catch (Throwable ignored3) {
						}
					} else {
						double deltaBps = Math.max(0.0, dist / (dtMs / 1000.0));
						// Blend delta + velocity to reduce jitter and eliminate stale samples.
						if (velBps > 0.0 && Double.isFinite(velBps)) {
							bps = (deltaBps * 0.65) + (velBps * 0.35);
						} else {
							bps = deltaBps;
						}
						stillTicks = 0;
						// Normal movement sample: advance so we measure per-tick speed.
						try {
							dashboardLastBoatLoc.put(id, bl.clone());
							dashboardLastBoatLocTime.put(id, nowMs);
						} catch (Throwable ignored3) {
						}
					}
				}
			}

			try {
				dashboardStationaryTicks.put(id, stillTicks);
			} catch (Throwable ignored2) {
			}
			try {
				dashboardLastBps.put(id, bps);
			} catch (Throwable ignored2) {
			}
		} catch (Throwable ignored) {
		}
		// Only advance the sample when we have a valid movement delta or when we
		// intentionally accepted a stop/teleport case above.
		try {
			org.bukkit.Location prev = dashboardLastBoatLoc.get(id);
			if (prev == null) {
				dashboardLastBoatLoc.put(id, bl.clone());
				dashboardLastBoatLocTime.put(id, nowMs);
			}
		} catch (Throwable ignored) {
		}
		double kmh = bps * 3.6;

		// Futuristic 2-line dashboard with a thin + more accurate bar speedometer.
		// Bar width is configurable; default is wider for better resolution.
		int barCells = getDashboardBarCellsCached(nowMs);
		String bar = buildDashboardBar(kmh, barCells);

		// User request: only 2 lines (speed number + bar)
		final String pad = "  ";
		String text = pad + "&b" + fmt1(kmh) + "&7 km/h" + pad + "\n" + pad + bar + pad;

		// IMPORTANT: No teleport-follow fallback.
		// If we can't mount to the rider, we remove the dashboard entirely.
		final float boatYaw = bl.getYaw();

		// Keep transformation consistent even if the display was created before tweaks.
		final org.joml.Vector3f dashTranslation = new org.joml.Vector3f(0.0f, -1.35f, 0.80f);
		final org.joml.Vector3f dashScale = new org.joml.Vector3f(0.55f, 0.55f, 0.55f);
		final org.joml.Quaternionf dashRotation = new org.joml.Quaternionf()
				.rotateY((float) Math.toRadians(180.0))
				.rotateX((float) Math.toRadians(-28.0));

		org.bukkit.entity.TextDisplay display = dashboardByPlayer.get(id);
		if (display == null || !display.isValid()) {
			try {
				display = bl.getWorld().spawn(bl, org.bukkit.entity.TextDisplay.class, d -> {
					try {
						d.text(Text.c(text));
					} catch (Throwable ignored2) {
					}
					try {
						d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
					} catch (Throwable ignored2) {
					}
					try {
						d.setSeeThrough(true);
					} catch (Throwable ignored2) {
					}
					try {
						d.setDefaultBackground(true);
					} catch (Throwable ignored2) {
					}
					try {
						trySetTextDisplayBackgroundArgb(d, 0x33000000);
					} catch (Throwable ignored2) {
					}
					try {
						d.setLineWidth(200);
					} catch (Throwable ignored2) {
					}
					try {
						d.setViewRange(32.0f);
					} catch (Throwable ignored2) {
					}
					try {
						d.setShadowed(true);
					} catch (Throwable ignored2) {
					}
					try {
						d.setAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
					} catch (Throwable ignored2) {
					}

					// Smooth client-side interpolation for any transform updates.
					try {
						d.setInterpolationDelay(0);
						d.setInterpolationDuration(Math.max(1, periodTicks));
					} catch (Throwable ignored2) {
					}
					trySetTeleportDuration(d, Math.max(1, periodTicks));

					// Render offset relative to the passenger mount point.
					// Since this display is mounted to the player, its entity position is near the
					// rider's head.
					// Use a negative Y translation to bring it down into a "dashboard" position,
					// and add a
					// slight pitch so it looks like an angled instrument panel.
					try {
						org.bukkit.util.Transformation cur = d.getTransformation();
						org.bukkit.util.Transformation next = new org.bukkit.util.Transformation(
								// Forward (+Z in entity space) and DOWN from head mount.
								// Lowered further so it stays out of the crosshair.
								// Requested tweak: down 0.5 block and closer 0.25 block.
								dashTranslation,
								// Face the driver (180° yaw) and tilt slightly toward the camera.
								dashRotation,
								// Scale down so it doesn't obstruct the view.
								dashScale,
								cur.getRightRotation());
						d.setTransformation(next);
					} catch (Throwable ignored2) {
					}
				});
			} catch (Throwable ignored) {
				display = null;
			}
			if (display == null)
				return;
			dashboardByPlayer.put(id, display);
		} else {
			try {
				display.text(Text.c(text));
			} catch (Throwable ignored) {
			}

			// Re-apply background settings in case the display was spawned before tweaks.
			try {
				display.setDefaultBackground(true);
			} catch (Throwable ignored) {
			}
			try {
				trySetTextDisplayBackgroundArgb(display, 0x33000000);
			} catch (Throwable ignored) {
			}

			// Apply transform updates even for existing displays so tweaks take effect
			// immediately.
			try {
				org.bukkit.util.Transformation cur = display.getTransformation();
				org.bukkit.util.Transformation next = new org.bukkit.util.Transformation(
						dashTranslation,
						dashRotation,
						dashScale,
						cur.getRightRotation());
				display.setTransformation(next);
			} catch (Throwable ignored) {
			}
		}

		// Keep the dashboard mounted on the PLAYER (more reliable than mounting on
		// rafts/boats).
		boolean mounted = false;
		try {
			org.bukkit.entity.Entity riding = display.getVehicle();
			if (riding != null && riding.equals(p)) {
				mounted = true;
			} else {
				if (riding != null) {
					try {
						display.leaveVehicle();
					} catch (Throwable ignored2) {
					}
				}
				try {
					p.addPassenger(display);
				} catch (Throwable ignored2) {
				}
				riding = display.getVehicle();
				mounted = (riding != null && riding.equals(p));
			}
		} catch (Throwable ignored) {
			mounted = false;
		}

		if (!mounted) {
			// No laggy teleport fallback: remove entirely if mounting is not supported.
			removeDashboard(id);
			dashboardDisabledPlayers.add(id);
			try {
				dbg("[DASHDBG] Disabled dashboard for player=" + p.getName()
						+ " (failed to mount to player; vehicle="
						+ (boat.getType() == null ? "?" : boat.getType().name()) + ")");
			} catch (Throwable ignored) {
			}
			return;
		}

		// Follow boat rotation.
		try {
			display.setRotation(boatYaw, 0.0f);
		} catch (Throwable ignored) {
		}

		// Debug logs (rate-limited): helps diagnose why dashboard seems detached or
		// mis-rotated.
		if (debugDashboard()) {
			long now = System.currentTimeMillis();
			Long prev = dashboardDebugLastLog.get(id);
			if (prev == null || (now - prev) >= 1000L) {
				dashboardDebugLastLog.put(id, now);
				try {
					org.bukkit.Location dl = display.getLocation();
					String boatType = (boat.getType() == null ? "?" : boat.getType().name());
					String boatWorld = (bl.getWorld() == null ? "?" : bl.getWorld().getName());
					String dispWorld = (dl.getWorld() == null ? "?" : dl.getWorld().getName());
					double dist = (dl.getWorld() != null && bl.getWorld() != null
							&& dl.getWorld().equals(bl.getWorld()))
									? dl.distance(bl)
									: -1.0;
					boolean passengerBoat = false;
					try {
						passengerBoat = boat.getPassengers() != null && boat.getPassengers().contains(display);
					} catch (Throwable ignored2) {
					}
					boolean passengerPlayer = false;
					try {
						passengerPlayer = p.getPassengers() != null && p.getPassengers().contains(display);
					} catch (Throwable ignored2) {
					}
					boolean riding = false;
					try {
						riding = display.getVehicle() != null;
					} catch (Throwable ignored2) {
					}
					dbg("[DASHDBG] track=" + (trackConfig == null ? "?" : trackConfig.getCurrentName())
							+ " player=" + p.getName()
							+ " boat=" + boatType
							+ " boatWorld=" + boatWorld
							+ " dispWorld=" + dispWorld
							+ " passengerBoat=" + passengerBoat
							+ " passengerPlayer=" + passengerPlayer
							+ " riding=" + riding
							+ " dist=" + (dist < 0 ? "?" : String.format(java.util.Locale.ROOT, "%.2f", dist))
							+ " boatYaw=" + String.format(java.util.Locale.ROOT, "%.1f", bl.getYaw())
							+ " playerYaw=" + String.format(java.util.Locale.ROOT, "%.1f", p.getLocation().getYaw())
							+ " dispYaw=" + String.format(java.util.Locale.ROOT, "%.1f", dl.getYaw())
							+ " dispPos=" + dev.belikhun.boatracing.util.Text.fmtPos(dl));
				} catch (Throwable ignored) {
				}
			}
		}

	}

	private void ensureCheckpointDisplays() {
		if (plugin == null)
			return;
		if (trackConfig == null)
			return;
		if (!checkpointDisplays.isEmpty())
			return;

		java.util.List<Region> checkpoints;
		try {
			checkpoints = trackConfig.getCheckpoints();
		} catch (Throwable ignored) {
			checkpoints = java.util.Collections.emptyList();
		}
		if (checkpoints == null || checkpoints.isEmpty())
			return;

		// Remove any stale/duplicated displays left behind by crashes / hard reloads.
		// We do this *before* spawning so each checkpoint ends up with exactly 2
		// displays.
		try {
			sweepCheckpointDisplays();
		} catch (Throwable ignored) {
		}

		for (int i = 0; i < checkpoints.size(); i++) {
			Region r = checkpoints.get(i);
			if (r == null)
				continue;

			org.bukkit.Location base;
			try {
				base = centerOf(r);
			} catch (Throwable ignored) {
				base = null;
			}
			if (base == null || base.getWorld() == null)
				continue;

			// Slightly above the water/track so it's readable.
			org.bukkit.Location itemLoc = base.clone();
			org.bukkit.Location textLoc = base.clone();
			try {
				itemLoc.setY(itemLoc.getY() + 1.35);
				textLoc.setY(textLoc.getY() + 2.00);
			} catch (Throwable ignored) {
			}

			final int idx = i + 1;
			final org.bukkit.Material markerMat = checkpointMarkerMaterialForIndex(i);

			// Hard de-dup near this checkpoint center (covers cases where old entities
			// survived).
			try {
				removeCheckpointDisplaysNear(base, idx);
			} catch (Throwable ignored) {
			}

			// 1) Rotating item display
			try {
				org.bukkit.entity.ItemDisplay item = itemLoc.getWorld().spawn(itemLoc,
						org.bukkit.entity.ItemDisplay.class, d -> {
							try {
								d.setItemStack(new org.bukkit.inventory.ItemStack(markerMat));
							} catch (Throwable ignored2) {
							}
							try {
								markCheckpointDisplay(d, idx);
							} catch (Throwable ignored2) {
							}
							try {
								d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
							} catch (Throwable ignored2) {
							}
							try {
								// Keep it crisp and visible at range.
								d.setViewRange(64.0f);
							} catch (Throwable ignored2) {
							}

							// Let the client interpolate transformation updates for smooth animation.
							try {
								d.setInterpolationDelay(0);
								d.setInterpolationDuration(10);
							} catch (Throwable ignored2) {
							}

							// Make the item smaller (closer to a dropped item feel)
							try {
								org.bukkit.util.Transformation cur = d.getTransformation();
								org.bukkit.util.Transformation next = new org.bukkit.util.Transformation(
										cur.getTranslation(),
										cur.getLeftRotation(),
										new org.joml.Vector3f(0.45f, 0.45f, 0.45f),
										cur.getRightRotation());
								d.setTransformation(next);
							} catch (Throwable ignored2) {
							}
						});
				checkpointDisplays.add(item);
			} catch (Throwable ignored) {
			}

			// 2) Floating text label
			try {
				org.bukkit.entity.TextDisplay text = textLoc.getWorld().spawn(textLoc,
						org.bukkit.entity.TextDisplay.class, d -> {
							try {
								d.text(Text.c("&a✔ &fĐiểm kiểm tra &a#" + idx));
							} catch (Throwable ignored2) {
							}
							try {
								markCheckpointDisplay(d, idx);
							} catch (Throwable ignored2) {
							}
							try {
								d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
							} catch (Throwable ignored2) {
							}
							try {
								d.setSeeThrough(true);
							} catch (Throwable ignored2) {
							}
							try {
								d.setDefaultBackground(false);
							} catch (Throwable ignored2) {
							}
							try {
								d.setViewRange(64.0f);
							} catch (Throwable ignored2) {
							}

							// Text itself doesn't animate, but interpolation avoids any snapping if we ever
							// adjust it.
							try {
								d.setInterpolationDelay(0);
								d.setInterpolationDuration(10);
							} catch (Throwable ignored2) {
							}
						});
				checkpointDisplays.add(text);
			} catch (Throwable ignored) {
			}
		}

		if (checkpointDisplays.isEmpty())
			return;

		if (checkpointDisplayTask != null) {
			try {
				checkpointDisplayTask.cancel();
			} catch (Throwable ignored) {
			}
			checkpointDisplayTask = null;
		}

		checkpointDisplayTick = 0L;
		checkpointSpin.identity();

		// Drive the animation with keyframes and let the client interpolate between
		// them.
		// This is smoother and lower-cost than updating every tick.
		final int interpTicks = 10;
		checkpointDisplayTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
			checkpointDisplayTick += interpTicks;

			// Bobbing motion similar to dropped items (client interpolates between
			// keyframes).
			float bob = (float) (Math.sin((double) checkpointDisplayTick * 0.18) * 0.12);

			// Accumulate spin in quaternion space to avoid 360° wrap causing reverse
			// interpolation.
			final float deltaYawRad = (float) Math.toRadians(5.0f * interpTicks); // match old 5 deg/tick
			checkpointSpin.mul(new org.joml.Quaternionf().rotateY(deltaYawRad));

			java.util.Iterator<org.bukkit.entity.Display> it = checkpointDisplays.iterator();
			while (it.hasNext()) {
				org.bukkit.entity.Display d = it.next();
				if (d == null || d.isDead() || !d.isValid()) {
					it.remove();
					continue;
				}
				if (!(d instanceof org.bukkit.entity.ItemDisplay item))
					continue;

				try {
					// Spin + slight tilt + bob like a dropped item entity.
					org.joml.Quaternionf rot = new org.joml.Quaternionf()
							.rotateX((float) Math.toRadians(20.0))
							.mul(new org.joml.Quaternionf(checkpointSpin));
					org.bukkit.util.Transformation cur = item.getTransformation();
					org.bukkit.util.Transformation next = new org.bukkit.util.Transformation(
							new org.joml.Vector3f(cur.getTranslation().x(), bob, cur.getTranslation().z()),
							rot,
							cur.getScale(),
							cur.getRightRotation());
					item.setTransformation(next);

					// Ensure interpolation settings are applied for each keyframe.
					try {
						item.setInterpolationDelay(0);
						item.setInterpolationDuration(interpTicks);
					} catch (Throwable ignored2) {
					}
				} catch (Throwable ignored) {
				}
			}

			if (checkpointDisplays.isEmpty()) {
				if (checkpointDisplayTask != null) {
					try {
						checkpointDisplayTask.cancel();
					} catch (Throwable ignored) {
					}
					checkpointDisplayTask = null;
				}
			}
		}, 1L, interpTicks);
	}

	private void clearCheckpointHolos() {
		// Kept method name to avoid touching other call sites; implementation now uses
		// Display entities.
		clearCheckpointDisplays();
	}

	private void clearCheckpointDisplays() {
		if (checkpointDisplayTask != null) {
			try {
				checkpointDisplayTask.cancel();
			} catch (Throwable ignored) {
			}
			checkpointDisplayTask = null;
		}

		// 1) Remove tracked display entity references.
		for (org.bukkit.entity.Display d : new java.util.ArrayList<>(checkpointDisplays)) {
			try {
				if (d != null)
					d.remove();
			} catch (Throwable ignored) {
			}
		}
		checkpointDisplays.clear();

		// 2) Fallback sweep: if entity references were lost (chunk unload / reload),
		// remove any
		// checkpoint displays near each checkpoint center by marker key.
		try {
			sweepCheckpointDisplays();
		} catch (Throwable ignored) {
		}
	}

	private NamespacedKey checkpointDisplayKey() {
		return keyCheckpointDisplay;
	}

	private NamespacedKey checkpointDisplayIndexKey() {
		return keyCheckpointDisplayIndex;
	}

	private NamespacedKey checkpointDisplayTrackKey() {
		return keyCheckpointDisplayTrack;
	}

	private String checkpointDisplayTrackId() {
		return (trackId == null ? "" : trackId);
	}

	private void markCheckpointDisplay(org.bukkit.entity.Display d, int checkpointOneBasedIndex) {
		if (d == null)
			return;
		NamespacedKey key = checkpointDisplayKey();
		if (key == null)
			return;
		try {
			d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
		} catch (Throwable ignored) {
		}

		// Tag with track id so multi-track cleanup can't delete other track markers.
		NamespacedKey trackKey = checkpointDisplayTrackKey();
		if (trackKey != null) {
			try {
				d.getPersistentDataContainer().set(trackKey, PersistentDataType.STRING, checkpointDisplayTrackId());
			} catch (Throwable ignored) {
			}
		}

		// Tag with checkpoint index so we can de-dup per-checkpoint.
		NamespacedKey idxKey = checkpointDisplayIndexKey();
		if (idxKey != null) {
			try {
				d.getPersistentDataContainer().set(idxKey, PersistentDataType.INTEGER,
						Math.max(1, checkpointOneBasedIndex));
			} catch (Throwable ignored) {
			}
		}
	}

	private boolean isCheckpointDisplay(org.bukkit.entity.Entity e) {
		if (e == null)
			return false;
		if (!(e instanceof org.bukkit.entity.Display))
			return false;
		NamespacedKey key = checkpointDisplayKey();
		if (key == null)
			return false;
		try {
			if (!e.getPersistentDataContainer().has(key, PersistentDataType.BYTE))
				return false;

			// If a track tag exists, require it to match this RaceManager's track.
			NamespacedKey trackKey = checkpointDisplayTrackKey();
			if (trackKey != null && e.getPersistentDataContainer().has(trackKey, PersistentDataType.STRING)) {
				String tagged = e.getPersistentDataContainer().get(trackKey, PersistentDataType.STRING);
				String t = (tagged == null ? "" : tagged);
				// Treat blank-tagged displays as legacy and removable by any track sweep.
				if (t.isBlank())
					return true;
				return checkpointDisplayTrackId().equals(t);
			}

			// Legacy displays (no track tag) are considered removable.
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void removeCheckpointDisplaysNear(org.bukkit.Location center, int checkpointOneBasedIndex) {
		if (plugin == null || center == null || center.getWorld() == null)
			return;
		int idx = Math.max(1, checkpointOneBasedIndex);
		NamespacedKey key = checkpointDisplayKey();
		NamespacedKey trackKey = checkpointDisplayTrackKey();
		NamespacedKey idxKey = checkpointDisplayIndexKey();
		if (key == null)
			return;

		final double r = 6.0;
		java.util.Collection<org.bukkit.entity.Entity> near;
		try {
			near = center.getWorld().getNearbyEntities(center, r, r, r, e -> {
				if (!(e instanceof org.bukkit.entity.Display))
					return false;
				try {
					var pdc = e.getPersistentDataContainer();
					if (!pdc.has(key, PersistentDataType.BYTE))
						return false;
					// Track tag: match exact, or allow legacy blank/missing.
					if (trackKey != null && pdc.has(trackKey, PersistentDataType.STRING)) {
						String t = pdc.get(trackKey, PersistentDataType.STRING);
						t = (t == null ? "" : t);
						if (!t.isBlank() && !checkpointDisplayTrackId().equals(t))
							return false;
					}
					// Index tag: if present, match this checkpoint; if missing, treat as legacy ->
					// removable.
					if (idxKey != null && pdc.has(idxKey, PersistentDataType.INTEGER)) {
						Integer v = pdc.get(idxKey, PersistentDataType.INTEGER);
						if (v != null && v.intValue() != idx)
							return false;
					}
					return true;
				} catch (Throwable ignored) {
					return false;
				}
			});
		} catch (Throwable ignored) {
			near = java.util.Collections.emptyList();
		}

		for (org.bukkit.entity.Entity e : near) {
			try {
				e.remove();
			} catch (Throwable ignored) {
			}
		}
	}

	private void sweepCheckpointDisplays() {
		if (plugin == null || trackConfig == null)
			return;
		java.util.List<Region> cps;
		try {
			cps = trackConfig.getCheckpoints();
		} catch (Throwable ignored) {
			cps = java.util.Collections.emptyList();
		}
		if (cps == null || cps.isEmpty())
			return;

		final double r = 8.0;
		for (Region cp : cps) {
			if (cp == null)
				continue;
			org.bukkit.Location c;
			try {
				c = centerOf(cp);
			} catch (Throwable ignored) {
				c = null;
			}
			if (c == null || c.getWorld() == null)
				continue;

			java.util.Collection<org.bukkit.entity.Entity> near;
			try {
				near = c.getWorld().getNearbyEntities(c, r, r, r, this::isCheckpointDisplay);
			} catch (Throwable ignored) {
				near = java.util.Collections.emptyList();
			}
			for (org.bukkit.entity.Entity e : near) {
				try {
					if (e != null)
						e.remove();
				} catch (Throwable ignored) {
				}
			}
		}
	}

	public TrackConfig getTrackConfig() {
		return trackConfig;
	}

	public boolean isRunning() {
		return running;
	}

	public boolean isRegistering() {
		return registering;
	}

	public Set<UUID> getRegistered() {
		return Collections.unmodifiableSet(registered);
	}

	public boolean isAnyCountdownActive() {
		return (countdownTask != null && !countdownPlayers.isEmpty())
				|| (introEndMillis > System.currentTimeMillis() && !introPlayers.isEmpty());
	}

	public boolean isIntroActive() {
		return introEndMillis > System.currentTimeMillis() && !introPlayers.isEmpty();
	}

	public boolean isInvolved(UUID id) {
		if (id == null)
			return false;
		return registered.contains(id) || participants.containsKey(id) || countdownPlayers.contains(id)
				|| introPlayers.contains(id);
	}

	public java.util.Set<UUID> getInvolved() {
		java.util.Set<UUID> out = new java.util.HashSet<>();
		out.addAll(registered);
		out.addAll(participants.keySet());
		out.addAll(countdownPlayers);
		out.addAll(introPlayers);
		return out;
	}

	public boolean shouldPreventBoatExit(UUID id) {
		if (id == null)
			return false;
		// During live race: only active (not finished) racers
		if (running) {
			ParticipantState s = participants.get(id);
			return s != null && !s.finished;
		}
		// During countdown: keep registered racers seated
		return countdownTask != null && countdownPlayers.contains(id);
	}

	public boolean isCountdownActiveFor(UUID id) {
		if (id == null)
			return false;
		if (running)
			return false;
		return countdownTask != null && countdownPlayers.contains(id);
	}

	public org.bukkit.Location getCountdownLockLocation(UUID id) {
		if (id == null)
			return null;
		org.bukkit.Location l = countdownLockLocation.get(id);
		return l == null ? null : l.clone();
	}

	/**
	 * Respawn helpers:
	 * - If player is in countdown: respawn to their locked start position.
	 * - If player is racing: respawn at their last checkpoint; if all checkpoints
	 * reached, respawn at start.
	 * Returns null when the player isn't in countdown/race (let vanilla handle it).
	 */
	public org.bukkit.Location getRaceRespawnLocation(UUID id, org.bukkit.Location deathLocation) {
		if (id == null)
			return null;

		// Countdown: keep them at the locked start spot.
		if (isCountdownActiveFor(id)) {
			org.bukkit.Location lock = getCountdownLockLocation(id);
			if (lock != null)
				return lock;
		}

		ParticipantState s = participants.get(id);
		if (s == null || s.finished)
			return null;

		java.util.List<Region> cps = trackConfig.getCheckpoints();
		if (cps == null || cps.isEmpty()) {
			return getStartRespawnLocation(deathLocation);
		}

		// If they already reached all checkpoints for this lap (awaiting finish), put
		// them back at start.
		if (s.awaitingFinish || s.nextCheckpointIndex >= cps.size()) {
			return getStartRespawnLocation(deathLocation);
		}

		int lastIdx = s.nextCheckpointIndex - 1;
		if (lastIdx < 0) {
			return getStartRespawnLocation(deathLocation);
		}

		Region last = cps.get(lastIdx);
		org.bukkit.Location cp = getRegionRespawnLocation(last, deathLocation);
		return cp != null ? cp : getStartRespawnLocation(deathLocation);
	}

	/**
	 * UX action: respawn immediately.
	 * - During countdown: snap back to the locked start position.
	 * - During race: teleport to last checkpoint (or start if none).
	 */

	private void applyFacingFromCenterline(org.bukkit.Location loc) {
		if (loc == null || loc.getWorld() == null || trackConfig == null)
			return;
		java.util.List<org.bukkit.Location> cl;
		try {
			cl = trackConfig.getCenterline();
		} catch (Throwable ignored) {
			cl = java.util.Collections.emptyList();
		}
		if (cl == null || cl.size() < 2)
			return;

		int best = -1;
		double bestD = Double.POSITIVE_INFINITY;
		org.bukkit.World w = loc.getWorld();
		for (int i = 0; i < cl.size(); i++) {
			org.bukkit.Location n = cl.get(i);
			if (n == null || n.getWorld() == null || !n.getWorld().equals(w))
				continue;
			double d = n.distanceSquared(loc);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		if (best < 0)
			return;
		int next = Math.min(cl.size() - 1, best + 1);
		org.bukkit.Location a = cl.get(best);
		org.bukkit.Location b = cl.get(next);
		if (a == null || b == null)
			return;

		org.bukkit.util.Vector dir = b.toVector().subtract(a.toVector());
		if (dir.lengthSquared() < 1.0e-6)
			return;
		float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
		loc.setYaw(yaw);
		loc.setPitch(0.0f);
	}

	private boolean teleportVehicleRetainPassengers(org.bukkit.entity.Entity vehicle, org.bukkit.Location target) {
		if (vehicle == null || target == null || target.getWorld() == null)
			return false;
		try {
			vehicle.setVelocity(new Vector(0, 0, 0));
		} catch (Throwable ignored) {
		}

		boolean tpOk;
		try {
			tpOk = vehicle.teleport(target, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
		} catch (Throwable t) {
			try {
				tpOk = vehicle.teleport(target);
			} catch (Throwable ignored) {
				tpOk = false;
			}
		}

		if (!tpOk) {
			try {
				tpOk = dev.belikhun.boatracing.util.EntityForceTeleport.nms(vehicle, target);
			} catch (Throwable ignored) {
				tpOk = false;
			}
		}

		try {
			vehicle.setVelocity(new Vector(0, 0, 0));
		} catch (Throwable ignored) {
		}
		try {
			vehicle.setRotation(target.getYaw(), target.getPitch());
		} catch (Throwable ignored) {
		}
		return tpOk;
	}

	public boolean manualRespawnAtCheckpoint(Player p) {
		if (p == null || !p.isOnline())
			return false;
		UUID id = p.getUniqueId();

		// Countdown: snap to lock and ensure boat.
		if (isCountdownActiveFor(id)) {
			org.bukkit.Location lock = getCountdownLockLocation(id);
			if (lock != null) {
				try {
					if (lock.getWorld() == null && p.getWorld() != null)
						lock.setWorld(p.getWorld());
				} catch (Throwable ignored) {
				}
				try {
					if (lock.getWorld() != null) {
						org.bukkit.entity.Entity veh = null;
						try {
							veh = p.getVehicle();
						} catch (Throwable ignored2) {
							veh = null;
						}
						if (isBoatLike(veh)) {
							teleportVehicleRetainPassengers(veh, lock);
						} else {
							p.teleport(lock);
						}
					}
					p.setFallDistance(0f);
				} catch (Throwable ignored) {
				}
				try {
					ensureRacerHasBoat(p);
				} catch (Throwable ignored) {
				}
				try {
					p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.15f);
				} catch (Throwable ignored) {
				}
				try {
					Text.msg(p, "&a⟲ Đã đưa bạn về vị trí xuất phát.");
				} catch (Throwable ignored) {
				}
				return true;
			}
		}

		// Running race: use the same respawn logic as death.
		ParticipantState s = participants.get(id);
		if (s == null || s.finished)
			return false;

		org.bukkit.Location target = getRaceRespawnLocation(id, p.getLocation());
		if (target == null)
			return false;
		try {
			if (target.getWorld() == null && p.getWorld() != null)
				target.setWorld(p.getWorld());
		} catch (Throwable ignored) {
		}
		if (target.getWorld() == null)
			return false;

		// Face along the track direction if possible.
		try {
			applyFacingFromCenterline(target);
		} catch (Throwable ignored) {
		}

		try {
			org.bukkit.entity.Entity veh = null;
			try {
				veh = p.getVehicle();
			} catch (Throwable ignored2) {
				veh = null;
			}
			if (isBoatLike(veh)) {
				teleportVehicleRetainPassengers(veh, target);
			} else {
				p.teleport(target);
			}
			p.setFallDistance(0f);
		} catch (Throwable ignored) {
		}
		try {
			ensureRacerHasBoat(p);
		} catch (Throwable ignored) {
		}
		try {
			p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.15f);
		} catch (Throwable ignored) {
		}
		try {
			Text.msg(p, "&a⟲ Đã đưa bạn về checkpoint gần nhất.");
		} catch (Throwable ignored) {
		}
		return true;
	}

	public void ensureRacerHasBoat(Player p) {
		if (p == null || !p.isOnline())
			return;
		UUID id = p.getUniqueId();

		// Only apply to countdown/racing participants.
		if (!isCountdownActiveFor(id)) {
			ParticipantState s = participants.get(id);
			if (s == null || s.finished)
				return;
		}

		try {
			Entity curVeh = p.getVehicle();
			if (isBoatLike(curVeh)) {
				return;
			}
		} catch (Throwable ignored) {
		}

		// Remove prior plugin-spawned boat for this player if it still exists.
		try {
			UUID boatId = spawnedBoatByPlayer.get(id);
			if (boatId != null) {
				Entity e = p.getWorld().getEntity(boatId);
				if (e != null && isSpawnedBoat(e)) {
					try {
						e.remove();
					} catch (Throwable ignored) {
					}
				}
			}
		} catch (Throwable ignored) {
		}

		try {
			Location target = p.getLocation().clone();

			PreferredBoatData pref = resolvePreferredBoat(id);
			EntityType spawnType = resolveSpawnEntityType(pref);
			var ent = (target.getWorld() != null ? target.getWorld() : p.getWorld()).spawnEntity(target, spawnType);

			try {
				markSpawnedBoat(ent);
				spawnedBoatByPlayer.put(id, ent.getUniqueId());
			} catch (Throwable ignored) {
			}

			String base = pref.baseType;
			if (ent instanceof Boat b) {
				if (base != null) {
					try {
						b.setBoatType(Boat.Type.valueOf(base));
					} catch (Throwable ignored) {
					}
				}
				try {
					b.addPassenger(p);
				} catch (Throwable ignored) {
					try {
						if (p.isInsideVehicle())
							p.leaveVehicle();
					} catch (Throwable ignored2) {
					}
					try {
						b.addPassenger(p);
					} catch (Throwable ignored2) {
					}
				}
			} else if (ent instanceof ChestBoat cb) {
				if (base != null) {
					try {
						cb.setBoatType(Boat.Type.valueOf(base));
					} catch (Throwable ignored) {
					}
				}
				try {
					cb.addPassenger(p);
				} catch (Throwable ignored) {
					try {
						if (p.isInsideVehicle())
							p.leaveVehicle();
					} catch (Throwable ignored2) {
					}
					try {
						cb.addPassenger(p);
					} catch (Throwable ignored2) {
					}
				}
			} else {
				try {
					ent.addPassenger(p);
				} catch (Throwable ignored) {
				}
			}

			// If they're in countdown, update their lock location to the new boat spot.
			try {
				if (isCountdownActiveFor(id)) {
					Entity v = p.getVehicle();
					if (v != null)
						countdownLockLocation.put(id, v.getLocation().clone());
					else
						countdownLockLocation.put(id, p.getLocation().clone());
				}
			} catch (Throwable ignored) {
			}

			if (debugBoatSelection()) {
				try {
					dbg("[BOATDBG] ensureRacerHasBoat player=" + p.getName() + " raw='" + pref.raw + "' spawnType="
							+ spawnType.name() + " base=" + base);
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private org.bukkit.Location getStartRespawnLocation(org.bukkit.Location deathLocation) {
		org.bukkit.Location base = null;
		try {
			base = trackConfig.getStartCenter();
		} catch (Throwable ignored) {
		}
		if (base == null) {
			try {
				java.util.List<org.bukkit.Location> starts = trackConfig.getStarts();
				if (starts != null && !starts.isEmpty())
					base = starts.get(0);
			} catch (Throwable ignored) {
			}
		}

		World w = (base != null) ? base.getWorld() : null;
		if (w == null && deathLocation != null)
			w = deathLocation.getWorld();
		if (w == null)
			return null;

		double x = (base != null) ? base.getX() : w.getSpawnLocation().getX();
		double z = (base != null) ? base.getZ() : w.getSpawnLocation().getZ();
		int yHint = (deathLocation != null) ? deathLocation.getBlockY()
				: ((base != null) ? base.getBlockY() : w.getSpawnLocation().getBlockY());
		float yaw = (deathLocation != null) ? deathLocation.getYaw() : ((base != null) ? base.getYaw() : 0f);
		float pitch = (deathLocation != null) ? deathLocation.getPitch() : ((base != null) ? base.getPitch() : 0f);

		return safeSpawnAt(w, x, z, yHint, yaw, pitch);
	}

	private org.bukkit.Location getRegionRespawnLocation(Region r, org.bukkit.Location deathLocation) {
		if (r == null)
			return null;
		BoundingBox b = null;
		try {
			b = r.getBox();
		} catch (Throwable ignored) {
		}
		if (b == null)
			return null;

		World w = null;
		try {
			String wn = r.getWorldName();
			if (wn != null)
				w = Bukkit.getWorld(wn);
		} catch (Throwable ignored) {
		}
		if (w == null && deathLocation != null)
			w = deathLocation.getWorld();
		if (w == null)
			return null;

		double x = (Math.min(b.getMinX(), b.getMaxX()) + Math.max(b.getMinX(), b.getMaxX())) * 0.5;
		double z = (Math.min(b.getMinZ(), b.getMaxZ()) + Math.max(b.getMinZ(), b.getMaxZ())) * 0.5;
		int yHint = (deathLocation != null) ? deathLocation.getBlockY()
				: (int) Math.round((b.getMinY() + b.getMaxY()) * 0.5);
		float yaw = (deathLocation != null) ? deathLocation.getYaw() : 0f;
		float pitch = (deathLocation != null) ? deathLocation.getPitch() : 0f;
		return safeSpawnAt(w, x, z, yHint, yaw, pitch);
	}

	private static org.bukkit.Location safeSpawnAt(World w, double x, double z, int yHint, float yaw, float pitch) {
		if (w == null)
			return null;
		int minY = w.getMinHeight();
		int maxY = w.getMaxHeight() - 2;

		int hint = Math.max(minY, Math.min(maxY, yHint));
		int best = Integer.MIN_VALUE;

		// Prefer a nearby solid block under the hint (tracks are usually flat-ish).
		for (int dy = 0; dy <= 12; dy++) {
			int y = hint - dy;
			if (y < minY)
				break;
			try {
				org.bukkit.block.Block below = w.getBlockAt((int) Math.floor(x), y, (int) Math.floor(z));
				org.bukkit.block.Block above = w.getBlockAt((int) Math.floor(x), y + 1, (int) Math.floor(z));
				if (below.getType().isSolid() && above.getType().isAir()) {
					best = y;
					break;
				}
			} catch (Throwable ignored) {
			}
		}

		// Fallback: world column top.
		if (best == Integer.MIN_VALUE) {
			try {
				best = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
			} catch (Throwable ignored) {
				best = w.getSpawnLocation().getBlockY();
			}
		}

		double spawnY = best + 1.0;
		return new org.bukkit.Location(w, x + 0.5, spawnY, z + 0.5, yaw, pitch);
	}

	/**
	 * Called on player movement to detect checkpoint/pit/finish crossings
	 */
	public void tickPlayer(Player player, Location to) {
		tickPlayer(player, null, to);
	}

	/**
	 * Called on movement with both endpoints so we can do swept intersection
	 * checks.
	 */
	public void tickPlayer(Player player, Location from, Location to) {
		if (!running)
			return;
		if (to == null)
			return;
		ParticipantState s = participants.get(player.getUniqueId());
		if (s == null || s.finished)
			return;

		Location segFrom = from;
		if (segFrom == null)
			segFrom = s.lastTickLocation;
		// If this is the first tick, just seed last location.
		if (segFrom == null) {
			s.lastTickLocation = to.clone();
			return;
		}
		// World mismatch: reset seed.
		if (segFrom.getWorld() == null || to.getWorld() == null || !segFrom.getWorld().equals(to.getWorld())) {
			s.lastTickLocation = to.clone();
			return;
		}

		// Track total traveled distance (used for average speed on completion).
		try {
			double dist = segFrom.distance(to);
			if (Double.isFinite(dist) && dist > 0.0 && dist <= 25.0) {
				s.distanceBlocks += dist;
			}
		} catch (Throwable ignored) {
		}

		// Pit mechanic removed

		// Checkpoints
		java.util.List<Region> checkpoints = trackConfig.getCheckpoints();

		// Debug-only: report which checkpoint region (if any) the segment intersects.
		if (debugCheckpoints()) {
			int insideAny = -1;
			for (int i = 0; i < checkpoints.size(); i++) {
				Region r = checkpoints.get(i);
				if (r != null && (r.containsXZ(to) || r.intersectsXZ(segFrom, to))) {
					insideAny = i;
					break;
				}
			}
			if (insideAny != s.lastInsideCheckpoint) {
				s.lastInsideCheckpoint = insideAny;
				if (insideAny >= 0) {
					dbg("[CPDBG] " + player.getName() + " entered checkpoint " + (insideAny + 1) + "/"
							+ checkpoints.size()
							+ " at " + dev.belikhun.boatracing.util.Text.fmtPos(to)
							+ " expectedNext=" + (s.nextCheckpointIndex + 1)
							+ " awaitingFinish=" + s.awaitingFinish);
				}
			}
		}

		// Gameplay: only advance in sequence, and allow consuming multiple checkpoints
		// in a single swept segment.
		if (!checkpoints.isEmpty() && !s.awaitingFinish) {
			int advancedCount = 0;
			while (advancedCount < 6 && s.nextCheckpointIndex >= 0 && s.nextCheckpointIndex < checkpoints.size()) {
				Region expected = checkpoints.get(s.nextCheckpointIndex);
				boolean hitExpected = expected != null
						&& (expected.containsXZ(to) || expected.intersectsXZ(segFrom, to));
				if (!hitExpected)
					break;

				int hitIndex = s.nextCheckpointIndex;
				boolean advanced = checkpointReachedInternal(player.getUniqueId(), hitIndex);
				if (advanced) {
					notifyCheckpointPassed(player, hitIndex + 1, checkpoints.size());
					advancedCount++;
					if (s.awaitingFinish)
						break;
					continue;
				}
				break;
			}
		}

		if (debugCheckpoints() && !s.awaitingFinish && s.nextCheckpointIndex >= 0
				&& s.nextCheckpointIndex < checkpoints.size()) {
			Region expected = checkpoints.get(s.nextCheckpointIndex);
			if (expected != null) {
				org.bukkit.util.BoundingBox b = expected.getBox();
				org.bukkit.World w = to.getWorld();
				if (b != null && w != null && expected.getWorldName() != null
						&& expected.getWorldName().equals(w.getName())) {
					// Match Region.containsXZ(): treat as block-selection in X/Z with +1 upper
					// bounds.
					double minX = Math.min(b.getMinX(), b.getMaxX());
					double maxX = Math.max(b.getMinX(), b.getMaxX()) + 1.0;
					double minZ = Math.min(b.getMinZ(), b.getMaxZ());
					double maxZ = Math.max(b.getMinZ(), b.getMaxZ()) + 1.0;

					double x = to.getX();
					double z = to.getZ();
					double cx = clamp(x, minX, maxX);
					double cz = clamp(z, minZ, maxZ);
					double dx = x - cx;
					double dz = z - cz;
					double dist = Math.sqrt(dx * dx + dz * dz);
					int bucket = (int) Math.floor(dist); // 0..n
					if (dist <= 4.0 && bucket != s.lastNearExpectedBucket) {
						s.lastNearExpectedBucket = bucket;
						dbg("[CPDBG] " + player.getName() + " near expected checkpoint " + (s.nextCheckpointIndex + 1)
								+ " dist=" + String.format(java.util.Locale.US, "%.2f", dist)
								+ " pos=" + dev.belikhun.boatracing.util.Text.fmtPos(to)
								+ " boxXZ=[" + minX + "," + minZ + "]..[" + (maxX - 1.0) + "," + (maxZ - 1.0) + "]");
					}
					if (dist > 6.0)
						s.lastNearExpectedBucket = -1;
				}
			}
		}

		// Finish detection
		// IMPORTANT: For checkpoint-based tracks, lap completion is driven by
		// checkpoint flow.
		// Finish should only be used as lap completion when there are NO checkpoints.
		Region finish = trackConfig.getFinish();
		boolean inFinish = finish != null && (finish.containsXZ(to) || finish.intersectsXZ(segFrom, to));
		boolean enteredFinish = inFinish && !s.wasInsideFinish;
		s.wasInsideFinish = inFinish;

		if (debugCheckpoints() && enteredFinish) {
			dbg("[CPDBG] " + player.getName() + " entered finish at " + dev.belikhun.boatracing.util.Text.fmtPos(to)
					+ " nextCheckpointIndex=" + (s.nextCheckpointIndex + 1));
		}
		if (enteredFinish) {
			if (checkpoints.isEmpty()) {
				completeLap(player.getUniqueId(), to);
			} else if (s.awaitingFinish) {
				s.awaitingFinish = false;
				s.nextCheckpointIndex = 0;
				completeLap(player.getUniqueId(), to);
			} else if (debugCheckpoints()) {
				dbg("[CPDBG] " + player.getName() + " entered finish but lap not ready (expectedNext="
						+ (s.nextCheckpointIndex + 1) + ")");
			}
		}

		// Update live path index for player (for live positions)
		if (pathReady) {
			int seed = s.lastPathIndex;
			if (s.awaitingFinish && gateIndex != null && gateIndex.length > 1) {
				// IMPORTANT: while awaiting finish (after last checkpoint), do NOT seed toward
				// the finish gate. Doing so can snap the nearestPathIndex to the finish side of
				// the loop and make lap progress jump to 100% instantly.
				// Instead, bias toward the last checkpoint gate so progress stays live.
				int cpCount;
				try {
					cpCount = trackConfig != null && trackConfig.getCheckpoints() != null
							? trackConfig.getCheckpoints().size()
							: 0;
				} catch (Throwable ignored) {
					cpCount = 0;
				}

				int lastCpGateIdx = Math.max(0, Math.min(cpCount - 1, gateIndex.length - 2));
				if (cpCount > 0) {
					seed = gateIndex[lastCpGateIdx];
				}
			} else if (s.nextCheckpointIndex == 0 && s.currentLap > 0) {
				// After wrapping a lap, bias toward the finish gate (lap start).
				seed = finishGateIndex();
			}
			s.lastPathIndex = nearestPathIndex(to, seed, 80);
		}

		// Update last location for next swept tick.
		s.lastTickLocation = to.clone();
	}

	// test hook: allow tests to simulate checkpoints without needing Region
	// instances
	private int testCheckpointCount = -1;

	void setTestCheckpointCount(int n) {
		this.testCheckpointCount = n;
	}

	// package-private helpers for testing and fine-grained control
	void checkpointReached(UUID uuid, int checkpointIndex) {
		checkpointReachedInternal(uuid, checkpointIndex);
	}

	private boolean checkpointReachedInternal(UUID uuid, int checkpointIndex) {
		ParticipantState s = participants.get(uuid);
		if (s == null || s.finished)
			return false;
		if (checkpointIndex != s.nextCheckpointIndex)
			return false; // enforce sequence
		s.nextCheckpointIndex++;
		int totalCheckpoints = testCheckpointCount >= 0 ? testCheckpointCount : trackConfig.getCheckpoints().size();
		if (totalCheckpoints > 0 && s.nextCheckpointIndex >= totalCheckpoints) {
			// Completed all checkpoints for this lap; now require crossing the finish line
			// to complete the lap.
			s.nextCheckpointIndex = totalCheckpoints;
			s.awaitingFinish = true;
		}
		return true;
	}

	public ParticipantState getParticipantState(UUID uuid) {
		return participants.get(uuid);
	}

	/**
	 * Debug helper: returns the current participant state if present, without creating one.
	 */
	public ParticipantState peekParticipantState(UUID uuid) {
		if (uuid == null)
			return null;
		return participants.get(uuid);
	}

	// test helper: add a participant without needing a Player or a running race
	void addParticipantForTests(UUID uuid) {
		participants.put(uuid, new ParticipantState(uuid));
	}

	// test hook: allow tests to simulate finish crossing
	void finishCrossedForTests(UUID uuid) {
		ParticipantState s = participants.get(uuid);
		if (s == null || s.finished)
			return;
		// For checkpoint tracks, only complete lap when awaiting finish.
		if ((testCheckpointCount >= 0 ? testCheckpointCount : trackConfig.getCheckpoints().size()) > 0) {
			if (!s.awaitingFinish)
				return;
			s.awaitingFinish = false;
			s.nextCheckpointIndex = 0;
		}
		completeLap(uuid, null);
	}

	void handleLapCompletion(UUID uuid) {
		// Backward-compatible alias (kept for existing callers). Prefer completeLap.
		completeLap(uuid, null);
	}

	private void completeLap(UUID uuid, Location pos) {
		ParticipantState s = participants.get(uuid);
		if (s == null || s.finished)
			return;
		s.currentLap++;
		// Pit mechanic removed: no penalties or pit flags

		// Finished?
		if (s.currentLap >= getTotalLaps()) {
			finishPlayer(uuid);
		} else {
			Player p = participantPlayers.get(uuid);
			if (p != null) {
				notifyLapCompleted(p, s.currentLap, getTotalLaps());
			}
		}

		// Reseed path index to avoid progress getting stuck after lap wrap.
		if (pathReady) {
			int seed = finishGateIndex();
			if (pos != null)
				s.lastPathIndex = nearestPathIndex(pos, seed, Math.max(200, path.size()));
			else
				s.lastPathIndex = seed;
		}
	}

	private void notifyCheckpointPassed(Player p, int passed, int total) {
		try {
			var sub = net.kyori.adventure.text.Component.text("✔ " + passed + "/" + total)
					.color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
			p.showTitle(net.kyori.adventure.title.Title.title(
					net.kyori.adventure.text.Component.empty(),
					sub,
					net.kyori.adventure.title.Title.Times.times(
							java.time.Duration.ofMillis(100),
							java.time.Duration.ofMillis(700),
							java.time.Duration.ofMillis(200))));
			p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.6f);
		} catch (Throwable ignored) {
		}
	}

	private void notifyLapCompleted(Player p, int lap, int total) {
		try {
			var sub = net.kyori.adventure.text.Component.text("🗘 " + lap + "/" + total)
					.color(net.kyori.adventure.text.format.NamedTextColor.GREEN);
			p.showTitle(net.kyori.adventure.title.Title.title(
					net.kyori.adventure.text.Component.empty(),
					sub,
					net.kyori.adventure.title.Title.Times.times(
							java.time.Duration.ofMillis(100),
							java.time.Duration.ofMillis(900),
							java.time.Duration.ofMillis(250))));
			p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.15f);
		} catch (Throwable ignored) {
		}
	}

	void finishPlayer(UUID uuid) {
		ParticipantState s = participants.get(uuid);
		if (s == null || s.finished)
			return;
		s.finished = true;
		s.finishTimeMillis = System.currentTimeMillis();
		// compute position as number of already finished + 1
		int pos = (int) participants.values().stream().filter(x -> x.finished && x.finishTimeMillis > 0).count();
		s.finishPosition = Math.max(1, pos);

		String finisherName = null;
		try {
			org.bukkit.entity.Player online = participantPlayers.get(uuid);
			finisherName = (online != null ? online.getName() : null);
		} catch (Throwable ignored) {
			finisherName = null;
		}
		if (finisherName == null || finisherName.isBlank()) {
			try {
				org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(uuid);
				finisherName = (op != null ? op.getName() : null);
			} catch (Throwable ignored) {
				finisherName = null;
			}
		}
		if (finisherName == null)
			finisherName = "";

		// Profile metric: total time raced (only count finished races).
		try {
			if (plugin instanceof dev.belikhun.boatracing.BoatRacingPlugin br && br.getProfileManager() != null) {
				long rawMs = Math.max(0L, s.finishTimeMillis - getRaceStartMillis());
				long penaltyMs = Math.max(0L, s.penaltySeconds) * 1000L;
				long totalMs = rawMs + penaltyMs;
				br.getProfileManager().addTimeRacedMillis(uuid, totalMs);

				// Count this race completion.
				try {
					br.getProfileManager().incCompleted(uuid);
				} catch (Throwable ignored) {
				}

				// Win only counts if at least 2 racers started this track.
				try {
					if (raceStartRacerCount >= 2 && s.finishPosition == 1)
						br.getProfileManager().incWins(uuid);
				} catch (Throwable ignored) {
				}

				// Update track record (global) + personal best (per track).
				try {
					String trackName = null;
					try {
						trackName = (trackConfig != null ? trackConfig.getCurrentName() : null);
					} catch (Throwable ignored) {
						trackName = null;
					}
					if (trackName != null && !trackName.isBlank()) {
						String holderName = null;
						try {
							org.bukkit.entity.Player online = participantPlayers.get(uuid);
							holderName = (online != null ? online.getName() : null);
						} catch (Throwable ignored) {
							holderName = null;
						}
						if (holderName == null || holderName.isBlank()) {
							try {
								org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(uuid);
								holderName = (op != null ? op.getName() : null);
							} catch (Throwable ignored) {
								holderName = null;
							}
						}
						if (holderName == null)
							holderName = "";

						try {
							if (br.getTrackRecordManager() != null) {
								boolean improved = br.getTrackRecordManager().updateIfBetter(trackName, uuid,
										holderName, totalMs);
								if (improved) {
									try {
										broadcastNewTrackRecord(trackName, uuid, holderName, totalMs);
									} catch (Throwable ignored) {
									}
								}
							}
						} catch (Throwable ignored) {
						}
						try {
							br.getProfileManager().updatePersonalBestIfBetter(uuid, trackName, totalMs);
						} catch (Throwable ignored) {
						}
					}
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}

		// Broadcast finish message to other racers in this track.
		try {
			long rawMs = Math.max(0L, s.finishTimeMillis - getRaceStartMillis());
			long penaltyMs = Math.max(0L, s.penaltySeconds) * 1000L;
			long totalMs = rawMs + penaltyMs;
			broadcastRacerFinished(uuid, finisherName, s.finishPosition, totalMs);
		} catch (Throwable ignored) {
		}

		Player p = participantPlayers.get(uuid);
		if (p != null) {
			// Rich finish board (10 lines) in vanilla chat height.
			try {
				sendFinishBoard(p, s);
			} catch (Throwable ignored) {
			}
			try {
				p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
			} catch (Throwable ignored) {
			}
		}

		// If everybody is finished, immediately reset start lights and schedule a
		// cleanup.
		try {
			boolean allFinished = !participants.isEmpty()
					&& participants.values().stream().allMatch(x -> x != null && x.finished);
			if (allFinished) {
				// Mark race as ended so end-of-race effects (fireworks/scoreboard) can run.
				running = false;
				try {
					stopRaceTicker();
				} catch (Throwable ignored) {
				}
				try {
					setStartLightsProgress(0.0);
				} catch (Throwable ignored) {
				}

				// Track completed: remove checkpoint markers immediately (user expects them
				// gone on completion).
				try {
					clearCheckpointHolos();
				} catch (Throwable ignored) {
				}

				// Celebration: fireworks + switch everyone in this race to spectator.
				try {
					spawnAllFinishedFireworks();
				} catch (Throwable ignored) {
				}
				try {
					setAllInvolvedSpectator();
				} catch (Throwable ignored) {
				}

				// After the race is fully completed, send 2 boards in chat separated by 5 seconds.
				try {
					cancelResultsBoards();
					java.util.List<ParticipantState> standings = getStandings();
					java.util.List<String> top = buildResultsTop3BoardLines(standings);
					java.util.List<String> rest = buildResultsRestBoardLines(standings);
					if (plugin != null) {
						resultsTopBoardTask = plugin.getServer().getScheduler().runTask(plugin, () -> {
							try {
								broadcastChatBoard(top);
							} catch (Throwable ignored) {
							}
							resultsTopBoardTask = null;
						});
						resultsRestBoardTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
							try {
								broadcastChatBoard(rest);
							} catch (Throwable ignored) {
							}
							resultsRestBoardTask = null;
						}, 5L * 20L);
					}
				} catch (Throwable ignored) {
				}

				if (plugin != null) {
					int sec = 15;
					try {
						sec = Math.max(0, plugin.getConfig().getInt("racing.post-finish-cleanup-seconds", 15));
					} catch (Throwable ignored) {
					}

					if (postFinishCleanupTask != null) {
						try {
							postFinishCleanupTask.cancel();
						} catch (Throwable ignored) {
						}
						postFinishCleanupTask = null;
					}
					postFinishCleanupEndMillis = (sec > 0) ? (System.currentTimeMillis() + (sec * 1000L)) : 0L;

					if (sec <= 0) {
						// IMPORTANT: Don't stop immediately.
						// EventService ticks every 20 ticks (1s) and detects completion via
						// areAllParticipantsFinished(). If we stop(false) right away here, we clear
						// participants before EventService can award points / advance track / teleport.
						final long delayTicks = 40L; // 2 seconds => guarantees at least one EventService tick
						postFinishCleanupEndMillis = System.currentTimeMillis() + (delayTicks * 50L);
						postFinishCleanupTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
							try {
								stop(false);
							} catch (Throwable ignored) {
							}
							postFinishCleanupEndMillis = 0L;
							postFinishCleanupTask = null;
						}, delayTicks);
					} else {
						postFinishCleanupTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
							try {
								stop(false);
							} catch (Throwable ignored) {
							}
							postFinishCleanupEndMillis = 0L;
							postFinishCleanupTask = null;
						}, sec * 20L);
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static String fmt1(double v) {
		if (!Double.isFinite(v))
			return "0.0";
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static String fmt2(double v) {
		if (!Double.isFinite(v))
			return "0.00";
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private void sendFinishBoard(Player p, ParticipantState s) {
		if (p == null || s == null)
			return;

		int racersTotal = Math.max(1, participants.size());
		int place = (s.finishPosition > 0 ? s.finishPosition : 1);

		long rawMs = Math.max(0L, s.finishTimeMillis - getRaceStartMillis());
		long penaltyMs = Math.max(0L, s.penaltySeconds) * 1000L;
		long finalMs = rawMs + penaltyMs;

		double dist = Math.max(0.0, s.distanceBlocks);
		double seconds = Math.max(0.001, finalMs / 1000.0);
		double avgBps = dist / seconds;
		double avgKmh = avgBps * 3.6;
		long avgLapMs = (getTotalLaps() <= 0) ? finalMs : (finalMs / (long) getTotalLaps());

		String track = null;
		try {
			track = trackConfig != null ? trackConfig.getCurrentName() : null;
		} catch (Throwable ignored) {
			track = null;
		}
		if (track == null || track.isBlank())
			track = "(không rõ)";

		int cps = 0;
		try {
			cps = (trackConfig != null && trackConfig.getCheckpoints() != null) ? trackConfig.getCheckpoints().size()
					: 0;
		} catch (Throwable ignored) {
			cps = 0;
		}

		// 10 lines total (Minecraft default chat height).
		Text.tell(p, "&6&l┏━━━━━━━━━━━━━━━━━━━━━━ &eKẾT QUẢ &6&l━━━━━━━━━━━━━━━━━━━━━━┓");
		Text.tell(p, "&eHạng: &f#" + place + "&7/&f" + racersTotal + "   &8●   &eThời gian: &f"
				+ Time.formatStopwatchMillis(finalMs));
		Text.tell(p, "&eThời gian thực: &f" + Time.formatStopwatchMillis(rawMs) + "   &8●   &ePhạt: &c+"
				+ Time.formatStopwatchMillis(penaltyMs));
		Text.tell(p, "&eĐường đua: &f" + track);
		Text.tell(p,
				"&eVòng: &f" + getTotalLaps() + "/" + getTotalLaps() + "   &8●   &eCheckpoint: &f" + cps + "&7/vòng");
		Text.tell(p, "&eQuãng đường: &f" + fmt1(dist) + "&7m");
		Text.tell(p, "&eTốc độ TB: &f" + fmt2(avgBps) + "&7 bps &8(≈ &f" + fmt2(avgKmh) + "&7 km/h)");
		Text.tell(p, "&eTB mỗi vòng: &f" + Time.formatStopwatchMillis(avgLapMs));
		Text.tell(p, "&7Gợi ý: &f/boatracing profile &7để chỉnh màu/số/biểu tượng.");
		Text.tell(p, "&6&l┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛");
	}

	private void setAllInvolvedSpectator() {
		if (plugin == null)
			return;
		for (UUID id : getInvolved()) {
			if (id == null)
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				previousGameModes.putIfAbsent(id, p.getGameMode());
				p.setGameMode(org.bukkit.GameMode.SPECTATOR);
			} catch (Throwable ignored) {
			}
		}
	}

	private void restorePreviousGameModes() {
		if (plugin == null) {
			previousGameModes.clear();
			return;
		}
		for (var en : new java.util.HashMap<>(previousGameModes).entrySet()) {
			UUID id = en.getKey();
			org.bukkit.GameMode gm = en.getValue();
			if (id == null || gm == null)
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				p.setGameMode(gm);
			} catch (Throwable ignored) {
			}
		}
		previousGameModes.clear();
	}

	private void stopAllFinishedFireworks() {
		if (allFinishedFireworksTask != null) {
			try {
				allFinishedFireworksTask.cancel();
			} catch (Throwable ignored) {
			}
			allFinishedFireworksTask = null;
		}
	}

	private static org.bukkit.Color randomFestiveColor(java.util.Random rnd) {
		if (rnd == null)
			rnd = new java.util.Random();
		org.bukkit.Color[] colors = new org.bukkit.Color[] {
				org.bukkit.Color.RED,
				org.bukkit.Color.LIME,
				org.bukkit.Color.AQUA,
				org.bukkit.Color.YELLOW,
				org.bukkit.Color.FUCHSIA,
				org.bukkit.Color.ORANGE,
				org.bukkit.Color.WHITE,
				org.bukkit.Color.PURPLE,
				org.bukkit.Color.BLUE
		};
		return colors[rnd.nextInt(colors.length)];
	}

	private void spawnAllFinishedFireworks() {
		if (plugin == null)
			return;
		if (allFinishedFireworksTask != null)
			return;

		org.bukkit.Location base = null;
		try {
			Region fin = trackConfig.getFinish();
			if (fin != null)
				base = centerOf(fin);
		} catch (Throwable ignored) {
			base = null;
		}
		if (base == null) {
			try {
				base = trackConfig.getStartCenter();
			} catch (Throwable ignored) {
				base = null;
			}
		}
		if (base == null || base.getWorld() == null)
			return;

		final org.bukkit.Location origin = base.clone();
		final java.util.Random rnd = new java.util.Random();
		final double radius = 8.0;

		// Periodic, festive show around the finish line. Runs until the track is closed
		// (stop()).
		allFinishedFireworksTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
			if (plugin == null) {
				stopAllFinishedFireworks();
				return;
			}
			// NOTE: Do not cancel just because 'running' was true; we flip running=false
			// when all finish.
			// Starting a new countdown explicitly stops this task.

			org.bukkit.World w = origin.getWorld();
			if (w == null)
				return;

			int perBurst = 2;
			for (int i = 0; i < perBurst; i++) {
				double a = rnd.nextDouble() * Math.PI * 2.0;
				double r = Math.sqrt(rnd.nextDouble()) * radius;
				double dx = Math.cos(a) * r;
				double dz = Math.sin(a) * r;

				org.bukkit.Location spawn = origin.clone().add(dx, 0.0, dz);
				spawn.setY(spawn.getY() + 1.8 + (rnd.nextDouble() * 0.6));

				try {
					org.bukkit.entity.Firework fw = w.spawn(spawn, org.bukkit.entity.Firework.class);
					try {
						dev.belikhun.boatracing.race.RaceFx.markFirework(plugin, fw);
					} catch (Throwable ignored) {
					}
					try {
						fw.setSilent(true);
					} catch (Throwable ignored) {
					}
					try {
						fw.setInvulnerable(true);
					} catch (Throwable ignored) {
					}
					org.bukkit.inventory.meta.FireworkMeta meta = fw.getFireworkMeta();
					meta.setPower(1); // fly up a bit

					org.bukkit.FireworkEffect.Type type = switch (rnd.nextInt(4)) {
						case 0 -> org.bukkit.FireworkEffect.Type.BALL;
						case 1 -> org.bukkit.FireworkEffect.Type.BALL_LARGE;
						case 2 -> org.bukkit.FireworkEffect.Type.BURST;
						default -> org.bukkit.FireworkEffect.Type.STAR;
					};

					meta.addEffect(org.bukkit.FireworkEffect.builder()
							.with(type)
							.flicker(true)
							.trail(true)
							.withColor(
									randomFestiveColor(rnd),
									randomFestiveColor(rnd),
									randomFestiveColor(rnd))
							.build());
					fw.setFireworkMeta(meta);

					try {
						fw.setVelocity(new Vector(
								(rnd.nextDouble() - 0.5) * 0.15,
								0.35 + rnd.nextDouble() * 0.15,
								(rnd.nextDouble() - 0.5) * 0.15));
					} catch (Throwable ignored) {
					}

					plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
						try {
							fw.detonate();
						} catch (Throwable ignored) {
						}
					}, 8L + rnd.nextInt(10));
				} catch (Throwable ignored) {
				}
			}
		}, 0L, 12L);
	}

	/**
	 * Get standings ordered by finish time + penalty (unfinished players last)
	 */
	public List<ParticipantState> getStandings() {
		List<ParticipantState> out = new ArrayList<>(participants.values());
		out.sort((a, b) -> {
			if (a.finished && b.finished) {
				long ta = a.finishTimeMillis + a.penaltySeconds * 1000L;
				long tb = b.finishTimeMillis + b.penaltySeconds * 1000L;
				return Long.compare(ta, tb);
			} else if (a.finished)
				return -1;
			else if (b.finished)
				return 1;
			else
				return Long.compare(b.currentLap, a.currentLap); // more laps ahead first
		});
		return out;
	}

	/**
	 * Lightweight check: whether any participant has finished.
	 * Avoids building/sorting standings when only a boolean is needed.
	 */
	public boolean hasAnyFinishedParticipant() {
		for (ParticipantState s : participants.values()) {
			if (s != null && s.finished)
				return true;
		}
		return false;
	}

	/**
	 * Lightweight check: whether all participants have finished.
	 * Returns false if there are no participants.
	 */
	public boolean areAllParticipantsFinished() {
		if (participants.isEmpty())
			return false;
		for (ParticipantState s : participants.values()) {
			if (s == null || !s.finished)
				return false;
		}
		return true;
	}

	// Simple state holder
	public static class ParticipantState {
		public final UUID id;
		public int currentLap = 0;
		public int nextCheckpointIndex = 0;
		// pit flags removed
		public boolean finished = false;
		public long finishTimeMillis = 0;
		public int finishPosition = 0;
		public int penaltySeconds = 0;
		public int lastPathIndex = 0; // nearest node index along centerline for live positions

		// Total distance traveled during the race (blocks). Teleports are filtered out.
		public double distanceBlocks = 0.0;

		// Last position seen (used for swept intersection checks).
		public org.bukkit.Location lastTickLocation = null;

		// Debug-only state (to avoid spam)
		public int lastInsideCheckpoint = -1;
		public int lastNearExpectedBucket = -1;

		// Used to edge-trigger finish crossings (avoid repeated lap completions when
		// inside the finish area)
		public boolean wasInsideFinish = false;

		// For checkpoint tracks: last checkpoint reached, now waiting to cross finish
		// to complete the lap.
		public boolean awaitingFinish = false;

		public ParticipantState(UUID id) {
			this.id = id;
		}
	}

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	public int getTotalLaps() {
		return Math.max(1, totalLaps);
	}

	private String safeTrackName() {
		try {
			String n = trackConfig != null ? trackConfig.getCurrentName() : null;
			if (n != null && !n.isBlank())
				return n;
		} catch (Throwable ignored) {
		}
		return "(không rõ)";
	}

	private CinematicCameraService cinematic() {
		try {
			if (plugin instanceof BoatRacingPlugin br)
				return br.getCinematicCameraService();
		} catch (Throwable ignored) {
		}
		return null;
	}

	private boolean introEnabled() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("racing.intro.enabled", true);
		} catch (Throwable ignored) {
			return true;
		}
	}

	private int introPoints() {
		try {
			return Math.max(1, plugin != null ? plugin.getConfig().getInt("racing.intro.points", 4) : 4);
		} catch (Throwable ignored) {
			return 4;
		}
	}

	private int introSegmentTicks() {
		// Hardcoded to 75 ticks to match the 14.4s music loop (4 points * 75 ticks + hold = ~315 ticks)
		return 75;
	}

	private double introRadius() {
		try {
			return Math.max(2.0, plugin != null ? plugin.getConfig().getDouble("racing.intro.radius", 16.0) : 16.0);
		} catch (Throwable ignored) {
			return 16.0;
		}
	}

	private double introHeight() {
		try {
			return Math.max(2.0, plugin != null ? plugin.getConfig().getDouble("racing.intro.height", 12.0) : 12.0);
		} catch (Throwable ignored) {
			return 12.0;
		}
	}

	private java.util.List<Location> introCandidateTargets() {
		java.util.List<Location> out = new java.util.ArrayList<>();
		try {
			java.util.List<Location> cl = trackConfig != null ? trackConfig.getCenterline() : null;
			if (cl != null) {
				for (Location l : cl) {
					if (l != null && l.getWorld() != null)
						out.add(l.clone());
				}
			}
		} catch (Throwable ignored) {
		}

		try {
			java.util.List<Region> cps = trackConfig != null ? trackConfig.getCheckpoints() : null;
			if (cps != null) {
				for (Region r : cps) {
					try {
						Location c = centerOf(r);
						if (c != null && c.getWorld() != null)
							out.add(c);
					} catch (Throwable ignored2) {
					}
				}
			}
		} catch (Throwable ignored) {
		}
		try {
			if (trackConfig != null && trackConfig.getFinish() != null) {
				Location f = centerOf(trackConfig.getFinish());
				if (f != null && f.getWorld() != null)
					out.add(f);
			}
		} catch (Throwable ignored) {
		}
		try {
			Location s = trackConfig != null ? trackConfig.getStartCenter() : null;
			if (s != null && s.getWorld() != null)
				out.add(s.clone());
		} catch (Throwable ignored) {
		}

		java.util.List<Location> dedup = new java.util.ArrayList<>();
		for (Location l : out) {
			if (l == null || l.getWorld() == null)
				continue;
			boolean exists = false;
			for (Location e : dedup) {
				if (e == null || e.getWorld() == null)
					continue;
				if (!e.getWorld().equals(l.getWorld()))
					continue;
				try {
					if (e.distanceSquared(l) <= 4.0 * 4.0) {
						exists = true;
						break;
					}
				} catch (Throwable ignored) {
				}
			}
			if (!exists)
				dedup.add(l);
		}
		return dedup;
	}

	private CinematicSequence buildIntroSequence(java.util.List<Location> targets, java.util.List<Player> audience) {
		if (targets == null || targets.isEmpty())
			return null;
		Random rnd = new Random();
		int points = Math.max(1, introPoints());
		int flyTicks = Math.max(10, introSegmentTicks());
		int teleportTicks = 0; // 0 tick = hard cut/teleport
		double radius = introRadius();
		double height = introHeight();

		java.util.List<Location> centerline = null;
		try {
			centerline = trackConfig != null ? trackConfig.getCenterline() : null;
		} catch (Throwable ignored) {
			centerline = null;
		}

		Location startCenter = null;
		try {
			startCenter = trackConfig != null ? trackConfig.getStartCenter() : null;
		} catch (Throwable ignored) {
			startCenter = null;
		}
		boolean hasStartCenter = startCenter != null && startCenter.getWorld() != null;

		java.util.List<Location> pick = new java.util.ArrayList<>(targets);
		java.util.Collections.shuffle(pick, rnd);
		if (pick.size() > points)
			pick = pick.subList(0, points);

		java.util.List<CinematicKeyframe> keys = new java.util.ArrayList<>();
		for (int i = 0; i < pick.size(); i++) {
			Location t = pick.get(i);
			if (t == null || t.getWorld() == null)
				continue;

			// Compute a preferred movement direction (along the track if possible) so the
			// fly-by has real translation, not just camera rotation.
			org.bukkit.util.Vector forward = null;
			try {
				if (centerline != null && centerline.size() >= 2) {
					int best = -1;
					double bestD = Double.POSITIVE_INFINITY;
					for (int ci = 0; ci < centerline.size(); ci++) {
						Location cl = centerline.get(ci);
						if (cl == null || cl.getWorld() == null)
							continue;
						if (!cl.getWorld().equals(t.getWorld()))
							continue;
						double d2;
						try {
							d2 = cl.distanceSquared(t);
						} catch (Throwable ignored2) {
							d2 = Double.POSITIVE_INFINITY;
						}
						if (d2 < bestD) {
							bestD = d2;
							best = ci;
						}
					}
					if (best >= 0) {
						int a = Math.max(0, best - 2);
						int b = Math.min(centerline.size() - 1, best + 2);
						Location la = centerline.get(a);
						Location lb = centerline.get(b);
						if (la != null && lb != null && la.getWorld() != null && lb.getWorld() != null
								&& la.getWorld().equals(lb.getWorld()) && la.getWorld().equals(t.getWorld())) {
							org.bukkit.util.Vector v = lb.toVector().subtract(la.toVector());
							v.setY(0);
							if (v.lengthSquared() > 0.0001) {
								forward = v.normalize();
							}
						}
					}
				}
			} catch (Throwable ignored) {
				forward = null;
			}
			if (forward == null) {
				double a = rnd.nextDouble() * Math.PI * 2.0;
				forward = new org.bukkit.util.Vector(Math.cos(a), 0, Math.sin(a));
			}

			double dolly = Math.max(2.0, Math.min(9.0, radius * 0.40)) * (0.85 + rnd.nextDouble() * 0.30);

			// Create a slow, close "fly-by" around the same anchor point:
			// pointA -> pointB (small orbit delta + slightly different height/radius).
			double a1 = rnd.nextDouble() * Math.PI * 2.0;
			double delta = (rnd.nextBoolean() ? 1.0 : -1.0) * (Math.PI * (0.06 + rnd.nextDouble() * 0.08));
			double a2 = a1 + delta;

			double r1 = radius * (0.90 + rnd.nextDouble() * 0.20);
			double r2 = r1 * (0.96 + rnd.nextDouble() * 0.08);
			double h1 = height * (0.92 + rnd.nextDouble() * 0.12);
			double h2 = h1 * (0.96 + rnd.nextDouble() * 0.08);

			Location camA = t.clone().add(Math.cos(a1) * r1, h1, Math.sin(a1) * r1);
			Location camB = t.clone().add(Math.cos(a2) * r2, h2, Math.sin(a2) * r2);

			// Add a subtle translation along the track direction (dolly move) to avoid
			// a pure "swing".
			try {
				camA.add(-forward.getX() * dolly * 0.35, 0.0, -forward.getZ() * dolly * 0.35);
				camB.add(forward.getX() * dolly, 0.0, forward.getZ() * dolly);
			} catch (Throwable ignored) {
			}

			float[] ap = CinematicCameraService.lookAt(camA, t);
			float[] bp = CinematicCameraService.lookAt(camB, t);
			camA.setYaw(ap[0]);
			camA.setPitch(ap[1]);
			camB.setYaw(bp[0]);
			camB.setPitch(bp[1]);

			boolean last = (i == pick.size() - 1);
			int durA = flyTicks;
			int durB = last ? (hasStartCenter ? teleportTicks : 0) : teleportTicks;
			keys.add(new CinematicKeyframe(camA, durA));
			keys.add(new CinematicKeyframe(camB, durB));
		}

		if (keys.size() < 2)
			return null;

		// Final cut to start area, then a short hold before starting countdown.
		if (hasStartCenter) {
			double a = rnd.nextDouble() * Math.PI * 2.0;
			double r = Math.max(6.0, radius * 0.85);
			double h = Math.max(4.0, height * 0.95);
			Location startCam = startCenter.clone().add(Math.cos(a) * r, h, Math.sin(a) * r);
			float[] sp = CinematicCameraService.lookAt(startCam, startCenter);
			startCam.setYaw(sp[0]);
			startCam.setPitch(sp[1]);

			int holdTicks = Math.max(6, Math.min(20, flyTicks / 4));
			keys.add(new CinematicKeyframe(startCam, holdTicks));
			keys.add(new CinematicKeyframe(startCam.clone(), 0));
		} else {
			keys.add(new CinematicKeyframe(keys.get(keys.size() - 1).location.clone(), 0));
		}

		java.util.List<dev.belikhun.boatracing.cinematic.CinematicSoundEvent> snd = java.util.Collections.emptyList();
		try {
			boolean soundEnabled = plugin != null && plugin.getConfig().getBoolean("racing.intro.sound.enabled", true);
			if (soundEnabled) {
				var tune = dev.belikhun.boatracing.cinematic.CinematicMusicService.defaultArcadeIntroTune();
				snd = tune.events;
				if (audience != null && tune.name != null) {
					String msg = "&7♪ Đang phát: &f" + tune.name;
					for (Player p : audience) {
						try {
							Text.msg(p, msg);
							p.sendActionBar(net.kyori.adventure.text.Component.text("♪ " + tune.name)
									.color(net.kyori.adventure.text.format.NamedTextColor.AQUA));
						} catch (Throwable ignored) {
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}

		// Ensure the camera sequence is long enough to cover the music + a small buffer.
		// If the generated path is too short (e.g. few track points), extend the final hold.
		int musicDuration = 0;
		for (dev.belikhun.boatracing.cinematic.CinematicSoundEvent e : snd) {
			if (e.atTick > musicDuration)
				musicDuration = e.atTick;
		}

		if (musicDuration > 0) {
			int camDuration = 0;
			for (CinematicKeyframe k : keys) {
				camDuration += k.durationTicks;
			}

			// Target: Music end + 20 ticks (1s) buffer
			int targetDuration = musicDuration + 20;

			if (camDuration < targetDuration) {
				int needed = targetDuration - camDuration;
				if (!keys.isEmpty()) {
					CinematicKeyframe lastK = keys.remove(keys.size() - 1);
					keys.add(new CinematicKeyframe(lastK.location, lastK.durationTicks + needed));
				}
			}
		}

		return new CinematicSequence(keys, snd, true);
	}

	public boolean startIntroThenCountdown(java.util.List<Player> racers, Consumer<java.util.List<Player>> onCountdownStarted) {
		if (racers == null)
			return false;
		if (plugin == null)
			return false;
		if (isRunning())
			return false;
		if (isAnyCountdownActive())
			return false;

		int startSlots = 0;
		try {
			startSlots = trackConfig != null ? trackConfig.getStarts().size() : 0;
		} catch (Throwable ignored) {
			startSlots = 0;
		}
		startSlots = Math.max(0, startSlots);

		java.util.List<Player> online = new java.util.ArrayList<>();
		for (Player p : racers) {
			if (p == null || !p.isOnline())
				continue;
			online.add(p);
		}
		if (online.isEmpty())
			return false;
		if (startSlots > 0 && online.size() > startSlots) {
			java.util.List<Player> extras = new java.util.ArrayList<>(online.subList(startSlots, online.size()));
			online = online.subList(0, startSlots);
			for (Player p : extras) {
				try {
					if (p != null && p.isOnline())
						Text.msg(p, "&e⚠ Đường đua thiếu vị trí xuất phát, bạn sẽ không tham gia chặng này.");
				} catch (Throwable ignored) {
				}
			}
		}

		CinematicCameraService cam = cinematic();
		if (!introEnabled() || cam == null) {
			java.util.List<Player> placed = placeAtStartsWithBoats(online);
			if (placed.isEmpty())
				return false;
			startLightsCountdown(placed);
			try {
				if (onCountdownStarted != null)
					onCountdownStarted.accept(placed);
			} catch (Throwable ignored) {
			}
			return true;
		}

		// 'online' may be reassigned above (subList trim), so use a stable final copy
		// for lambda capture.
		final java.util.List<Player> introPlayersList = new java.util.ArrayList<>(online);

		try {
			if (!introPlayers.isEmpty())
				cam.stopForPlayers(new java.util.ArrayList<>(introPlayers), true);
		} catch (Throwable ignored) {
		}
		introPlayers.clear();
		introEndMillis = 0L;

		this.registering = false;
		this.waitingEndMillis = 0L;

		for (Player p : introPlayersList) {
			try {
				if (p.isInsideVehicle())
					p.leaveVehicle();
			} catch (Throwable ignored) {
			}

			introPlayers.add(p.getUniqueId());
		}

		CinematicSequence seq = buildIntroSequence(introCandidateTargets(), introPlayersList);
		if (seq == null) {
			introPlayers.clear();
			introEndMillis = 0L;
			java.util.List<Player> placed = placeAtStartsWithBoats(introPlayersList);
			if (placed.isEmpty())
				return false;
			startLightsCountdown(placed);
			try {
				if (onCountdownStarted != null)
					onCountdownStarted.accept(placed);
			} catch (Throwable ignored) {
			}
			return true;
		}

		int introTicks = Math.max(1, seq.totalTicks());
		introEndMillis = System.currentTimeMillis() + (introTicks * 50L);
		String introId = "track:" + trackId + ":intro:" + System.nanoTime();

		boolean started = cam.start(introId, introPlayersList, seq, () -> {
			try {
				introPlayers.clear();
				introEndMillis = 0L;
			} catch (Throwable ignored) {
			}

			java.util.List<Player> placed = placeAtStartsWithBoats(introPlayersList);
			if (placed.isEmpty())
				return;
			startLightsCountdown(placed);
			try {
				if (onCountdownStarted != null)
					onCountdownStarted.accept(placed);
			} catch (Throwable ignored) {
			}
		});

		if (!started) {
			introPlayers.clear();
			introEndMillis = 0L;
			java.util.List<Player> placed = placeAtStartsWithBoats(introPlayersList);
			if (placed.isEmpty())
				return false;
			startLightsCountdown(placed);
			try {
				if (onCountdownStarted != null)
					onCountdownStarted.accept(placed);
			} catch (Throwable ignored) {
			}
			return true;
		}
		return true;
	}

	private void announceRegistrationOpened(int laps) {
		if (plugin == null)
			return;

		String track = safeTrackName();
		String cmd = "/boatracing race join " + track;

		String tpl;
		try {
			tpl = plugin.getConfig().getString(
					"scoreboard.registration-announce",
					"&eCuộc đua mới tại &f{track}&e (&f{laps}&e vòng). &7Tham gia bằng &f{cmd}");
		} catch (Throwable ignored) {
			tpl = "&eCuộc đua mới tại &f{track}&e (&f{laps}&e vòng). &7Tham gia bằng &f{cmd}";
		}

		String msg = tpl
				.replace("{track}", track)
				.replace("{laps}", String.valueOf(Math.max(1, laps)))
				.replace("{cmd}", cmd);

		try {
			for (Player p : Bukkit.getOnlinePlayers()) {
				try {
					Text.msg(p, msg);
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private void broadcastRegistrationJoin(Player joined) {
		if (joined == null)
			return;

		String track = safeTrackName();
		int joinedCount = 0;
		int max = 0;
		try {
			joinedCount = registered.size();
		} catch (Throwable ignored) {
			joinedCount = 0;
		}
		try {
			max = trackConfig != null ? trackConfig.getStarts().size() : 0;
		} catch (Throwable ignored) {
			max = 0;
		}

		String racerDisplay = "&f" + joined.getName();
		try {
			if (plugin instanceof dev.belikhun.boatracing.BoatRacingPlugin br && br.getProfileManager() != null) {
				racerDisplay = br.getProfileManager().formatRacerLegacy(joined.getUniqueId(), joined.getName());
			}
		} catch (Throwable ignored) {
		}

		// Only announce to racers currently waiting/registered for THIS track.
		String msg = "&a● " + racerDisplay + " &ađã tham gia đăng ký &e" + track + "&a. &7(" + joinedCount + "/" + max
				+ ")";
		for (UUID id : new java.util.LinkedHashSet<>(registered)) {
			try {
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;
				Text.msg(p, msg);
			} catch (Throwable ignored) {
			}
		}
	}

	private String racerDisplayLegacy(UUID id, String name) {
		String n = resolveRacerName(id, name);
		try {
			if (plugin instanceof dev.belikhun.boatracing.BoatRacingPlugin br && br.getProfileManager() != null
					&& id != null) {
				return br.getProfileManager().formatRacerLegacy(id, n);
			}
		} catch (Throwable ignored) {
		}
		return "&f" + n;
	}

	private static String resolveRacerName(UUID id, String name) {
		if (name != null && !name.isBlank())
			return name;
		if (id == null)
			return "(không rõ)";

		try {
			Player p = Bukkit.getPlayer(id);
			if (p != null) {
				String pn = p.getName();
				if (pn != null && !pn.isBlank())
					return pn;
			}
		} catch (Throwable ignored) {
		}

		try {
			org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(id);
			String on = (op != null ? op.getName() : null);
			if (on != null && !on.isBlank())
				return on;
		} catch (Throwable ignored) {
		}

		String s = id.toString();
		return s.length() >= 8 ? s.substring(0, 8) : s;
	}

	private void broadcastRegistrationLeave(UUID leftId, String leftName) {
		if (leftId == null)
			return;
		String track = safeTrackName();
		int joinedCount = 0;
		int max = 0;
		try {
			joinedCount = registered.size();
		} catch (Throwable ignored) {
			joinedCount = 0;
		}
		try {
			max = trackConfig != null ? trackConfig.getStarts().size() : 0;
		} catch (Throwable ignored) {
			max = 0;
		}

		String racerDisplay = racerDisplayLegacy(leftId, leftName);
		String msg = "&c● " + racerDisplay + " &cđã rời đăng ký &e" + track + "&c. &7(" + joinedCount + "/" + max + ")";

		for (UUID id : new java.util.LinkedHashSet<>(registered)) {
			try {
				if (id == null || id.equals(leftId))
					continue;
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;
				Text.msg(p, msg);
			} catch (Throwable ignored) {
			}
		}
	}

	private void broadcastRacerFinished(UUID finisherId, String finisherName, int place, long finalMillis) {
		if (finisherId == null)
			return;
		String racerDisplay = racerDisplayLegacy(finisherId, finisherName);
		String msg = "&a✔ " + racerDisplay + " &ađã về đích: &e#" + Math.max(1, place) + " &7(⌚ &f"
				+ Time.formatStopwatchMillis(Math.max(0L, finalMillis)) + "&7)";

		for (UUID id : getInvolved()) {
			try {
				if (id == null || id.equals(finisherId))
					continue;
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;
				Text.msg(p, msg);
			} catch (Throwable ignored) {
			}
		}
	}

	private void broadcastNewTrackRecord(String trackName, UUID holderId, String holderName, long timeMillis) {
		if (plugin == null)
			return;
		String t = (trackName == null || trackName.isBlank()) ? "(không rõ)" : trackName;
		String holder = racerDisplayLegacy(holderId, holderName);
		String msg = "&6✔ &eKỷ lục mới tại &f" + t + "&e: " + holder + " &7(⌚ &f"
				+ Time.formatStopwatchMillis(Math.max(0L, timeMillis)) + "&7)";
		try {
			for (Player p : Bukkit.getOnlinePlayers()) {
				try {
					Text.msg(p, msg);
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public boolean openRegistration(int laps, Object unused) {
		boolean wasRegistering = this.registering;

		// If there is an existing scheduled registration transition, cancel it.
		if (registrationStartTask != null) {
			try {
				registrationStartTask.cancel();
			} catch (Throwable ignored) {
			}
			registrationStartTask = null;
		}
		this.registering = true;
		this.totalLaps = laps;
		// Waiting countdown should only start once at least 1 racer is waiting.
		this.waitingEndMillis = 0L;

		// Announce once when the track is opened for registration (waiting for racers).
		if (!wasRegistering) {
			try {
				announceRegistrationOpened(laps);
			} catch (Throwable ignored) {
			}
		}

		// Show checkpoint markers while the track is active.
		try {
			ensureCheckpointHolos();
		} catch (Throwable ignored) {
		}
		return true;
	}

	private void ensureRegistrationCountdownScheduledIfNeeded() {
		if (plugin == null)
			return;
		if (!registering)
			return;
		if (registrationStartTask != null)
			return;
		if (registered.isEmpty())
			return;

		int waitSec = Math.max(1, plugin.getConfig().getInt("racing.registration-seconds", 30));
		this.waitingEndMillis = System.currentTimeMillis() + (waitSec * 1000L);

		registrationStartTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			registrationStartTask = null;
			if (!registering) {
				waitingEndMillis = 0L;
				return;
			}
			if (registered.isEmpty()) {
				cancelRegistration(false);
				waitingEndMillis = 0L;
				return;
			}
			java.util.List<org.bukkit.entity.Player> participants = new java.util.ArrayList<>();
			for (java.util.UUID id : new java.util.LinkedHashSet<>(registered)) {
				org.bukkit.entity.Player rp = plugin.getServer().getPlayer(id);
				if (rp != null && rp.isOnline())
					participants.add(rp);
			}
			if (participants.isEmpty()) {
				cancelRegistration(false);
				waitingEndMillis = 0L;
				return;
			}
			this.registering = false;
			waitingEndMillis = 0L;
			startIntroThenCountdown(participants, null);
		}, waitSec * 20L);
	}

	public boolean join(Player p) {
		if (!registering)
			return false;
		boolean added = registered.add(p.getUniqueId());
		if (added) {
			// Start the waiting countdown only after the first racer joins.
			ensureRegistrationCountdownScheduledIfNeeded();
			// Lobby may allow flight; ensure racers can't fly.
			try {
				p.setAllowFlight(false);
				p.setFlying(false);
			} catch (Throwable ignored) {
			}
			try {
				// Ensure player isn't stuck in an old vehicle when joining.
				if (p.isInsideVehicle())
					p.leaveVehicle();
			} catch (Throwable ignored) {
			}
			// Prefer waiting spawn; else fall back to start center; else finish center
			org.bukkit.Location dest = trackConfig.getWaitingSpawn();
			if (debugTeleport()) {
				dbg("[TPDBG] join(" + p.getName() + ") track=" + trackConfig.getCurrentName()
						+ " trackWorld=" + trackConfig.getWorldName()
						+ " waitingSpawn=" + (dest == null ? "null" : dev.belikhun.boatracing.util.Text.fmtPos(dest))
						+ " destWorld="
						+ (dest == null || dest.getWorld() == null ? "null" : dest.getWorld().getName()));
			}
			if (dest == null)
				dest = trackConfig.getStartCenter();
			if (dest == null && trackConfig.getFinish() != null) {
				try {
					dest = centerOf(trackConfig.getFinish());
				} catch (Throwable ignored) {
				}
			}
			boolean ok = false;
			if (dest != null && dest.getWorld() != null) {
				try {
					ok = p.teleport(dest);
				} catch (Throwable ignored) {
					ok = false;
				}
			}
			if (debugTeleport()) {
				dbg("[TPDBG] teleport primary ok=" + ok + " dest="
						+ (dest == null ? "null" : dev.belikhun.boatracing.util.Text.fmtPos(dest))
						+ " destWorld="
						+ (dest == null || dest.getWorld() == null ? "null" : dest.getWorld().getName()));
			}
			// fallback if teleport failed (rare but possible)
			if (!ok) {
				org.bukkit.Location fb = trackConfig.getStartCenter();
				if (fb == null && trackConfig.getFinish() != null) {
					try {
						fb = centerOf(trackConfig.getFinish());
					} catch (Throwable ignored) {
					}
				}
				if (fb != null && fb.getWorld() != null) {
					try {
						p.teleport(fb);
					} catch (Throwable ignored) {
					}
					if (debugTeleport()) {
						dbg("[TPDBG] teleport fallback -> " + dev.belikhun.boatracing.util.Text.fmtPos(fb)
								+ " world=" + (fb.getWorld() == null ? "null" : fb.getWorld().getName()));
					}
				}
			}

			// Join sound
			try {
				p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.6f);
			} catch (Throwable ignored) {
			}

			// Notify everyone currently waiting on this track.
			try {
				broadcastRegistrationJoin(p);
			} catch (Throwable ignored) {
			}
		}
		return added;
	}

	public boolean leave(Player p) {
		if (p == null)
			return false;
		boolean wasRegistering = registering;
		boolean removed = registered.remove(p.getUniqueId());
		if (!removed)
			return false;

		if (wasRegistering) {
			try {
				broadcastRegistrationLeave(p.getUniqueId(), p.getName());
			} catch (Throwable ignored) {
			}
		}

		// If the last racer leaves during registration, reset the waiting timer so the
		// next join
		// gets a full countdown again.
		if (registering && registered.isEmpty()) {
			waitingEndMillis = 0L;
			if (registrationStartTask != null) {
				try {
					registrationStartTask.cancel();
				} catch (Throwable ignored) {
				}
				registrationStartTask = null;
			}
		}

		return true;
	}

	public void forceStart() {
		this.registering = false;
		if (registered.isEmpty())
			return;
		// Build participants from currently registered and online
		java.util.List<Player> participants = new java.util.ArrayList<>();
		for (UUID id : new java.util.LinkedHashSet<>(registered)) {
			Player rp = plugin.getServer().getPlayer(id);
			if (rp != null && rp.isOnline())
				participants.add(rp);
		}
		if (participants.isEmpty())
			return;
		// Intro sequence, then countdown.
		startIntroThenCountdown(participants, null);
	}

	// Place participants at start locations and spawn boats for them
	public List<Player> placeAtStartsWithBoats(List<Player> participants) {
		List<Location> starts = trackConfig.getStarts();
		if (starts.isEmpty())
			return Collections.emptyList();
		List<Player> placed = new ArrayList<>();
		int slot = 0;
		for (Player p : participants) {
			if (slot >= starts.size())
				break; // no more slots
			Location boatSpawn = starts.get(slot).clone();
			Location target = boatSpawn.clone();
			try {
				// Always dismount the player first.
				// If they are in one of our spawned boats (e.g. restart), remove it; otherwise,
				// just eject.
				try {
					Entity curVeh = p.getVehicle();
					if (curVeh != null) {
						try {
							curVeh.eject();
						} catch (Throwable ignored) {
						}
						try {
							p.leaveVehicle();
						} catch (Throwable ignored) {
						}
						if (isSpawnedBoat(curVeh)) {
							try {
								curVeh.remove();
							} catch (Throwable ignored) {
							}
						}
					}
				} catch (Throwable ignored) {
				}

				// teleport player slightly above the boat spawn to avoid clipping
				target.setY(target.getY() + 1.0);
				p.teleport(target);

				PreferredBoatData pref = resolvePreferredBoat(p.getUniqueId());
				EntityType spawnType = resolveSpawnEntityType(pref);
				var spawnWorld = (boatSpawn.getWorld() != null ? boatSpawn.getWorld() : p.getWorld());
				var ent = spawnWorld.spawnEntity(boatSpawn, spawnType);

				try {
					markSpawnedBoat(ent);
					spawnedBoatByPlayer.put(p.getUniqueId(), ent.getUniqueId());
				} catch (Throwable ignored) {
				}

				// Apply variant when possible.
				String base = pref.baseType;
				if (ent instanceof Boat b) {
					if (base != null) {
						try {
							b.setBoatType(Boat.Type.valueOf(base));
						} catch (Throwable ignored) {
						}
					}
					try {
						b.addPassenger(p);
					} catch (Throwable ignored) {
						try {
							if (p.isInsideVehicle())
								p.leaveVehicle();
						} catch (Throwable ignored2) {
						}
						try {
							b.addPassenger(p);
						} catch (Throwable ignored2) {
						}
					}
				} else if (ent instanceof ChestBoat cb) {
					if (base != null) {
						try {
							cb.setBoatType(Boat.Type.valueOf(base));
						} catch (Throwable ignored) {
						}
					}
					try {
						cb.addPassenger(p);
					} catch (Throwable ignored) {
						try {
							if (p.isInsideVehicle())
								p.leaveVehicle();
						} catch (Throwable ignored2) {
						}
						try {
							cb.addPassenger(p);
						} catch (Throwable ignored2) {
						}
					}
				} else {
					// Fallback: still seat the player if possible
					try {
						ent.addPassenger(p);
					} catch (Throwable ignored) {
					}
				}
				placed.add(p);

				if (debugBoatSelection()) {
					try {
						dbg("[BOATDBG] placeAtStarts player=" + p.getName() + " raw='" + pref.raw + "' spawnType="
								+ spawnType.name() + " base=" + base);
					} catch (Throwable ignored) {
					}
				}
			} catch (Throwable ignored) {
			}
			slot++;
		}
		return placed;
	}

	// Simple countdown using server scheduler
	public void startLightsCountdown(List<Player> placed) {
		if (placed.isEmpty())
			return;
		this.registering = false;

		// Cancel any pending end-of-race result announcements when starting a new countdown.
		cancelResultsBoards();

		// Ensure checkpoint holos exist for this race instance.
		try {
			ensureCheckpointHolos();
		} catch (Throwable ignored) {
		}

		// Cancel any scheduled post-finish cleanup when starting a new countdown.
		if (postFinishCleanupTask != null) {
			try {
				postFinishCleanupTask.cancel();
			} catch (Throwable ignored) {
			}
			postFinishCleanupTask = null;
		}
		postFinishCleanupEndMillis = 0L;

		// Stop the "all finished" firework show if it was running.
		stopAllFinishedFireworks();

		// Start/update the per-boat dashboard while countdown/race is active.
		ensureDashboardTask();

		// Stop any prior countdown before starting a new one.
		if (countdownTask != null) {
			try {
				countdownTask.cancel();
			} catch (Throwable ignored) {
			}
			countdownTask = null;
		}
		if (countdownFreezeTask != null) {
			try {
				countdownFreezeTask.cancel();
			} catch (Throwable ignored) {
			}
			countdownFreezeTask = null;
		}
		if (startLightsBlinkTask != null) {
			try {
				startLightsBlinkTask.cancel();
			} catch (Throwable ignored) {
			}
			startLightsBlinkTask = null;
		}

		countdownPlayers.clear();
		countdownLockLocation.clear();
		countdownDebugLastLog.clear();
		clearCountdownBarriers();
		restoreCountdownBoatPhysics();
		for (Player p : placed) {
			if (p != null) {
				countdownPlayers.add(p.getUniqueId());
				try {
					org.bukkit.Location lock;
					org.bukkit.entity.Entity veh = p.getVehicle();
					if (veh != null) {
						lock = veh.getLocation().clone();
					} else {
						// Prefer the plugin-spawned boat entity (covers cases where seating isn't
						// finished yet)
						lock = null;
						try {
							UUID boatId = spawnedBoatByPlayer.get(p.getUniqueId());
							if (boatId != null) {
								org.bukkit.entity.Entity e = Bukkit.getEntity(boatId);
								if (e != null) {
									lock = e.getLocation().clone();
								}
							}
						} catch (Throwable ignored) {
						}
						if (lock == null)
							lock = p.getLocation().clone();
					}

					// Always lock yaw/pitch to the player's facing direction at countdown start.
					try {
						org.bukkit.Location facing = p.getLocation();
						lock.setYaw(facing.getYaw());
						lock.setPitch(facing.getPitch());
					} catch (Throwable ignored) {
					}

					countdownLockLocation.put(p.getUniqueId(), lock);
				} catch (Throwable ignored) {
				}
			}
		}

		// Place temporary barrier blocks in front of each start position to prevent
		// forward motion.
		try {
			placeCountdownBarriers();
		} catch (Throwable ignored) {
		}

		final int total = 10; // 10..1..GO
		this.countdownEndMillis = System.currentTimeMillis() + (total * 1000L);

		// Initialize start lights.
		try {
			setStartLightsProgress(0.0);
		} catch (Throwable ignored) {
		}

		// Create the countdown task first so the freeze task (delay 0) doesn't cancel
		// itself
		// on the first tick due to countdownTask being null.
		countdownTask = new BukkitRunnable() {
			private int sec = total;

			@Override
			public void run() {
				if (sec <= 0) {
					// Start!
					try {
						setStartLightsProgress(1.0);
					} catch (Throwable ignored) {
					}

					// Visual cue: blink start lights 3 times on GO.
					try {
						blinkStartLights(3, 4L);
					} catch (Throwable ignored) {
					}

					// Audio/visual cue: firework "gun shot" at the start.
					try {
						spawnStartFirework();
					} catch (Throwable ignored) {
					}

					// Remove temporary barriers before the race starts.
					try {
						clearCountdownBarriers();
					} catch (Throwable ignored) {
					}

					running = true;
					raceStartMillis = System.currentTimeMillis();
					countdownEndMillis = 0L;

					if (countdownFreezeTask != null) {
						try {
							countdownFreezeTask.cancel();
						} catch (Throwable ignored) {
						}
						countdownFreezeTask = null;
					}
					countdownPlayers.clear();
					countdownLockLocation.clear();
					countdownDebugLastLog.clear();
					clearCountdownBarriers();
					restoreCountdownBoatPhysics();

					participants.clear();
					participantPlayers.clear();
					for (Player p : placed) {
						ParticipantState st = new ParticipantState(p.getUniqueId());
						participants.put(p.getUniqueId(), st);
						participantPlayers.put(p.getUniqueId(), p);
					}
					raceStartRacerCount = participants.size();
					initPathForLivePositions();
					startRaceTicker();

					// Ensure dashboard stays active during the running race.
					ensureDashboardTask();

					if (debugCheckpoints()) {
						try {
							dbg("[CPDBG] Race started. track=" + trackConfig.getCurrentName()
									+ " checkpoints=" + trackConfig.getCheckpoints().size()
									+ " finish=" + (trackConfig.getFinish() == null ? "null"
											: dev.belikhun.boatracing.util.Text.fmtArea(trackConfig.getFinish())));
						} catch (Throwable ignored) {
						}
					}

					for (Player p : placed) {
						try {
							// Keep fade in/out for the start title, but show only the start text in the
							// subtitle.
							var sub = net.kyori.adventure.text.Component.text("🟢 BẮT ĐẦU!")
									.color(net.kyori.adventure.text.format.TextColor.color(0x00FF00));
							p.showTitle(net.kyori.adventure.title.Title.title(
									net.kyori.adventure.text.Component.empty(), sub,
									net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200),
											java.time.Duration.ofMillis(1000), java.time.Duration.ofMillis(200))));
							p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
						} catch (Throwable ignored) {
						}
					}
					cancel();
					return;
				}

				// Update start lights as a progress bar.
				try {
					// Light one lamp per second for the last N seconds (N = number of configured
					// lights).
					// Example with 5 lamps: lights stay off until sec==5, then 1..5 lamps light up
					// as sec goes 5..1.
					setStartLightsCountdownSeconds(sec);
				} catch (Throwable ignored) {
				}

				countdownEndMillis = System.currentTimeMillis() + (sec * 1000L);
				for (Player p : placed) {
					try {
						var sub = net.kyori.adventure.text.Component.text(String.valueOf(sec))
								.color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
						net.kyori.adventure.text.Component dot = net.kyori.adventure.text.Component.text("●");
						var dark = net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY;
						net.kyori.adventure.text.Component title;
						if (sec > 3) {
							title = net.kyori.adventure.text.Component.text("● ● ●").color(dark);
						} else if (sec == 3) {
							title = net.kyori.adventure.text.Component.empty()
									.append(dot.color(net.kyori.adventure.text.format.NamedTextColor.RED))
									.append(net.kyori.adventure.text.Component.text(" "))
									.append(dot.color(dark)).append(net.kyori.adventure.text.Component.text(" "))
									.append(dot.color(dark));
						} else if (sec == 2) {
							title = net.kyori.adventure.text.Component.empty()
									.append(dot.color(net.kyori.adventure.text.format.NamedTextColor.RED))
									.append(net.kyori.adventure.text.Component.text(" "))
									.append(dot.color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
									.append(net.kyori.adventure.text.Component.text(" "))
									.append(dot.color(dark));
						} else { // sec == 1
							title = net.kyori.adventure.text.Component.empty()
									.append(dot.color(dark)).append(net.kyori.adventure.text.Component.text(" "))
									.append(dot.color(dark)).append(net.kyori.adventure.text.Component.text(" "))
									.append(dot.color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
						}
						// Countdown: no fade in/out. Keep slightly >1s display to avoid flicker
						// when the server tick drifts and the next title arrives a hair late.
						p.showTitle(net.kyori.adventure.title.Title.title(title, sub,
								net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO,
										java.time.Duration.ofMillis(1200), java.time.Duration.ZERO)));
						if (sec == 3) {
							p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 0.90f);
						} else if (sec == 2) {
							p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.05f);
						} else if (sec == 1) {
							p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.25f);
						} else {
							p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
						}
					} catch (Throwable ignored) {
					}
				}

				sec--;
			}

			@Override
			public synchronized void cancel() throws IllegalStateException {
				super.cancel();
				if (countdownTask == this)
					countdownTask = null;
			}
		};

		// Enforce a hard freeze every tick, not just on VehicleMoveEvent.
		countdownFreezeTask = new BukkitRunnable() {
			@Override
			public void run() {
				if (plugin == null) {
					cancel();
					return;
				}
				if (running) {
					cancel();
					return;
				}
				if (countdownTask == null || countdownPlayers.isEmpty()) {
					cancel();
					return;
				}

				boolean dbg = debugCountdownFreeze();
				long now = dbg ? System.currentTimeMillis() : 0L;

				for (UUID id : new java.util.ArrayList<>(countdownPlayers)) {
					Player p = Bukkit.getPlayer(id);
					if (p == null || !p.isOnline())
						continue;
					org.bukkit.Location lock = countdownLockLocation.get(id);
					if (lock == null)
						continue;

					try {
						org.bukkit.entity.Entity v = p.getVehicle();
						if (isBoatLike(v)) {
							org.bukkit.Location before = dbg ? v.getLocation() : null;

							// Ensure lock has a world (teleport returns false if lock world is null).
							if (lock.getWorld() == null) {
								try {
									lock.setWorld(v.getWorld());
								} catch (Throwable ignored) {
								}
							}

							// Stop velocity (prevents drift/false-start movement).
							v.setVelocity(new Vector(0, 0, 0));

							// Always snap back to the fixed lock location (prevents TPS-lag inching).
							boolean tpOk;
							try {
								// Paper teleport flags: retaining passengers is default since 1.21.10, but
								// explicit is fine.
								tpOk = v.teleport(lock,
										io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
							} catch (Throwable t) {
								try {
									tpOk = v.teleport(lock);
								} catch (Throwable ignored) {
									tpOk = false;
								}
							}
							boolean nmsOk = false;
							if (!tpOk) {
								try {
									nmsOk = dev.belikhun.boatracing.util.EntityForceTeleport.nms(v, lock);
								} catch (Throwable ignored) {
									nmsOk = false;
								}
							}
							// Re-zero in case teleport preserved any motion
							v.setVelocity(new Vector(0, 0, 0));
							try {
								v.setRotation(lock.getYaw(), lock.getPitch());
							} catch (Throwable ignored) {
							}

							if (dbg) {
								Long prev = countdownDebugLastLog.get(id);
								if (prev == null || (now - prev) >= 1000L) {
									countdownDebugLastLog.put(id, now);
									org.bukkit.Location cur = (before != null ? before : v.getLocation());
									double dx = cur.getX() - lock.getX();
									double dy = cur.getY() - lock.getY();
									double dz = cur.getZ() - lock.getZ();
									float dyaw = absAngleDelta(cur.getYaw(), lock.getYaw());
									float dpitch = Math.abs(cur.getPitch() - lock.getPitch());
									try {
										String bw = (v.getWorld() == null ? "?" : v.getWorld().getName());
										String lw = (lock.getWorld() == null ? "null" : lock.getWorld().getName());
										boolean chunkLoaded = false;
										try {
											chunkLoaded = lock.getWorld() != null && lock.getWorld()
													.isChunkLoaded(lock.getBlockX() >> 4, lock.getBlockZ() >> 4);
										} catch (Throwable ignored2) {
										}
										plugin.getLogger().info("[COUNTDOWN] track="
												+ (trackConfig == null ? "?" : trackConfig.getCurrentName())
												+ " player=" + p.getName()
												+ " tp=" + tpOk
												+ " nms=" + nmsOk
												+ " boatWorld=" + bw
												+ " lockWorld=" + lw
												+ " chunkLoaded=" + chunkLoaded
												+ " passengers="
												+ (v.getPassengers() == null ? 0 : v.getPassengers().size())
												+ " dPos="
												+ String.format(java.util.Locale.ROOT, "(%.4f,%.4f,%.4f)", dx, dy, dz)
												+ " dYaw=" + String.format(java.util.Locale.ROOT, "%.2f", dyaw)
												+ " dPitch=" + String.format(java.util.Locale.ROOT, "%.2f", dpitch));
									} catch (Throwable ignored) {
									}
								}
							}
						} else {
							// Fallback: keep the player on the lock spot.
							p.teleport(lock);
							try {
								p.setRotation(lock.getYaw(), lock.getPitch());
							} catch (Throwable ignored) {
							}
						}
					} catch (Throwable ignored) {
					}
				}
			}

			@Override
			public synchronized void cancel() throws IllegalStateException {
				super.cancel();
				if (countdownFreezeTask == this)
					countdownFreezeTask = null;
			}
		};
		countdownFreezeTask.runTaskTimer(plugin, 0L, 1L);

		countdownTask.runTaskTimer(plugin, 0L, 20L);
	}

	private void setStartLightsProgress(double progress01) {
		if (plugin == null)
			return;
		java.util.List<Block> lights = trackConfig.getLights();
		if (lights == null || lights.isEmpty())
			return;
		int n = lights.size();
		double p = Math.max(0.0, Math.min(1.0, progress01));
		int litCount = (int) Math.floor(p * (double) n + 1e-9);
		if (litCount < 0)
			litCount = 0;
		if (litCount > n)
			litCount = n;

		for (int i = 0; i < n; i++) {
			Block b = lights.get(i);
			if (b == null)
				continue;
			boolean lit = i < litCount;
			setLampLit(b, lit);
		}
	}

	// Countdown mode: light up 1 lamp per second during the last N seconds.
	// If there are 5 lamps, they light up when sec==5 down to sec==1.
	private void setStartLightsCountdownSeconds(int countdownSeconds) {
		if (plugin == null)
			return;
		java.util.List<Block> lights = trackConfig.getLights();
		if (lights == null || lights.isEmpty())
			return;
		int n = lights.size();
		int sec = Math.max(0, countdownSeconds);

		int litCount;
		if (sec <= 0) {
			litCount = n;
		} else if (sec > n) {
			litCount = 0;
		} else {
			litCount = (n - sec) + 1;
		}

		setStartLightsLitCount(litCount);
	}

	private void setStartLightsLitCount(int litCount) {
		if (plugin == null)
			return;
		java.util.List<Block> lights = trackConfig.getLights();
		if (lights == null || lights.isEmpty())
			return;
		int n = lights.size();
		int on = Math.max(0, Math.min(n, litCount));
		for (int i = 0; i < n; i++) {
			Block b = lights.get(i);
			if (b == null)
				continue;
			setLampLit(b, i < on);
		}
	}

	private static void setLampLit(Block b, boolean lit) {
		try {
			if (b == null)
				return;
			if (b.getType() != Material.REDSTONE_LAMP)
				return;
			BlockData bd = b.getBlockData();
			if (!(bd instanceof Lightable l))
				return;
			if (l.isLit() == lit)
				return;
			l.setLit(lit);
			b.setBlockData(l, false);
		} catch (Throwable ignored) {
		}
	}

	public boolean cancelRegistration(boolean announce) {
		boolean had = registering || !registered.isEmpty();
		registering = false;
		registered.clear();
		waitingEndMillis = 0L;
		if (registrationStartTask != null) {
			try {
				registrationStartTask.cancel();
			} catch (Throwable ignored) {
			}
			registrationStartTask = null;
		}

		try {
			clearCheckpointHolos();
		} catch (Throwable ignored) {
		}
		return had;
	}

	public boolean cancelRace() {
		return stop(true);
	}

	/**
	 * Stop ANY active state (registering / countdown / running) and clean up:
	 * - remove plugin-spawned boats
	 * - reset all runtime state
	 * - teleport affected players back to their world spawn
	 */
	public boolean stop(boolean teleportToSpawn) {
		boolean wasRunning = running;
		boolean wasRegistering = registering;
		boolean wasCountdown = countdownTask != null && !countdownPlayers.isEmpty();
		boolean hadAny = wasRunning || wasRegistering || wasCountdown || !registered.isEmpty()
				|| !participants.isEmpty() || !countdownPlayers.isEmpty();

		// Stop intro cinematic if active.
		try {
			CinematicCameraService cam = cinematic();
			if (cam != null && !introPlayers.isEmpty())
				cam.stopForPlayers(new java.util.ArrayList<>(introPlayers), true);
		} catch (Throwable ignored) {
		}
		try {
			introPlayers.clear();
			introEndMillis = 0L;
		} catch (Throwable ignored) {
		}

		// If we were freezing boats during countdown, restore physics regardless of how
		// we stop.
		try {
			restoreCountdownBoatPhysics();
		} catch (Throwable ignored) {
		}
		try {
			clearCountdownBarriers();
		} catch (Throwable ignored) {
		}
		try {
			restorePreviousGameModes();
		} catch (Throwable ignored) {
		}
		try {
			stopAllFinishedFireworks();
		} catch (Throwable ignored) {
		}
		try {
			cancelResultsBoards();
		} catch (Throwable ignored) {
		}
		try {
			clearCheckpointHolos();
		} catch (Throwable ignored) {
		}
		try {
			clearAllDashboards();
		} catch (Throwable ignored) {
		}
		try {
			stopDashboardTask();
		} catch (Throwable ignored) {
		}
		try {
			dashboardDisabledPlayers.clear();
		} catch (Throwable ignored) {
		}
		try {
			dashboardActiveTmp.clear();
			dashboardRemoveTmp.clear();
		} catch (Throwable ignored) {
		}

		if (postFinishCleanupTask != null) {
			try {
				postFinishCleanupTask.cancel();
			} catch (Throwable ignored) {
			}
			postFinishCleanupTask = null;
		}
		postFinishCleanupEndMillis = 0L;

		// Snapshot players to clean up before wiping state.
		java.util.Set<UUID> toCleanup = new java.util.HashSet<>();
		toCleanup.addAll(registered);
		toCleanup.addAll(participants.keySet());
		toCleanup.addAll(countdownPlayers);

		// Stop scheduled tasks first.
		if (registrationStartTask != null) {
			try {
				registrationStartTask.cancel();
			} catch (Throwable ignored) {
			}
			registrationStartTask = null;
		}
		if (countdownTask != null) {
			try {
				countdownTask.cancel();
			} catch (Throwable ignored) {
			}
			countdownTask = null;
		}
		if (countdownFreezeTask != null) {
			try {
				countdownFreezeTask.cancel();
			} catch (Throwable ignored) {
			}
			countdownFreezeTask = null;
		}
		if (startLightsBlinkTask != null) {
			try {
				startLightsBlinkTask.cancel();
			} catch (Throwable ignored) {
			}
			startLightsBlinkTask = null;
		}

		cancelResultsBoards();

		// Turn off start lights when stopping.
		try {
			setStartLightsProgress(0.0);
		} catch (Throwable ignored) {
		}
		stopRaceTicker();

		// Reset state flags.
		running = false;
		registering = false;
		countdownEndMillis = 0L;
		waitingEndMillis = 0L;
		postFinishCleanupEndMillis = 0L;
		raceStartMillis = 0L;

		// Clean up entities/players.
		cleanupPlayers(toCleanup, teleportToSpawn);

		// Reset all runtime collections.
		registered.clear();
		participants.clear();
		participantPlayers.clear();
		countdownPlayers.clear();
		countdownLockLocation.clear();
		spawnedBoatByPlayer.clear();
		previousGameModes.clear();
		allFinishedFireworksTask = null;
		return hadAny || wasRunning || wasRegistering;
	}

	private void blinkStartLights(int blinks, long intervalTicks) {
		if (plugin == null)
			return;
		int times = Math.max(1, blinks);
		long step = Math.max(1L, intervalTicks);

		if (startLightsBlinkTask != null) {
			try {
				startLightsBlinkTask.cancel();
			} catch (Throwable ignored) {
			}
			startLightsBlinkTask = null;
		}

		// 3 blinks => ON/OFF repeated 3 times (6 toggles). Start from current "on"
		// state.
		final int totalToggles = times * 2;
		startLightsBlinkTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
			int toggles = 0;
			boolean on = false;

			@Override
			public void run() {
				if (plugin == null) {
					try {
						if (startLightsBlinkTask != null)
							startLightsBlinkTask.cancel();
					} catch (Throwable ignored) {
					}
					startLightsBlinkTask = null;
					return;
				}
				// toggle
				on = !on;
				try {
					setStartLightsProgress(on ? 1.0 : 0.0);
				} catch (Throwable ignored) {
				}

				toggles++;
				if (toggles >= totalToggles) {
					try {
						setStartLightsProgress(0.0);
					} catch (Throwable ignored) {
					}
					try {
						if (startLightsBlinkTask != null)
							startLightsBlinkTask.cancel();
					} catch (Throwable ignored) {
					}
					startLightsBlinkTask = null;
				}
			}
		}, 0L, step);
	}

	private void spawnStartFirework() {
		if (plugin == null)
			return;
		org.bukkit.Location loc = null;
		try {
			loc = trackConfig.getStartCenter();
		} catch (Throwable ignored) {
		}
		if (loc == null) {
			try {
				java.util.List<org.bukkit.Location> starts = trackConfig.getStarts();
				if (starts != null && !starts.isEmpty())
					loc = starts.get(0);
			} catch (Throwable ignored) {
			}
		}
		if (loc == null || loc.getWorld() == null)
			return;

		org.bukkit.Location spawn = loc.clone().add(0.0, 2.0, 0.0);
		try {
			org.bukkit.entity.Firework fw = spawn.getWorld().spawn(spawn, org.bukkit.entity.Firework.class);
			try {
				dev.belikhun.boatracing.race.RaceFx.markFirework(plugin, fw);
			} catch (Throwable ignored) {
			}
			try {
				fw.setSilent(true);
			} catch (Throwable ignored) {
			}
			try {
				fw.setInvulnerable(true);
			} catch (Throwable ignored) {
			}
			org.bukkit.inventory.meta.FireworkMeta meta = fw.getFireworkMeta();
			meta.setPower(0);
			try {
				meta.addEffect(org.bukkit.FireworkEffect.builder()
						.with(org.bukkit.FireworkEffect.Type.BALL)
						.flicker(true)
						.trail(true)
						.withColor(org.bukkit.Color.WHITE)
						.build());
			} catch (Throwable ignored) {
			}
			fw.setFireworkMeta(meta);
			plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
				try {
					fw.detonate();
				} catch (Throwable ignored) {
				}
			}, 1L);
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Called when a player disconnects (quit/kick). If they were participating in
	 * any phase
	 * (registration/countdown/race), they are removed and their plugin-spawned boat
	 * is deleted.
	 *
	 * This intentionally does NOT stop the race for remaining racers.
	 */
	public boolean handleRacerDisconnect(UUID id) {
		if (id == null)
			return false;

		boolean changed = false;
		boolean wasRegistering = registering;
		boolean wasRegistered = registered.contains(id);
		String leftName = null;
		try {
			Player p = participantPlayers.get(id);
			leftName = (p != null ? p.getName() : null);
		} catch (Throwable ignored) {
			leftName = null;
		}
		if (leftName == null || leftName.isBlank()) {
			try {
				org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(id);
				leftName = (op != null ? op.getName() : null);
			} catch (Throwable ignored) {
				leftName = null;
			}
		}
		if (leftName == null)
			leftName = "";

		// Remove from registration.
		if (registered.remove(id))
			changed = true;
		if (wasRegistering && wasRegistered) {
			try {
				broadcastRegistrationLeave(id, leftName);
			} catch (Throwable ignored) {
			}
		}

		// Remove from countdown/freeze state.
		if (countdownPlayers.remove(id))
			changed = true;
		if (countdownLockLocation.remove(id) != null)
			changed = true;

		// Remove from intro state.
		if (introPlayers.remove(id))
			changed = true;

		// Remove from live race state.
		if (participants.remove(id) != null)
			changed = true;
		if (participantPlayers.remove(id) != null)
			changed = true;

		// If they were put into spectator, forget their original mode.
		try {
			previousGameModes.remove(id);
		} catch (Throwable ignored) {
		}

		// Remove their spawned boat entity even if they're offline.
		UUID boatId = spawnedBoatByPlayer.remove(id);
		if (boatId != null) {
			changed = true;
			try {
				if (plugin != null) {
					Entity ent = plugin.getServer().getEntity(boatId);
					if (ent != null && isSpawnedBoat(ent)) {
						try {
							ent.eject();
						} catch (Throwable ignored) {
						}
						try {
							ent.remove();
						} catch (Throwable ignored) {
						}
					}
				}
			} catch (Throwable ignored) {
			}
		}

		// If countdown is active but nobody is left, cancel countdown state.
		if (countdownTask != null && countdownPlayers.isEmpty()) {
			try {
				countdownTask.cancel();
			} catch (Throwable ignored) {
			}
			countdownTask = null;
			if (countdownFreezeTask != null) {
				try {
					countdownFreezeTask.cancel();
				} catch (Throwable ignored) {
				}
				countdownFreezeTask = null;
			}
			countdownEndMillis = 0L;
			try {
				clearCountdownBarriers();
			} catch (Throwable ignored) {
			}
			try {
				setStartLightsProgress(0.0);
			} catch (Throwable ignored) {
			}
			changed = true;
		}

		// If intro is active but nobody is left, cancel intro state.
		try {
			if (introEndMillis > System.currentTimeMillis() && introPlayers.isEmpty()) {
				introEndMillis = 0L;
				changed = true;
			}
		} catch (Throwable ignored) {
		}

		// If registration is active but nobody remains, cancel it.
		if (registering && registered.isEmpty()) {
			cancelRegistration(false);
			changed = true;
		}

		// If the race is running but nobody remains, end it and clear timers/tasks.
		if (running && participants.isEmpty()) {
			stopRaceTicker();
			running = false;
			raceStartMillis = 0L;
			try {
				setStartLightsProgress(0.0);
			} catch (Throwable ignored) {
			}
			changed = true;
		}

		// Remove their dashboard.
		try {
			removeDashboard(id);
		} catch (Throwable ignored) {
		}

		return changed;
	}

	private NamespacedKey spawnedBoatKey() {
		return keySpawnedBoat;
	}

	private void markSpawnedBoat(Entity e) {
		if (e == null)
			return;
		NamespacedKey key = spawnedBoatKey();
		if (key == null)
			return;
		try {
			e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
		} catch (Throwable ignored) {
		}
	}

	private boolean isSpawnedBoat(Entity e) {
		if (e == null)
			return false;
		if (!isBoatLike(e))
			return false;
		NamespacedKey key = spawnedBoatKey();
		if (key == null)
			return false;
		try {
			return e.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void cleanupPlayers(java.util.Set<UUID> ids, boolean teleportToSpawn) {
		if (plugin == null || ids == null || ids.isEmpty())
			return;
		for (UUID id : ids) {
			if (id == null)
				continue;
			Player p = null;
			try {
				p = plugin.getServer().getPlayer(id);
			} catch (Throwable ignored) {
			}
			if (p == null || !p.isOnline())
				continue;

			// Remove their spawned boat if we have a handle.
			UUID boatId = spawnedBoatByPlayer.get(id);
			if (boatId != null) {
				try {
					Entity ent = plugin.getServer().getEntity(boatId);
					if (ent != null && isSpawnedBoat(ent)) {
						try {
							ent.eject();
						} catch (Throwable ignored) {
						}
						try {
							ent.remove();
						} catch (Throwable ignored) {
						}
					}
				} catch (Throwable ignored) {
				}
			}

			// If player is still in a spawned boat, remove it too.
			try {
				Entity veh = p.getVehicle();
				if (veh != null) {
					if (isSpawnedBoat(veh)) {
						try {
							veh.eject();
						} catch (Throwable ignored) {
						}
						try {
							veh.remove();
						} catch (Throwable ignored) {
						}
					} else {
						try {
							p.leaveVehicle();
						} catch (Throwable ignored) {
						}
					}
				}
			} catch (Throwable ignored) {
			}

			if (teleportToSpawn) {
				try {
					org.bukkit.Location spawn = null;
					if (plugin instanceof dev.belikhun.boatracing.BoatRacingPlugin br) {
						spawn = br.resolveLobbySpawn(p);
					} else if (p.getWorld() != null) {
						spawn = p.getWorld().getSpawnLocation();
					}
					if (spawn != null)
						p.teleport(spawn);
					p.setFallDistance(0f);
					try {
						if (plugin instanceof dev.belikhun.boatracing.BoatRacingPlugin br)
							br.applyLobbyFlight(p);
					} catch (Throwable ignored) {
					}
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private void startRaceTicker() {
		stopRaceTicker();
		raceTickTask = new BukkitRunnable() {
			@Override
			public void run() {
				if (!running) {
					cancel();
					return;
				}
				boolean anyActive = false;
				offlineRacerTmp.clear();
				for (var e : participantPlayers.entrySet()) {
					UUID id = e.getKey();
					Player p = e.getValue();
					if (p == null || !p.isOnline()) {
						if (id != null)
							offlineRacerTmp.add(id);
						continue;
					}
					ParticipantState st = participants.get(e.getKey());
					if (st == null || st.finished)
						continue;
					anyActive = true;
					try {
						org.bukkit.entity.Entity veh = p.getVehicle();
						org.bukkit.Location loc = (veh != null ? veh.getLocation() : p.getLocation());
						tickPlayer(p, null, loc);
					} catch (Throwable ignored) {
					}
				}
				if (!offlineRacerTmp.isEmpty()) {
					for (UUID id : offlineRacerTmp) {
						try {
							handleRacerDisconnect(id);
						} catch (Throwable ignored) {
						}
					}
				}
				if (!anyActive) {
					cancel();
				}
			}

			@Override
			public synchronized void cancel() throws IllegalStateException {
				super.cancel();
				if (raceTickTask == this)
					raceTickTask = null;
			}
		};
		raceTickTask.runTaskTimer(plugin, 1L, 1L);
	}

	private void stopRaceTicker() {
		BukkitRunnable t = raceTickTask;
		raceTickTask = null;
		if (t != null) {
			try {
				t.cancel();
			} catch (Throwable ignored) {
			}
		}
	}

	public java.util.Set<UUID> getParticipants() {
		return java.util.Collections.unmodifiableSet(registered);
	}

	public void setTotalLaps(int laps) {
		this.totalLaps = Math.max(1, laps);
	}

	// Pit mechanic removed: no mandatory pitstops API

	// ===================== Live position calculation =====================
	private void initPathForLivePositions() {
		java.util.List<org.bukkit.Location> cl = trackConfig.getCenterline();
		if (cl == null || cl.isEmpty()) {
			pathReady = false;
			path = java.util.Collections.emptyList();
			gateIndex = new int[0];
			arcCum = new double[0];
			arcLapLength = 0.0;
			arcReady = false;
			return;
		}
		path = cl;
		// Build gates: checkpoint centers and finish mapped to nearest index
		java.util.List<Region> cps = trackConfig.getCheckpoints();
		int gates = (cps == null ? 0 : cps.size()) + 1; // + finish
		gateIndex = new int[gates];
		int seed = 0;
		if (cps != null) {
			for (int i = 0; i < cps.size(); i++) {
				org.bukkit.Location c = centerOf(cps.get(i));
				seed = nearestPathIndex(c, seed, Math.max(100, path.size()));
				gateIndex[i] = seed;
			}
		}
		// finish gate
		org.bukkit.Location fin = centerOf(trackConfig.getFinish());
		seed = nearestPathIndex(fin, seed, Math.max(100, path.size()));
		if (gateIndex.length > 0)
			gateIndex[gateIndex.length - 1] = seed;
		pathReady = true;
		rebuildArcCache();
	}

	private void rebuildArcCache() {
		arcReady = false;
		arcLapLength = 0.0;
		if (!pathReady || path == null)
			return;
		int n = path.size();
		if (n < 2)
			return;

		double[] cum = new double[n];
		cum[0] = 0.0;
		double sum = 0.0;
		for (int i = 1; i < n; i++) {
			org.bukkit.Location a = path.get(i - 1);
			org.bukkit.Location b = path.get(i);
			double d = 0.0;
			try {
				if (a != null && b != null && a.getWorld() != null && b.getWorld() != null
						&& a.getWorld().equals(b.getWorld())) {
					d = a.distance(b);
				}
			} catch (Throwable ignored) {
				d = 0.0;
			}
			if (!Double.isFinite(d) || d < 0.0)
				d = 0.0;
			sum += d;
			cum[i] = sum;
		}

		// Close the loop (path is treated as a cycle throughout the live progress code).
		double close = 0.0;
		try {
			org.bukkit.Location last = path.get(n - 1);
			org.bukkit.Location first = path.get(0);
			if (last != null && first != null && last.getWorld() != null && first.getWorld() != null
					&& last.getWorld().equals(first.getWorld())) {
				close = last.distance(first);
			}
		} catch (Throwable ignored) {
			close = 0.0;
		}
		if (!Double.isFinite(close) || close < 0.0)
			close = 0.0;

		this.arcCum = cum;
		this.arcLapLength = Math.max(0.0, sum + close);
		this.arcReady = this.arcLapLength > 0.0001;
	}

	private static int clampIndex(int idx, int n) {
		if (n <= 0)
			return 0;
		if (idx < 0)
			return 0;
		if (idx >= n)
			return n - 1;
		return idx;
	}

	private double arcDistanceForward(int fromIndex, int toIndex) {
		if (!arcReady || arcCum == null || arcCum.length < 2)
			return 0.0;
		int n = arcCum.length;
		int from = clampIndex(fromIndex, n);
		int to = clampIndex(toIndex, n);
		if (from == to)
			return 0.0;

		double a = arcCum[from];
		double b = arcCum[to];
		if (!Double.isFinite(a))
			a = 0.0;
		if (!Double.isFinite(b))
			b = 0.0;

		if (to > from) {
			return Math.max(0.0, b - a);
		}
		// Wrap across the lap boundary.
		double d = arcLapLength - (a - b);
		if (!Double.isFinite(d))
			d = 0.0;
		return Math.max(0.0, d);
	}

	private double totalProgressMeters(UUID id) {
		if (id == null)
			return -1.0;
		if (!arcReady)
			return -1.0;
		ParticipantState s = participants.get(id);
		if (s == null)
			return -1.0;

		// Finished racers are clamped to full race distance.
		if (s.finished)
			return (double) getTotalLaps() * arcLapLength;

		int n = (path == null ? 0 : path.size());
		if (n <= 0)
			return -1.0;
		int idx = clampIndex(s.lastPathIndex, n);
		int startIdx = finishGateIndex();
		double inLap = arcDistanceForward(startIdx, idx);
		return (double) Math.max(0, s.currentLap) * arcLapLength + inLap;
	}

	/**
	 * Track-following distance (arc-length) along the centerline from {@code fromId} to {@code toId},
	 * following race direction and accounting for completed laps.
	 *
	 * Returns -1 if arc-length cannot be computed (no centerline/path).
	 */
	public double getArcDistanceMeters(UUID fromId, UUID toId) {
		double from = totalProgressMeters(fromId);
		double to = totalProgressMeters(toId);
		if (from < 0.0 || to < 0.0)
			return -1.0;
		double d = to - from;
		if (!Double.isFinite(d) || d < 0.0)
			return -1.0;
		return d;
	}

	private static org.bukkit.Location centerOf(Region r) {
		org.bukkit.util.BoundingBox b = r.getBox();
		org.bukkit.World w = org.bukkit.Bukkit.getWorld(r.getWorldName());
		if (b == null)
			return new org.bukkit.Location(w, 0.0, 0.0, 0.0);

		// Match Region.containsXZ() block-selection semantics: upper bounds are
		// half-open with +1.
		double minX = Math.min(b.getMinX(), b.getMaxX());
		double maxX = Math.max(b.getMinX(), b.getMaxX()) + 1.0;
		double minZ = Math.min(b.getMinZ(), b.getMaxZ());
		double maxZ = Math.max(b.getMinZ(), b.getMaxZ()) + 1.0;

		double x = (minX + maxX) * 0.5;
		double z = (minZ + maxZ) * 0.5;
		double y = (b.getMinY() + b.getMaxY()) * 0.5;
		return new org.bukkit.Location(w, x, y, z);
	}

	private int nearestPathIndex(org.bukkit.Location pos, int seed, int window) {
		if (path == null || path.isEmpty() || pos == null || pos.getWorld() == null)
			return 0;
		int n = path.size();
		int bestIdx = Math.max(0, Math.min(seed, n - 1));
		double best = Double.POSITIVE_INFINITY;
		int from = Math.max(0, bestIdx - window);
		int to = Math.min(n - 1, bestIdx + window);
		org.bukkit.World w = pos.getWorld();
		for (int i = from; i <= to; i++) {
			org.bukkit.Location node = path.get(i);
			if (node.getWorld() == null || !node.getWorld().equals(w))
				continue;
			double d = node.distanceSquared(pos);
			if (d < best) {
				best = d;
				bestIdx = i;
			}
		}

		// If we failed to find a close match in the local window (e.g., after lap
		// wrap), do a full scan.
		// Threshold: 64 blocks squared.
		if (best > (64.0 * 64.0)) {
			for (int i = 0; i < n; i++) {
				org.bukkit.Location node = path.get(i);
				if (node.getWorld() == null || !node.getWorld().equals(w))
					continue;
				double d = node.distanceSquared(pos);
				if (d < best) {
					best = d;
					bestIdx = i;
				}
			}
		}
		return bestIdx;
	}

	private static int forwardDistance(int from, int to, int n) {
		int size = Math.max(1, n);
		int a = ((from % size) + size) % size;
		int b = ((to % size) + size) % size;
		if (b >= a)
			return b - a;
		return (size - a) + b;
	}

	private int finishGateIndex() {
		if (gateIndex != null && gateIndex.length > 0)
			return Math.max(0, Math.min(gateIndex[gateIndex.length - 1], path.size() - 1));
		return 0;
	}

	private int totalCheckpointsForProgress() {
		if (testCheckpointCount >= 0)
			return Math.max(0, testCheckpointCount);
		try {
			return trackConfig != null && trackConfig.getCheckpoints() != null ? Math.max(0, trackConfig.getCheckpoints().size()) : 0;
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private double lapProgressRatioInternal(ParticipantState s) {
		if (s == null)
			return 0.0;
		if (!pathReady || path.isEmpty())
			return 0.0;
		if (gateIndex == null || gateIndex.length == 0)
			return 0.0;

		int n = path.size();
		int idx = Math.max(0, Math.min(s.lastPathIndex, n - 1));

		int totalCp = totalCheckpointsForProgress();
		int totalSegments = Math.max(1, totalCp + 1);

		// Segment index is the next expected checkpoint (0..totalCp). When totalCp is
		// reached, we are in the final segment leading to finish.
		int seg = s.nextCheckpointIndex;
		if (seg < 0)
			seg = 0;
		if (seg > totalCp)
			seg = totalCp;

		int finishIdx = finishGateIndex();

		// Gate ordering for progress within a lap:
		// start (= finish line) -> cp1 -> ... -> last cp -> finish
		int prevGate;
		if (seg == 0) {
			prevGate = finishIdx;
		} else {
			int pi = Math.min(seg - 1, gateIndex.length - 1);
			prevGate = gateIndex[pi];
		}

		int nextGate;
		if (seg < totalCp) {
			int ni = Math.min(seg, gateIndex.length - 1);
			nextGate = gateIndex[ni];
		} else {
			nextGate = finishIdx;
		}

		prevGate = Math.max(0, Math.min(prevGate, n - 1));
		nextGate = Math.max(0, Math.min(nextGate, n - 1));

		int segLen = forwardDistance(prevGate, nextGate, n);
		if (segLen <= 0) {
			// Degenerate segment; still keep monotonic behavior.
			double base = (double) seg / (double) totalSegments;
			return Math.max(0.0, Math.min(1.0, base));
		}

		int posInSeg = forwardDistance(prevGate, idx, n);
		if (posInSeg < 0)
			posInSeg = 0;
		if (posInSeg > segLen)
			posInSeg = segLen;

		double segRatio = (double) posInSeg / (double) segLen;
		double out = ((double) seg + segRatio) / (double) totalSegments;
		if (!Double.isFinite(out))
			out = 0.0;
		return Math.max(0.0, Math.min(1.0, out));
	}

	private double liveProgressValue(UUID id) {
		ParticipantState s = participants.get(id);
		if (s == null)
			return 0.0;
		if (s.finished)
			return getTotalLaps();
		double intra = lapProgressRatioInternal(s);
		return (double) s.currentLap + intra;
	}

	public double getLapProgressRatio(UUID id) {
		ParticipantState s = participants.get(id);
		if (s == null)
			return 0.0;
		return lapProgressRatioInternal(s);
	}

	public long getRaceElapsedMillis() {
		if (!running && raceStartMillis == 0L)
			return 0L;
		long now = System.currentTimeMillis();
		return Math.max(0L, now - raceStartMillis);
	}

	public long getRaceStartMillis() {
		return raceStartMillis;
	}

	/**
	 * Remaining seconds for the active start countdown, or 0 if none.
	 */
	public int getCountdownRemainingSeconds() {
		long now = System.currentTimeMillis();
		long end = 0L;
		if (registering && waitingEndMillis > now)
			end = waitingEndMillis;
		else if (introEndMillis > now)
			end = introEndMillis;
		else if (countdownEndMillis > now)
			end = countdownEndMillis;
		if (end <= now)
			return 0;
		return (int) ((end - now + 999L) / 1000L);
	}

	/**
	 * Remaining seconds until this race instance auto-cleans up after everyone
	 * finished.
	 * Returns 0 when not in the post-finish ending window.
	 */
	public int getPostFinishCleanupRemainingSeconds() {
		long now = System.currentTimeMillis();
		long end = postFinishCleanupEndMillis;
		if (end <= now)
			return 0;
		return (int) ((end - now + 999L) / 1000L);
	}

	public java.util.List<UUID> getLiveOrder() {
		java.util.List<UUID> ids = new java.util.ArrayList<>(participants.keySet());
		// finished racers first by finishTime, then unfinished by live progress desc
		ids.sort((a, b) -> {
			ParticipantState sa = participants.get(a);
			ParticipantState sb = participants.get(b);
			if (sa == null && sb == null)
				return a.compareTo(b);
			if (sa == null)
				return 1;
			if (sb == null)
				return -1;
			boolean fa = sa != null && sa.finished;
			boolean fb = sb != null && sb.finished;
			if (fa && fb) {
				long ta = sa.finishTimeMillis;
				long tb = sb.finishTimeMillis;
				return Long.compare(ta, tb);
			}
			if (fa)
				return -1;
			if (fb)
				return 1;
			// both unfinished: compare lap first, then path progress
			int lapCmp = Integer.compare(sb.currentLap, sa.currentLap);
			if (lapCmp != 0)
				return lapCmp;
			double pa = liveProgressValue(a) - sa.currentLap;
			double pb = liveProgressValue(b) - sb.currentLap;
			int cmp = Double.compare(pb, pa);
			if (cmp != 0)
				return cmp;
			// tie-breaker: next checkpoint index (further along)
			int cpCmp = Integer.compare(sb.nextCheckpointIndex, sa.nextCheckpointIndex);
			if (cpCmp != 0)
				return cpCmp;
			// final tie-breaker: UUID (stable)
			return a.compareTo(b);
		});
		return ids;
	}
}
