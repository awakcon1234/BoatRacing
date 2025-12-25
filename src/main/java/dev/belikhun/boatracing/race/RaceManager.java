package dev.belikhun.boatracing.race;

import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.track.Region;
import dev.belikhun.boatracing.util.Text;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Race manager with minimal yet functional placement and countdown.
 */
public class RaceManager {
    private Plugin plugin;
    @SuppressWarnings("unused")
    private final TrackConfig trackConfig;
    private boolean running = false;
    private boolean registering = false;
    private final Set<UUID> registered = new HashSet<>();
    private int totalLaps = 3;
    // Pit mechanic removed: no mandatory pitstops

    // runtime participant state
    private final java.util.Map<UUID, ParticipantState> participants = new java.util.HashMap<>();
    private final java.util.Map<UUID, Player> participantPlayers = new java.util.HashMap<>();
    private final java.util.Map<UUID, UUID> spawnedBoatByPlayer = new java.util.HashMap<>();
    private final java.util.Set<UUID> countdownPlayers = new java.util.HashSet<>();
    private long raceStartMillis = 0L;
    // Countdown end (millis) for the start countdown; 0 if no countdown active
    private volatile long countdownEndMillis = 0L;
    // Waiting end (millis) for registration waiting phase; 0 if none
    private volatile long waitingEndMillis = 0L;

    // Centerline-based live position
    private java.util.List<org.bukkit.Location> path = java.util.Collections.emptyList();
    private int[] gateIndex = new int[0]; // indices along path for each checkpoint and finish
    private boolean pathReady = false;

    private BukkitRunnable raceTickTask;
    private BukkitTask registrationStartTask;
    private BukkitRunnable countdownTask;
    private BukkitRunnable countdownFreezeTask;
    private final java.util.Map<UUID, org.bukkit.Location> countdownLockLocation = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> countdownDebugLastLog = new java.util.HashMap<>();
    // NOTE: We intentionally do not use Boat physics setters (maxSpeed/deceleration/workOnLand)
    // because they are deprecated in modern Paper. Countdown freezing is enforced via snapping.

    private static final class PreferredBoatData {
        final boolean chest;
        final String baseType; // Boat.Type name (e.g. OAK, SPRUCE, BAMBOO)
        PreferredBoatData(boolean chest, String baseType) {
            this.chest = chest;
            this.baseType = baseType;
        }
    }

    // Debug helpers
    private boolean debugTeleport() {
        try { return plugin != null && plugin.getConfig().getBoolean("racing.debug.teleport", false); }
        catch (Throwable ignored) { return false; }
    }
    private boolean debugCheckpoints() {
        try { return plugin != null && plugin.getConfig().getBoolean("racing.debug.checkpoints", false); }
        catch (Throwable ignored) { return false; }
    }

    private boolean debugCountdownFreeze() {
        try { return plugin != null && plugin.getConfig().getBoolean("racing.debug.countdown-freeze", false); }
        catch (Throwable ignored) { return false; }
    }

    private PreferredBoatData resolvePreferredBoat(UUID id) {
        if (id == null) return new PreferredBoatData(false, null);
        if (!(plugin instanceof dev.belikhun.boatracing.BoatRacingPlugin br) || br.getProfileManager() == null) {
            return new PreferredBoatData(false, null);
        }
        String bt;
        try { bt = br.getProfileManager().getBoatType(id); }
        catch (Throwable ignored) { bt = null; }

        if (bt == null || bt.isBlank()) return new PreferredBoatData(false, null);

        Material pref = null;
        try {
            String norm = bt.trim().toUpperCase(java.util.Locale.ROOT);
            try { pref = Material.valueOf(norm); } catch (IllegalArgumentException ignored) { pref = null; }
            if (pref == null) pref = Material.matchMaterial(bt);
        } catch (Throwable ignored) { pref = null; }

        boolean chest = false;
        String base = null;

        if (pref != null) {
            chest = pref.name().endsWith("_CHEST_BOAT") || pref.name().endsWith("_CHEST_RAFT");
            base = pref.name()
                    .replace("_CHEST_BOAT", "").replace("_BOAT", "")
                    .replace("_CHEST_RAFT", "").replace("_RAFT", "");
        } else {
            // Back-compat: accept older stored values like "OAK" or "SPRUCE".
            String norm = bt.trim().toUpperCase(java.util.Locale.ROOT);
            chest = norm.endsWith("_CHEST_BOAT") || norm.endsWith("_CHEST_RAFT") || norm.contains("CHEST_BOAT") || norm.contains("CHEST_RAFT");
            try { base = org.bukkit.entity.Boat.Type.valueOf(norm).name(); }
            catch (IllegalArgumentException ignored) { base = null; }
        }

        return new PreferredBoatData(chest, base);
    }

    private static float absAngleDelta(float a, float b) {
        float d = (a - b) % 360.0f;
        if (d > 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return Math.abs(d);
    }

    private void restoreCountdownBoatPhysics() {
        // Kept for compatibility with existing call sites; no-op (see note above).
    }

    // (NMS force snap moved to util.EntityForceTeleport)
    private void dbg(String msg) {
        try { if (plugin != null) plugin.getLogger().info(msg); } catch (Throwable ignored) {}
    }

    public RaceManager(Plugin plugin, TrackConfig trackConfig) {
        this.plugin = plugin;
        this.trackConfig = trackConfig;
    }

    // test-only constructor that avoids needing a Plugin instance in unit tests
    public RaceManager(TrackConfig trackConfig) {
        this.plugin = null;
        this.trackConfig = trackConfig;
    }

    public TrackConfig getTrackConfig() { return trackConfig; }

    public boolean isRunning() { return running; }
    public boolean isRegistering() { return registering; }
    public Set<UUID> getRegistered() { return Collections.unmodifiableSet(registered); }

    public boolean isAnyCountdownActive() {
        return countdownTask != null && !countdownPlayers.isEmpty();
    }

    public boolean isInvolved(UUID id) {
        if (id == null) return false;
        return registered.contains(id) || participants.containsKey(id) || countdownPlayers.contains(id);
    }

    public java.util.Set<UUID> getInvolved() {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        out.addAll(registered);
        out.addAll(participants.keySet());
        out.addAll(countdownPlayers);
        return out;
    }

    public boolean shouldPreventBoatExit(UUID id) {
        if (id == null) return false;
        // During live race: only active (not finished) racers
        if (running) {
            ParticipantState s = participants.get(id);
            return s != null && !s.finished;
        }
        // During countdown: keep registered racers seated
        return countdownTask != null && countdownPlayers.contains(id);
    }

    public boolean isCountdownActiveFor(UUID id) {
        if (id == null) return false;
        if (running) return false;
        return countdownTask != null && countdownPlayers.contains(id);
    }

    public org.bukkit.Location getCountdownLockLocation(UUID id) {
        if (id == null) return null;
        org.bukkit.Location l = countdownLockLocation.get(id);
        return l == null ? null : l.clone();
    }

    /**
     * Respawn helpers:
     * - If player is in countdown: respawn to their locked start position.
     * - If player is racing: respawn at their last checkpoint; if all checkpoints reached, respawn at start.
     * Returns null when the player isn't in countdown/race (let vanilla handle it).
     */
    public org.bukkit.Location getRaceRespawnLocation(UUID id, org.bukkit.Location deathLocation) {
        if (id == null) return null;

        // Countdown: keep them at the locked start spot.
        if (isCountdownActiveFor(id)) {
            org.bukkit.Location lock = getCountdownLockLocation(id);
            if (lock != null) return lock;
        }

        ParticipantState s = participants.get(id);
        if (s == null || s.finished) return null;

        java.util.List<Region> cps = trackConfig.getCheckpoints();
        if (cps == null || cps.isEmpty()) {
            return getStartRespawnLocation(deathLocation);
        }

        // If they already reached all checkpoints for this lap (awaiting finish), put them back at start.
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

    public void ensureRacerHasBoat(Player p) {
        if (p == null || !p.isOnline()) return;
        UUID id = p.getUniqueId();

        // Only apply to countdown/racing participants.
        if (!isCountdownActiveFor(id)) {
            ParticipantState s = participants.get(id);
            if (s == null || s.finished) return;
        }

        try {
            Entity curVeh = p.getVehicle();
            if (curVeh instanceof org.bukkit.entity.Boat || curVeh instanceof org.bukkit.entity.ChestBoat) {
                return;
            }
        } catch (Throwable ignored) {}

        // Remove prior plugin-spawned boat for this player if it still exists.
        try {
            UUID boatId = spawnedBoatByPlayer.get(id);
            if (boatId != null) {
                Entity e = p.getWorld().getEntity(boatId);
                if (e != null && isSpawnedBoat(e)) {
                    try { e.remove(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            Location target = p.getLocation().clone();


            PreferredBoatData pref = resolvePreferredBoat(id);
            EntityType spawnType = pref.chest ? EntityType.CHEST_BOAT : EntityType.BOAT;
            var ent = (target.getWorld() != null ? target.getWorld() : p.getWorld()).spawnEntity(target, spawnType);

            try {
                markSpawnedBoat(ent);
                spawnedBoatByPlayer.put(id, ent.getUniqueId());
            } catch (Throwable ignored) {}


            String base = pref.baseType;
            if (ent instanceof Boat b) {
                if (base != null) {
                    try { b.setBoatType(Boat.Type.valueOf(base)); } catch (Throwable ignored) {}
                }
                try { b.addPassenger(p); } catch (Throwable ignored) {
                    try { if (p.isInsideVehicle()) p.leaveVehicle(); } catch (Throwable ignored2) {}
                    try { b.addPassenger(p); } catch (Throwable ignored2) {}
                }
            } else if (ent instanceof ChestBoat cb) {
                if (base != null) {
                    try { cb.setBoatType(Boat.Type.valueOf(base)); } catch (Throwable ignored) {}
                }
                try { cb.addPassenger(p); } catch (Throwable ignored) {
                    try { if (p.isInsideVehicle()) p.leaveVehicle(); } catch (Throwable ignored2) {}
                    try { cb.addPassenger(p); } catch (Throwable ignored2) {}
                }
            } else {
                try { ent.addPassenger(p); } catch (Throwable ignored) {}
            }

            // If they're in countdown, update their lock location to the new boat spot.
            try {
                if (isCountdownActiveFor(id)) {
                    Entity v = p.getVehicle();
                    if (v != null) countdownLockLocation.put(id, v.getLocation().clone());
                    else countdownLockLocation.put(id, p.getLocation().clone());
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private org.bukkit.Location getStartRespawnLocation(org.bukkit.Location deathLocation) {
        org.bukkit.Location base = null;
        try { base = trackConfig.getStartCenter(); } catch (Throwable ignored) {}
        if (base == null) {
            try {
                java.util.List<org.bukkit.Location> starts = trackConfig.getStarts();
                if (starts != null && !starts.isEmpty()) base = starts.get(0);
            } catch (Throwable ignored) {}
        }

        World w = (base != null) ? base.getWorld() : null;
        if (w == null && deathLocation != null) w = deathLocation.getWorld();
        if (w == null) return null;

        double x = (base != null) ? base.getX() : w.getSpawnLocation().getX();
        double z = (base != null) ? base.getZ() : w.getSpawnLocation().getZ();
        int yHint = (deathLocation != null) ? deathLocation.getBlockY() : ((base != null) ? base.getBlockY() : w.getSpawnLocation().getBlockY());
        float yaw = (deathLocation != null) ? deathLocation.getYaw() : ((base != null) ? base.getYaw() : 0f);
        float pitch = (deathLocation != null) ? deathLocation.getPitch() : ((base != null) ? base.getPitch() : 0f);

        return safeSpawnAt(w, x, z, yHint, yaw, pitch);
    }

    private org.bukkit.Location getRegionRespawnLocation(Region r, org.bukkit.Location deathLocation) {
        if (r == null) return null;
        BoundingBox b = null;
        try { b = r.getBox(); } catch (Throwable ignored) {}
        if (b == null) return null;

        World w = null;
        try {
            String wn = r.getWorldName();
            if (wn != null) w = Bukkit.getWorld(wn);
        } catch (Throwable ignored) {}
        if (w == null && deathLocation != null) w = deathLocation.getWorld();
        if (w == null) return null;

        double x = (Math.min(b.getMinX(), b.getMaxX()) + Math.max(b.getMinX(), b.getMaxX())) * 0.5;
        double z = (Math.min(b.getMinZ(), b.getMaxZ()) + Math.max(b.getMinZ(), b.getMaxZ())) * 0.5;
        int yHint = (deathLocation != null) ? deathLocation.getBlockY() : (int) Math.round((b.getMinY() + b.getMaxY()) * 0.5);
        float yaw = (deathLocation != null) ? deathLocation.getYaw() : 0f;
        float pitch = (deathLocation != null) ? deathLocation.getPitch() : 0f;
        return safeSpawnAt(w, x, z, yHint, yaw, pitch);
    }

    private static org.bukkit.Location safeSpawnAt(World w, double x, double z, int yHint, float yaw, float pitch) {
        if (w == null) return null;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 2;

        int hint = Math.max(minY, Math.min(maxY, yHint));
        int best = Integer.MIN_VALUE;

        // Prefer a nearby solid block under the hint (tracks are usually flat-ish).
        for (int dy = 0; dy <= 12; dy++) {
            int y = hint - dy;
            if (y < minY) break;
            try {
                org.bukkit.block.Block below = w.getBlockAt((int) Math.floor(x), y, (int) Math.floor(z));
                org.bukkit.block.Block above = w.getBlockAt((int) Math.floor(x), y + 1, (int) Math.floor(z));
                if (below.getType().isSolid() && above.getType().isAir()) {
                    best = y;
                    break;
                }
            } catch (Throwable ignored) {}
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
     * Called on movement with both endpoints so we can do swept intersection checks.
     */
    public void tickPlayer(Player player, Location from, Location to) {
        if (!running) return;
        if (to == null) return;
        ParticipantState s = participants.get(player.getUniqueId());
        if (s == null || s.finished) return;

        Location segFrom = from;
        if (segFrom == null) segFrom = s.lastTickLocation;
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
        } catch (Throwable ignored) {}

        // Pit mechanic removed

        // Checkpoints
        java.util.List<Region> checkpoints = trackConfig.getCheckpoints();

        // Debug-only: report which checkpoint region (if any) the segment intersects.
        if (debugCheckpoints()) {
            int insideAny = -1;
            for (int i = 0; i < checkpoints.size(); i++) {
                Region r = checkpoints.get(i);
                if (r != null && (r.containsXZ(to) || r.intersectsXZ(segFrom, to))) { insideAny = i; break; }
            }
            if (insideAny != s.lastInsideCheckpoint) {
                s.lastInsideCheckpoint = insideAny;
                if (insideAny >= 0) {
                    dbg("[CPDBG] " + player.getName() + " entered checkpoint " + (insideAny + 1) + "/" + checkpoints.size()
                            + " at " + dev.belikhun.boatracing.util.Text.fmtPos(to)
                            + " expectedNext=" + (s.nextCheckpointIndex + 1)
                            + " awaitingFinish=" + s.awaitingFinish);
                }
            }
        }

        // Gameplay: only advance in sequence, and allow consuming multiple checkpoints in a single swept segment.
        if (!checkpoints.isEmpty() && !s.awaitingFinish) {
            int advancedCount = 0;
            while (advancedCount < 6 && s.nextCheckpointIndex >= 0 && s.nextCheckpointIndex < checkpoints.size()) {
                Region expected = checkpoints.get(s.nextCheckpointIndex);
                boolean hitExpected = expected != null && (expected.containsXZ(to) || expected.intersectsXZ(segFrom, to));
                if (!hitExpected) break;

                int hitIndex = s.nextCheckpointIndex;
                boolean advanced = checkpointReachedInternal(player.getUniqueId(), hitIndex);
                if (advanced) {
                    notifyCheckpointPassed(player, hitIndex + 1, checkpoints.size());
                    advancedCount++;
                    if (s.awaitingFinish) break;
                    continue;
                }
                break;
            }
        }

        if (debugCheckpoints() && !s.awaitingFinish && s.nextCheckpointIndex >= 0 && s.nextCheckpointIndex < checkpoints.size()) {
            Region expected = checkpoints.get(s.nextCheckpointIndex);
            if (expected != null) {
                org.bukkit.util.BoundingBox b = expected.getBox();
                org.bukkit.World w = to.getWorld();
                if (b != null && w != null && expected.getWorldName() != null && expected.getWorldName().equals(w.getName())) {
                    // Match Region.containsXZ(): treat as block-selection in X/Z with +1 upper bounds.
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
                    if (dist > 6.0) s.lastNearExpectedBucket = -1;
                }
            }
        }

        // Finish detection
        // IMPORTANT: For checkpoint-based tracks, lap completion is driven by checkpoint flow.
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
                dbg("[CPDBG] " + player.getName() + " entered finish but lap not ready (expectedNext=" + (s.nextCheckpointIndex + 1) + ")");
            }
        }

        // Update live path index for player (for live positions)
        if (pathReady) {
            int seed = s.lastPathIndex;
            if (s.awaitingFinish && gateIndex != null && gateIndex.length > 0) {
                seed = gateIndex[gateIndex.length - 1];
            } else if (s.nextCheckpointIndex == 0 && s.currentLap > 0) {
                // After wrapping a lap, bias toward the start of the centerline.
                seed = 0;
            }
            s.lastPathIndex = nearestPathIndex(to, seed, 80);
        }

        // Update last location for next swept tick.
        s.lastTickLocation = to.clone();
    }

    // test hook: allow tests to simulate checkpoints without needing Region instances
    private int testCheckpointCount = -1;
    void setTestCheckpointCount(int n) { this.testCheckpointCount = n; }

    // package-private helpers for testing and fine-grained control
    void checkpointReached(UUID uuid, int checkpointIndex) {
        checkpointReachedInternal(uuid, checkpointIndex);
    }

    private boolean checkpointReachedInternal(UUID uuid, int checkpointIndex) {
        ParticipantState s = participants.get(uuid);
        if (s == null || s.finished) return false;
        if (checkpointIndex != s.nextCheckpointIndex) return false; // enforce sequence
        s.nextCheckpointIndex++;
        int totalCheckpoints = testCheckpointCount >= 0 ? testCheckpointCount : trackConfig.getCheckpoints().size();
        if (totalCheckpoints > 0 && s.nextCheckpointIndex >= totalCheckpoints) {
            // Completed all checkpoints for this lap; now require crossing the finish line to complete the lap.
            s.nextCheckpointIndex = totalCheckpoints;
            s.awaitingFinish = true;
        }
        return true;
    }

    public ParticipantState getParticipantState(UUID uuid) { return participants.get(uuid); }

    // test helper: add a participant without needing a Player or a running race
    void addParticipantForTests(UUID uuid) {
        participants.put(uuid, new ParticipantState(uuid));
    }

    // test hook: allow tests to simulate finish crossing
    void finishCrossedForTests(UUID uuid) {
        ParticipantState s = participants.get(uuid);
        if (s == null || s.finished) return;
        // For checkpoint tracks, only complete lap when awaiting finish.
        if ((testCheckpointCount >= 0 ? testCheckpointCount : trackConfig.getCheckpoints().size()) > 0) {
            if (!s.awaitingFinish) return;
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
        if (s == null || s.finished) return;
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
            if (pos != null) s.lastPathIndex = nearestPathIndex(pos, 0, Math.max(200, path.size()));
            else s.lastPathIndex = 0;
        }
    }

    private void notifyCheckpointPassed(Player p, int passed, int total) {
        try {
            var sub = net.kyori.adventure.text.Component.text("Điểm kiểm tra " + passed + "/" + total)
                    .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
            p.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.empty(),
                    sub,
                net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(100),
                            java.time.Duration.ofMillis(700),
                            java.time.Duration.ofMillis(200)
                    )));
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.6f);
        } catch (Throwable ignored) {}
    }

    private void notifyLapCompleted(Player p, int lap, int total) {
        try {
            var sub = net.kyori.adventure.text.Component.text("Hoàn thành vòng " + lap + "/" + total)
                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN);
            p.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.empty(),
                    sub,
                net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(100),
                            java.time.Duration.ofMillis(900),
                            java.time.Duration.ofMillis(250)
                    )));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.15f);
        } catch (Throwable ignored) {}
    }

    void finishPlayer(UUID uuid) {
        ParticipantState s = participants.get(uuid);
        if (s == null || s.finished) return;
        s.finished = true;
        s.finishTimeMillis = System.currentTimeMillis();
        // compute position as number of already finished + 1
        int pos = (int) participants.values().stream().filter(x -> x.finished && x.finishTimeMillis > 0).count();
        s.finishPosition = Math.max(1, pos);
        Player p = participantPlayers.get(uuid);
        if (p != null) {
            Text.msg(p, "&6Bạn đã về đích! Vị trí: &e" + s.finishPosition);
        }
    }

    /**
     * Get standings ordered by finish time + penalty (unfinished players last)
     */
    public List<ParticipantState> getStandings() {
        List<ParticipantState> out = new ArrayList<>(participants.values());
        out.sort((a,b) -> {
            if (a.finished && b.finished) {
                long ta = a.finishTimeMillis + a.penaltySeconds*1000L;
                long tb = b.finishTimeMillis + b.penaltySeconds*1000L;
                return Long.compare(ta, tb);
            } else if (a.finished) return -1;
            else if (b.finished) return 1;
            else return Long.compare(b.currentLap, a.currentLap); // more laps ahead first
        });
        return out;
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

        // Used to edge-trigger finish crossings (avoid repeated lap completions when inside the finish area)
        public boolean wasInsideFinish = false;

        // For checkpoint tracks: last checkpoint reached, now waiting to cross finish to complete the lap.
        public boolean awaitingFinish = false;

        public ParticipantState(UUID id) { this.id = id; }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public int getTotalLaps() {
        return Math.max(1, totalLaps);
    }

    public boolean openRegistration(int laps, Object unused) {
        // If there is an existing scheduled registration transition, cancel it.
        if (registrationStartTask != null) {
            try { registrationStartTask.cancel(); } catch (Throwable ignored) {}
            registrationStartTask = null;
        }
        this.registering = true;
        this.totalLaps = laps;
        // Waiting countdown should only start once at least 1 racer is waiting.
        this.waitingEndMillis = 0L;
        return true;
    }

    private void ensureRegistrationCountdownScheduledIfNeeded() {
        if (plugin == null) return;
        if (!registering) return;
        if (registrationStartTask != null) return;
        if (registered.isEmpty()) return;

        int waitSec = Math.max(1, plugin.getConfig().getInt("racing.registration-seconds", 30));
        this.waitingEndMillis = System.currentTimeMillis() + (waitSec * 1000L);

        registrationStartTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            registrationStartTask = null;
            if (!registering) { waitingEndMillis = 0L; return; }
            if (registered.isEmpty()) { cancelRegistration(false); waitingEndMillis = 0L; return; }
            java.util.List<org.bukkit.entity.Player> participants = new java.util.ArrayList<>();
            for (java.util.UUID id : new java.util.LinkedHashSet<>(registered)) {
                org.bukkit.entity.Player rp = plugin.getServer().getPlayer(id);
                if (rp != null && rp.isOnline()) participants.add(rp);
            }
            if (participants.isEmpty()) { cancelRegistration(false); waitingEndMillis = 0L; return; }
            java.util.List<org.bukkit.entity.Player> placed = placeAtStartsWithBoats(participants);
            if (placed.isEmpty()) { cancelRegistration(false); waitingEndMillis = 0L; return; }
            if (placed.size() < participants.size()) {
                for (org.bukkit.entity.Player p : participants) if (!placed.contains(p)) dev.belikhun.boatracing.util.Text.msg(p, "&7Không đủ vị trí xuất phát cho tất cả người chơi đã đăng ký.");
            }
            this.registering = false;
            waitingEndMillis = 0L;
            startLightsCountdown(placed);
        }, waitSec * 20L);
    }

    public boolean join(Player p) {
        if (!registering) return false;
        boolean added = registered.add(p.getUniqueId());
        if (added) {
            // Start the waiting countdown only after the first racer joins.
            ensureRegistrationCountdownScheduledIfNeeded();
            try {
                // Ensure player isn't stuck in an old vehicle when joining.
                if (p.isInsideVehicle()) p.leaveVehicle();
            } catch (Throwable ignored) {}
            // Prefer waiting spawn; else fall back to start center; else finish center
            org.bukkit.Location dest = trackConfig.getWaitingSpawn();
            if (debugTeleport()) {
                dbg("[TPDBG] join(" + p.getName() + ") track=" + trackConfig.getCurrentName()
                        + " trackWorld=" + trackConfig.getWorldName()
                        + " waitingSpawn=" + (dest == null ? "null" : dev.belikhun.boatracing.util.Text.fmtPos(dest))
                        + " destWorld=" + (dest == null || dest.getWorld() == null ? "null" : dest.getWorld().getName()));
            }
            if (dest == null) dest = trackConfig.getStartCenter();
            if (dest == null && trackConfig.getFinish() != null) {
                try { dest = centerOf(trackConfig.getFinish()); } catch (Throwable ignored) {}
            }
            boolean ok = false;
            if (dest != null && dest.getWorld() != null) {
                try { ok = p.teleport(dest); } catch (Throwable ignored) { ok = false; }
            }
            if (debugTeleport()) {
                dbg("[TPDBG] teleport primary ok=" + ok + " dest=" + (dest == null ? "null" : dev.belikhun.boatracing.util.Text.fmtPos(dest))
                        + " destWorld=" + (dest == null || dest.getWorld() == null ? "null" : dest.getWorld().getName()));
            }
            // fallback if teleport failed (rare but possible)
            if (!ok) {
                org.bukkit.Location fb = trackConfig.getStartCenter();
                if (fb == null && trackConfig.getFinish() != null) {
                    try { fb = centerOf(trackConfig.getFinish()); } catch (Throwable ignored) {}
                }
                if (fb != null && fb.getWorld() != null) {
                    try { p.teleport(fb); } catch (Throwable ignored) {}
                    if (debugTeleport()) {
                        dbg("[TPDBG] teleport fallback -> " + dev.belikhun.boatracing.util.Text.fmtPos(fb)
                                + " world=" + (fb.getWorld() == null ? "null" : fb.getWorld().getName()));
                    }
                }
            }

            // Join sound
            try { p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.6f); } catch (Throwable ignored) {}
        }
        return added;
    }

    public boolean leave(Player p) {
        return registered.remove(p.getUniqueId());
    }

    public void forceStart() {
        this.registering = false;
        if (registered.isEmpty()) return;
        // Build participants from currently registered and online
        java.util.List<Player> participants = new java.util.ArrayList<>();
        for (UUID id : new java.util.LinkedHashSet<>(registered)) {
            Player rp = plugin.getServer().getPlayer(id);
            if (rp != null && rp.isOnline()) participants.add(rp);
        }
        if (participants.isEmpty()) return;
        // Place at starts and run the same lights countdown
        java.util.List<Player> placed = placeAtStartsWithBoats(participants);
        if (placed.isEmpty()) return;
        if (placed.size() < participants.size()) {
            for (Player p : participants) if (!placed.contains(p)) dev.belikhun.boatracing.util.Text.msg(p, "&7Không đủ vị trí xuất phát cho tất cả người chơi đã đăng ký.");
        }
        startLightsCountdown(placed);
    }

    // Place participants at start locations and spawn boats for them
    public List<Player> placeAtStartsWithBoats(List<Player> participants) {
        List<Location> starts = trackConfig.getStarts();
        if (starts.isEmpty()) return Collections.emptyList();
        List<Player> placed = new ArrayList<>();
        int slot = 0;
        for (Player p : participants) {
            if (slot >= starts.size()) break; // no more slots
            Location boatSpawn = starts.get(slot).clone();
            Location target = boatSpawn.clone();
            try {
                // Always dismount the player first.
                // If they are in one of our spawned boats (e.g. restart), remove it; otherwise, just eject.
                try {
                    Entity curVeh = p.getVehicle();
                    if (curVeh != null) {
                        try { curVeh.eject(); } catch (Throwable ignored) {}
                        try { p.leaveVehicle(); } catch (Throwable ignored) {}
                        if (isSpawnedBoat(curVeh)) {
                            try { curVeh.remove(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}

                // teleport player slightly above the boat spawn to avoid clipping
                target.setY(target.getY() + 1.0);
                p.teleport(target);

                PreferredBoatData pref = resolvePreferredBoat(p.getUniqueId());
                EntityType spawnType = pref.chest ? EntityType.CHEST_BOAT : EntityType.BOAT;
                var spawnWorld = (boatSpawn.getWorld() != null ? boatSpawn.getWorld() : p.getWorld());
                var ent = spawnWorld.spawnEntity(boatSpawn, spawnType);

                try {
                    markSpawnedBoat(ent);
                    spawnedBoatByPlayer.put(p.getUniqueId(), ent.getUniqueId());
                } catch (Throwable ignored) {}

                // Apply variant when possible.
                String base = pref.baseType;
                if (ent instanceof Boat b) {
                    if (base != null) {
                        try { b.setBoatType(Boat.Type.valueOf(base)); } catch (Throwable ignored) {}
                    }
                    try { b.addPassenger(p); } catch (Throwable ignored) {
                        try { if (p.isInsideVehicle()) p.leaveVehicle(); } catch (Throwable ignored2) {}
                        try { b.addPassenger(p); } catch (Throwable ignored2) {}
                    }
                } else if (ent instanceof ChestBoat cb) {
                    if (base != null) {
                        try { cb.setBoatType(Boat.Type.valueOf(base)); } catch (Throwable ignored) {}
                    }
                    try { cb.addPassenger(p); } catch (Throwable ignored) {
                        try { if (p.isInsideVehicle()) p.leaveVehicle(); } catch (Throwable ignored2) {}
                        try { cb.addPassenger(p); } catch (Throwable ignored2) {}
                    }
                } else {
                    // Fallback: still seat the player if possible
                    try { ent.addPassenger(p); } catch (Throwable ignored) {}
                }
                placed.add(p);
            } catch (Throwable ignored) {}
            slot++;
        }
        return placed;
    }

    // Simple countdown using server scheduler
    public void startLightsCountdown(List<Player> placed) {
        if (placed.isEmpty()) return;
        this.registering = false;

        // Stop any prior countdown before starting a new one.
        if (countdownTask != null) {
            try { countdownTask.cancel(); } catch (Throwable ignored) {}
            countdownTask = null;
        }
        if (countdownFreezeTask != null) {
            try { countdownFreezeTask.cancel(); } catch (Throwable ignored) {}
            countdownFreezeTask = null;
        }

        countdownPlayers.clear();
        countdownLockLocation.clear();
        countdownDebugLastLog.clear();
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
                        // Prefer the plugin-spawned boat entity (covers cases where seating isn't finished yet)
                        lock = null;
                        try {
                            UUID boatId = spawnedBoatByPlayer.get(p.getUniqueId());
                            if (boatId != null) {
                                org.bukkit.entity.Entity e = Bukkit.getEntity(boatId);
                                if (e != null) {
                                    lock = e.getLocation().clone();
                                }
                            }
                        } catch (Throwable ignored) {}
                        if (lock == null) lock = p.getLocation().clone();
                    }

                    // Always lock yaw/pitch to the player's facing direction at countdown start.
                    try {
                        org.bukkit.Location facing = p.getLocation();
                        lock.setYaw(facing.getYaw());
                        lock.setPitch(facing.getPitch());
                    } catch (Throwable ignored) {}

                    countdownLockLocation.put(p.getUniqueId(), lock);
                } catch (Throwable ignored) {}
            }
        }

        final int total = 10; // 10..1..GO
        this.countdownEndMillis = System.currentTimeMillis() + (total * 1000L);

        // Initialize start lights (progress bar).
        try { setStartLightsProgress(0.0); } catch (Throwable ignored) {}

        // Create the countdown task first so the freeze task (delay 0) doesn't cancel itself
        // on the first tick due to countdownTask being null.
        countdownTask = new BukkitRunnable() {
            private int sec = total;

            @Override
            public void run() {
                if (sec <= 0) {
                    // Start!
                    try { setStartLightsProgress(1.0); } catch (Throwable ignored) {}

                    running = true;
                    raceStartMillis = System.currentTimeMillis();
                    countdownEndMillis = 0L;

                    if (countdownFreezeTask != null) {
                        try { countdownFreezeTask.cancel(); } catch (Throwable ignored) {}
                        countdownFreezeTask = null;
                    }
                    countdownPlayers.clear();
                    countdownLockLocation.clear();
                    countdownDebugLastLog.clear();
                    restoreCountdownBoatPhysics();

                    participants.clear();
                    participantPlayers.clear();
                    for (Player p : placed) {
                        ParticipantState st = new ParticipantState(p.getUniqueId());
                        participants.put(p.getUniqueId(), st);
                        participantPlayers.put(p.getUniqueId(), p);
                    }
                    initPathForLivePositions();
                    startRaceTicker();

					if (debugCheckpoints()) {
						try {
							dbg("[CPDBG] Race started. track=" + trackConfig.getCurrentName()
									+ " checkpoints=" + trackConfig.getCheckpoints().size()
									+ " finish=" + (trackConfig.getFinish() == null ? "null" : dev.belikhun.boatracing.util.Text.fmtArea(trackConfig.getFinish())));
						} catch (Throwable ignored) {}
					}

                    for (Player p : placed) {
                        try {
                            var title = net.kyori.adventure.text.Component.text("BẮT ĐẦU!").color(net.kyori.adventure.text.format.TextColor.color(0x00FF00));
                            var sub = net.kyori.adventure.text.Component.text("● ● ●").color(net.kyori.adventure.text.format.NamedTextColor.GREEN);
                            p.showTitle(net.kyori.adventure.title.Title.title(title, sub,
                                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1000), java.time.Duration.ofMillis(200))));
                            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                        } catch (Throwable ignored) {}
                    }
                    cancel();
                    return;
                }

                // Update start lights as a progress bar.
                try {
                    double progress = (double) (total - sec) / (double) total;
                    setStartLightsProgress(progress);
                } catch (Throwable ignored) {}

                countdownEndMillis = System.currentTimeMillis() + (sec * 1000L);
                for (Player p : placed) {
                    try {
                        var title = net.kyori.adventure.text.Component.text(String.valueOf(sec)).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
                        net.kyori.adventure.text.Component dot = net.kyori.adventure.text.Component.text("●");
                        var dark = net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY;
                        net.kyori.adventure.text.Component sub;
                        if (sec > 3) {
                            sub = net.kyori.adventure.text.Component.text("● ● ●").color(dark);
                        } else if (sec == 3) {
                            sub = net.kyori.adventure.text.Component.empty()
                                    .append(dot.color(net.kyori.adventure.text.format.NamedTextColor.RED)).append(net.kyori.adventure.text.Component.text(" "))
                                    .append(dot.color(dark)).append(net.kyori.adventure.text.Component.text(" "))
                                    .append(dot.color(dark));
                        } else if (sec == 2) {
                            sub = net.kyori.adventure.text.Component.empty()
                                    .append(dot.color(net.kyori.adventure.text.format.NamedTextColor.RED)).append(net.kyori.adventure.text.Component.text(" "))
                                    .append(dot.color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)).append(net.kyori.adventure.text.Component.text(" "))
                                    .append(dot.color(dark));
                        } else { // sec == 1
                            sub = net.kyori.adventure.text.Component.empty()
                                    .append(dot.color(dark)).append(net.kyori.adventure.text.Component.text(" "))
                                    .append(dot.color(dark)).append(net.kyori.adventure.text.Component.text(" "))
                                    .append(dot.color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
                        }
                        p.showTitle(net.kyori.adventure.title.Title.title(title, sub,
                                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1000), java.time.Duration.ofMillis(200))));
                        if (sec == 3) {
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 0.90f);
                        } else if (sec == 2) {
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.05f);
                        } else if (sec == 1) {
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.25f);
                        } else {
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                        }
                    } catch (Throwable ignored) {}
                }

                sec--;
            }

            @Override
            public synchronized void cancel() throws IllegalStateException {
                super.cancel();
                if (countdownTask == this) countdownTask = null;
            }
        };

        // Enforce a hard freeze every tick, not just on VehicleMoveEvent.
        countdownFreezeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin == null) { cancel(); return; }
                if (running) { cancel(); return; }
                if (countdownTask == null || countdownPlayers.isEmpty()) { cancel(); return; }

                boolean dbg = debugCountdownFreeze();
                long now = dbg ? System.currentTimeMillis() : 0L;

                for (UUID id : new java.util.ArrayList<>(countdownPlayers)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline()) continue;
                    org.bukkit.Location lock = countdownLockLocation.get(id);
                    if (lock == null) continue;

                    try {
                        org.bukkit.entity.Entity v = p.getVehicle();
                        if (v instanceof org.bukkit.entity.Boat boat) {
                            org.bukkit.Location before = dbg ? boat.getLocation() : null;

                            // Ensure lock has a world (teleport returns false if lock world is null).
                            if (lock.getWorld() == null) {
                                try { lock.setWorld(boat.getWorld()); } catch (Throwable ignored) {}
                            }

                            // Stop velocity (prevents drift/false-start movement).
                            boat.setVelocity(new Vector(0, 0, 0));

                            // Always snap back to the fixed lock location (prevents TPS-lag inching).
                            boolean tpOk;
                            try {
                                // Paper teleport flags: retaining passengers is default since 1.21.10, but explicit is fine.
                                tpOk = boat.teleport(lock, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                            } catch (Throwable t) {
                                try { tpOk = boat.teleport(lock); } catch (Throwable ignored) { tpOk = false; }
                            }
                            boolean nmsOk = false;
                            if (!tpOk) {
                                try { nmsOk = dev.belikhun.boatracing.util.EntityForceTeleport.nms(boat, lock); } catch (Throwable ignored) { nmsOk = false; }
                            }
                            // Re-zero in case teleport preserved any motion
                            boat.setVelocity(new Vector(0, 0, 0));
                            try { boat.setRotation(lock.getYaw(), lock.getPitch()); } catch (Throwable ignored) {}

                            if (dbg) {
                                Long prev = countdownDebugLastLog.get(id);
                                if (prev == null || (now - prev) >= 1000L) {
                                    countdownDebugLastLog.put(id, now);
                                    org.bukkit.Location cur = (before != null ? before : boat.getLocation());
                                    double dx = cur.getX() - lock.getX();
                                    double dy = cur.getY() - lock.getY();
                                    double dz = cur.getZ() - lock.getZ();
                                    float dyaw = absAngleDelta(cur.getYaw(), lock.getYaw());
                                    float dpitch = Math.abs(cur.getPitch() - lock.getPitch());
                                    try {
                                        String bw = (boat.getWorld() == null ? "?" : boat.getWorld().getName());
                                        String lw = (lock.getWorld() == null ? "null" : lock.getWorld().getName());
                                        boolean chunkLoaded = false;
                                        try { chunkLoaded = lock.getWorld() != null && lock.getWorld().isChunkLoaded(lock.getBlockX() >> 4, lock.getBlockZ() >> 4); } catch (Throwable ignored2) {}
                                        plugin.getLogger().info("[COUNTDOWN] track=" + (trackConfig == null ? "?" : trackConfig.getCurrentName())
                                                + " player=" + p.getName()
                                                + " tp=" + tpOk
                                            + " nms=" + nmsOk
                                                + " boatWorld=" + bw
                                                + " lockWorld=" + lw
                                                + " chunkLoaded=" + chunkLoaded
                                                + " passengers=" + (boat.getPassengers() == null ? 0 : boat.getPassengers().size())
                                                + " dPos=" + String.format(java.util.Locale.ROOT, "(%.4f,%.4f,%.4f)", dx, dy, dz)
                                                + " dYaw=" + String.format(java.util.Locale.ROOT, "%.2f", dyaw)
                                                + " dPitch=" + String.format(java.util.Locale.ROOT, "%.2f", dpitch)
                                        );
                                    } catch (Throwable ignored) {}
                                }
                            }
                        } else {
                            // Fallback: keep the player on the lock spot.
                            p.teleport(lock);
                            try { p.setRotation(lock.getYaw(), lock.getPitch()); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public synchronized void cancel() throws IllegalStateException {
                super.cancel();
                if (countdownFreezeTask == this) countdownFreezeTask = null;
            }
        };
        countdownFreezeTask.runTaskTimer(plugin, 0L, 1L);

        countdownTask.runTaskTimer(plugin, 0L, 20L);
    }

    private void setStartLightsProgress(double progress01) {
        if (plugin == null) return;
        java.util.List<Block> lights = trackConfig.getLights();
        if (lights == null || lights.isEmpty()) return;
        int n = lights.size();
        double p = Math.max(0.0, Math.min(1.0, progress01));
        int litCount = (int) Math.floor(p * (double) n + 1e-9);
        if (litCount < 0) litCount = 0;
        if (litCount > n) litCount = n;

        for (int i = 0; i < n; i++) {
            Block b = lights.get(i);
            if (b == null) continue;
            boolean lit = i < litCount;
            setLampLit(b, lit);
        }
    }

    private static void setLampLit(Block b, boolean lit) {
        try {
            if (b == null) return;
            if (b.getType() != Material.REDSTONE_LAMP) return;
            BlockData bd = b.getBlockData();
            if (!(bd instanceof Lightable l)) return;
            if (l.isLit() == lit) return;
            l.setLit(lit);
            b.setBlockData(l, false);
        } catch (Throwable ignored) {}
    }

    public boolean cancelRegistration(boolean announce) {
        boolean had = registering || !registered.isEmpty();
        registering = false;
        registered.clear();
        waitingEndMillis = 0L;
        if (registrationStartTask != null) {
            try { registrationStartTask.cancel(); } catch (Throwable ignored) {}
            registrationStartTask = null;
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
        boolean hadAny = wasRunning || wasRegistering || wasCountdown || !registered.isEmpty() || !participants.isEmpty() || !countdownPlayers.isEmpty();

        // If we were freezing boats during countdown, restore physics regardless of how we stop.
        try { restoreCountdownBoatPhysics(); } catch (Throwable ignored) {}

        // Snapshot players to clean up before wiping state.
        java.util.Set<UUID> toCleanup = new java.util.HashSet<>();
        toCleanup.addAll(registered);
        toCleanup.addAll(participants.keySet());
        toCleanup.addAll(countdownPlayers);

        // Stop scheduled tasks first.
        if (registrationStartTask != null) {
            try { registrationStartTask.cancel(); } catch (Throwable ignored) {}
            registrationStartTask = null;
        }
        if (countdownTask != null) {
            try { countdownTask.cancel(); } catch (Throwable ignored) {}
            countdownTask = null;
        }
        if (countdownFreezeTask != null) {
            try { countdownFreezeTask.cancel(); } catch (Throwable ignored) {}
            countdownFreezeTask = null;
        }

        // Turn off start lights when stopping.
        try { setStartLightsProgress(0.0); } catch (Throwable ignored) {}
        stopRaceTicker();

        // Reset state flags.
        running = false;
        registering = false;
        countdownEndMillis = 0L;
        waitingEndMillis = 0L;
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
        return hadAny || wasRunning || wasRegistering;
    }

    /**
     * Called when a player disconnects (quit/kick). If they were participating in any phase
     * (registration/countdown/race), they are removed and their plugin-spawned boat is deleted.
     *
     * This intentionally does NOT stop the race for remaining racers.
     */
    public boolean handleRacerDisconnect(UUID id) {
        if (id == null) return false;

        boolean changed = false;

        // Remove from registration.
        if (registered.remove(id)) changed = true;

        // Remove from countdown/freeze state.
        if (countdownPlayers.remove(id)) changed = true;
        if (countdownLockLocation.remove(id) != null) changed = true;

        // Remove from live race state.
        if (participants.remove(id) != null) changed = true;
        if (participantPlayers.remove(id) != null) changed = true;

        // Remove their spawned boat entity even if they're offline.
        UUID boatId = spawnedBoatByPlayer.remove(id);
        if (boatId != null) {
            changed = true;
            try {
                if (plugin != null) {
                    Entity ent = plugin.getServer().getEntity(boatId);
                    if (ent != null && isSpawnedBoat(ent)) {
                        try { ent.eject(); } catch (Throwable ignored) {}
                        try { ent.remove(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        // If countdown is active but nobody is left, cancel countdown state.
        if (countdownTask != null && countdownPlayers.isEmpty()) {
            try { countdownTask.cancel(); } catch (Throwable ignored) {}
            countdownTask = null;
            if (countdownFreezeTask != null) {
                try { countdownFreezeTask.cancel(); } catch (Throwable ignored) {}
                countdownFreezeTask = null;
            }
            countdownEndMillis = 0L;
            try { setStartLightsProgress(0.0); } catch (Throwable ignored) {}
            changed = true;
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
            try { setStartLightsProgress(0.0); } catch (Throwable ignored) {}
            changed = true;
        }

        return changed;
    }

    private NamespacedKey spawnedBoatKey() {
        try {
            if (plugin == null) return null;
            return new NamespacedKey(plugin, "boatracing_spawned_boat");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void markSpawnedBoat(Entity e) {
        if (e == null) return;
        NamespacedKey key = spawnedBoatKey();
        if (key == null) return;
        try { e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1); } catch (Throwable ignored) {}
    }

    private boolean isSpawnedBoat(Entity e) {
        if (e == null) return false;
        if (!(e instanceof Boat) && !(e instanceof ChestBoat)) return false;
        NamespacedKey key = spawnedBoatKey();
        if (key == null) return false;
        try { return e.getPersistentDataContainer().has(key, PersistentDataType.BYTE); }
        catch (Throwable ignored) { return false; }
    }

    private void cleanupPlayers(java.util.Set<UUID> ids, boolean teleportToSpawn) {
        if (plugin == null || ids == null || ids.isEmpty()) return;
        for (UUID id : ids) {
            if (id == null) continue;
            Player p = null;
            try { p = plugin.getServer().getPlayer(id); } catch (Throwable ignored) {}
            if (p == null || !p.isOnline()) continue;

            // Remove their spawned boat if we have a handle.
            UUID boatId = spawnedBoatByPlayer.get(id);
            if (boatId != null) {
                try {
                    Entity ent = plugin.getServer().getEntity(boatId);
                    if (ent != null && isSpawnedBoat(ent)) {
                        try { ent.eject(); } catch (Throwable ignored) {}
                        try { ent.remove(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }

            // If player is still in a spawned boat, remove it too.
            try {
                Entity veh = p.getVehicle();
                if (veh != null) {
                    if (isSpawnedBoat(veh)) {
                        try { veh.eject(); } catch (Throwable ignored) {}
                        try { veh.remove(); } catch (Throwable ignored) {}
                    } else {
                        try { p.leaveVehicle(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            if (teleportToSpawn) {
                try {
                    org.bukkit.Location spawn = p.getWorld() != null ? p.getWorld().getSpawnLocation() : null;
                    if (spawn != null) p.teleport(spawn);
                    p.setFallDistance(0f);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void startRaceTicker() {
        stopRaceTicker();
        raceTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) { cancel(); return; }
                boolean anyActive = false;
                for (var e : participantPlayers.entrySet()) {
                    Player p = e.getValue();
                    if (p == null || !p.isOnline()) continue;
                    ParticipantState st = participants.get(e.getKey());
                    if (st == null || st.finished) continue;
                    anyActive = true;
                    try {
                        org.bukkit.entity.Entity veh = p.getVehicle();
                        org.bukkit.Location loc = (veh != null ? veh.getLocation() : p.getLocation());
                        tickPlayer(p, null, loc);
                    } catch (Throwable ignored) {}
                }
                if (!anyActive) {
                    cancel();
                }
            }

            @Override
            public synchronized void cancel() throws IllegalStateException {
                super.cancel();
                if (raceTickTask == this) raceTickTask = null;
            }
        };
        raceTickTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void stopRaceTicker() {
        BukkitRunnable t = raceTickTask;
        raceTickTask = null;
        if (t != null) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
    }

    public java.util.Set<UUID> getParticipants() { return java.util.Collections.unmodifiableSet(registered); }

    public void setTotalLaps(int laps) { this.totalLaps = Math.max(1, laps); }

    // Pit mechanic removed: no mandatory pitstops API

    // ===================== Live position calculation =====================
    private void initPathForLivePositions() {
        java.util.List<org.bukkit.Location> cl = trackConfig.getCenterline();
        if (cl == null || cl.isEmpty()) { pathReady = false; path = java.util.Collections.emptyList(); gateIndex = new int[0]; return; }
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
        if (gateIndex.length > 0) gateIndex[gateIndex.length - 1] = seed;
        pathReady = true;
    }

    private static org.bukkit.Location centerOf(Region r) {
        org.bukkit.util.BoundingBox b = r.getBox();
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(r.getWorldName());
        return new org.bukkit.Location(w, b.getCenterX(), b.getCenterY(), b.getCenterZ());
    }

    private int nearestPathIndex(org.bukkit.Location pos, int seed, int window) {
        if (path == null || path.isEmpty() || pos == null || pos.getWorld() == null) return 0;
        int n = path.size();
        int bestIdx = Math.max(0, Math.min(seed, n - 1));
        double best = Double.POSITIVE_INFINITY;
        int from = Math.max(0, bestIdx - window);
        int to = Math.min(n - 1, bestIdx + window);
        org.bukkit.World w = pos.getWorld();
        for (int i = from; i <= to; i++) {
            org.bukkit.Location node = path.get(i);
            if (node.getWorld() == null || !node.getWorld().equals(w)) continue;
            double d = node.distanceSquared(pos);
            if (d < best) { best = d; bestIdx = i; }
        }

        // If we failed to find a close match in the local window (e.g., after lap wrap), do a full scan.
        // Threshold: 64 blocks squared.
        if (best > (64.0 * 64.0)) {
            for (int i = 0; i < n; i++) {
                org.bukkit.Location node = path.get(i);
                if (node.getWorld() == null || !node.getWorld().equals(w)) continue;
                double d = node.distanceSquared(pos);
                if (d < best) { best = d; bestIdx = i; }
            }
        }
        return bestIdx;
    }

    private double normalizedIndexClamped(ParticipantState s) {
        if (!pathReady || path.isEmpty()) return 0.0;
        int idx = Math.max(0, Math.min(s.lastPathIndex, path.size() - 1));
        // Clamp upper bound to next gate to avoid showing progress beyond next checkpoint.
        // IMPORTANT: Handle wrap-around (e.g. finish near start) where nextGate index may be < prevGate.
        int nextGate = (gateIndex == null || gateIndex.length == 0) ? (path.size() - 1)
                : (s.nextCheckpointIndex < gateIndex.length ? gateIndex[s.nextCheckpointIndex] : path.size() - 1);
        int prevGate = 0;
        if (gateIndex != null && gateIndex.length > 0 && s.nextCheckpointIndex > 0) {
            int pi = Math.min(s.nextCheckpointIndex - 1, gateIndex.length - 1);
            prevGate = gateIndex[pi];
        }

        if (prevGate <= nextGate) {
            if (idx > nextGate) idx = nextGate;
        } else {
            // Segment wraps around end->start. Valid indices are [prevGate..end] U [0..nextGate].
            // If we are in the "gap" (nextGate..prevGate), clamp to nearest boundary.
            if (idx > nextGate && idx < prevGate) {
                int dToNext = idx - nextGate;
                int dToPrev = prevGate - idx;
                idx = (dToNext <= dToPrev) ? nextGate : prevGate;
            }
        }
        return (path.size() <= 1) ? 0.0 : ((double) idx) / (double) (path.size() - 1);
    }

    private double liveProgressValue(UUID id) {
        ParticipantState s = participants.get(id);
        if (s == null) return 0.0;
        if (s.finished) return getTotalLaps();
        double intra = normalizedIndexClamped(s);
        return (double) s.currentLap + intra;
    }

    public double getLapProgressRatio(UUID id) {
        ParticipantState s = participants.get(id);
        if (s == null) return 0.0;
        return normalizedIndexClamped(s);
    }

    public long getRaceElapsedMillis() {
        if (!running && raceStartMillis == 0L) return 0L;
        long now = System.currentTimeMillis();
        return Math.max(0L, now - raceStartMillis);
    }

    public long getRaceStartMillis() { return raceStartMillis; }

    /**
     * Remaining seconds for the active start countdown, or 0 if none.
     */
    public int getCountdownRemainingSeconds() {
        long now = System.currentTimeMillis();
        long end = 0L;
        if (registering && waitingEndMillis > now) end = waitingEndMillis;
        else if (countdownEndMillis > now) end = countdownEndMillis;
        if (end <= now) return 0;
        return (int) ((end - now + 999L) / 1000L);
    }

    public java.util.List<UUID> getLiveOrder() {
        java.util.List<UUID> ids = new java.util.ArrayList<>(participants.keySet());
        // finished racers first by finishTime, then unfinished by live progress desc
        ids.sort((a,b) -> {
            ParticipantState sa = participants.get(a);
            ParticipantState sb = participants.get(b);
            if (sa == null && sb == null) return a.compareTo(b);
            if (sa == null) return 1;
            if (sb == null) return -1;
            boolean fa = sa != null && sa.finished;
            boolean fb = sb != null && sb.finished;
            if (fa && fb) {
                long ta = sa.finishTimeMillis;
                long tb = sb.finishTimeMillis;
                return Long.compare(ta, tb);
            }
            if (fa) return -1;
            if (fb) return 1;
            // both unfinished: compare lap first, then path progress
            int lapCmp = Integer.compare(sb.currentLap, sa.currentLap);
            if (lapCmp != 0) return lapCmp;
            double pa = liveProgressValue(a) - sa.currentLap;
            double pb = liveProgressValue(b) - sb.currentLap;
            int cmp = Double.compare(pb, pa);
            if (cmp != 0) return cmp;
            // tie-breaker: next checkpoint index (further along)
            int cpCmp = Integer.compare(sb.nextCheckpointIndex, sa.nextCheckpointIndex);
            if (cpCmp != 0) return cpCmp;
            // final tie-breaker: UUID (stable)
            return a.compareTo(b);
        });
        return ids;
    }
}

