package dev.belikhun.boatracing.cinematic;

import dev.belikhun.boatracing.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A small service that drives a cinematic camera for players by teleporting them each tick.
 *
 * Implementation notes:
 * - Uses SPECTATOR mode during playback.
 * - Avoids entity-based camera to stay Paper-version tolerant.
 */
public class CinematicCameraService {
	private final Plugin plugin;

	private static final class Running {
		final String id;
		final Set<UUID> players;
		final Set<UUID> allPlayers;
		final Map<UUID, GameMode> previousGameModes = new HashMap<>();
		final CinematicSequence sequence;
		final Runnable onComplete;
		BukkitTask tickTask;
		int tick;
		// Performance: cache player lookups and last teleport location
		final Map<UUID, Player> playerCache = new HashMap<>();
		Location lastCameraLocation = null;

		Running(String id, Set<UUID> players, CinematicSequence sequence, Runnable onComplete) {
			this.id = id;
			this.players = players;
			this.allPlayers = new HashSet<>(players);
			this.sequence = sequence;
			this.onComplete = onComplete;
			this.tick = 0;
		}
	}

	private final Map<String, Running> runningById = new HashMap<>();
	private final Map<UUID, String> idByPlayer = new HashMap<>();
	private final Map<UUID, GameMode> pendingRestoreGameModes = new HashMap<>();
	// viewerId -> set of targetIds that must be shown again once both are online.
	private final Map<UUID, Set<UUID>> pendingRestoreVisibility = new HashMap<>();

	public CinematicCameraService(Plugin plugin) {
		this.plugin = plugin;
	}

	public synchronized boolean isRunningFor(UUID playerId) {
		if (playerId == null)
			return false;
		return idByPlayer.containsKey(playerId);
	}

	public synchronized boolean start(String id, Collection<Player> players, CinematicSequence sequence, Runnable onComplete) {
		if (plugin == null)
			return false;
		if (id == null || id.isBlank())
			return false;
		if (sequence == null || sequence.keyframes == null || sequence.keyframes.size() < 2)
			return false;

		List<Player> online = new ArrayList<>();
		if (players != null) {
			for (Player p : players) {
				if (p == null || !p.isOnline())
					continue;
				online.add(p);
			}
		}
		if (online.isEmpty())
			return false;

		// Detach these players from any previous sequences.
		for (Player p : online) {
			stopForPlayer(p.getUniqueId(), true);
		}

		Set<UUID> ids = new HashSet<>();
		for (Player p : online) {
			ids.add(p.getUniqueId());
		}

		Running r = new Running(id, ids, sequence, onComplete);

		// Save previous modes and switch to spectator.
		for (Player p : online) {
			try {
				r.previousGameModes.put(p.getUniqueId(), p.getGameMode());
			} catch (Throwable ignored) {
			}
			try {
				if (p.isInsideVehicle())
					p.leaveVehicle();
			} catch (Throwable ignored) {
			}
			try {
				p.setGameMode(GameMode.SPECTATOR);
			} catch (Throwable ignored) {
			}
		}

		// Prevent spectator players from seeing each other's heads during cinematic.
		applyMutualHide(online);

		runningById.put(id, r);
		for (UUID pid : ids) {
			idByPlayer.put(pid, id);
		}

		// Start at first keyframe.
		tryTeleportAllToFrame(r, sequence.keyframes.get(0));

		r.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			tick(r.id);
		}, 0L, 1L);

		return true;
	}

	private void applyMutualHide(List<Player> players) {
		if (players == null || players.size() < 2)
			return;
		for (Player viewer : players) {
			if (viewer == null || !viewer.isOnline())
				continue;
			for (Player target : players) {
				if (target == null || !target.isOnline())
					continue;
				if (viewer.getUniqueId().equals(target.getUniqueId()))
					continue;
				try {
					viewer.hidePlayer(plugin, target);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private void restoreVisibilityBetween(Set<UUID> viewers, Set<UUID> targets) {
		if (viewers == null || targets == null || viewers.isEmpty() || targets.isEmpty())
			return;
		for (UUID viewerId : new HashSet<>(viewers)) {
			Player viewer = Bukkit.getPlayer(viewerId);
			if (viewer == null || !viewer.isOnline())
				continue;
			for (UUID targetId : new HashSet<>(targets)) {
				if (targetId == null || targetId.equals(viewerId))
					continue;
				Player target = Bukkit.getPlayer(targetId);
				if (target == null || !target.isOnline()) {
					pendingRestoreVisibility.computeIfAbsent(viewerId, k -> new HashSet<>()).add(targetId);
					continue;
				}
				try {
					viewer.showPlayer(plugin, target);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static float wrapAngleDeg(float a) {
		float x = a % 360.0f;
		if (x < 0.0f)
			x += 360.0f;
		return x;
	}

	private static float lerpAngleDeg(float a, float b, double t) {
		float aa = wrapAngleDeg(a);
		float bb = wrapAngleDeg(b);
		float d = (bb - aa) % 360.0f;
		if (d > 180.0f)
			d -= 360.0f;
		if (d < -180.0f)
			d += 360.0f;
		return (float) (aa + d * t);
	}

	private static double easeInOut(double t) {
		double x = Math.max(0.0, Math.min(1.0, t));
		// smoothstep
		return x * x * (3.0 - 2.0 * x);
	}

	private synchronized void tick(String id) {
		Running r = runningById.get(id);
		if (r == null)
			return;

		// Refresh player cache and prune offline (avoid repeated Bukkit.getPlayer calls)
		r.playerCache.clear();
		r.players.removeIf(pid -> {
			Player p = Bukkit.getPlayer(pid);
			if (p == null || !p.isOnline()) {
				removePlayerFromRunning(r, pid, true);
				return true;
			}
			r.playerCache.put(pid, p);
			return false;
		});

		if (r.players.isEmpty()) {
			stopSequenceInternal(id, true, false);
			return;
		}

		int total = r.sequence.totalTicks();
		int now = r.tick;

		// Sound events (use cached players)
		try {
			for (CinematicSoundEvent se : r.sequence.soundEvents) {
				if (se == null)
					continue;
				if (se.atTick != now)
					continue;
				playSoundToCached(r.playerCache.values(), se.sound, se.volume, se.pitch);
			}
		} catch (Throwable ignored) {
		}

		// Position interpolation (only teleport if camera moved)
		Location cam = sample(r.sequence, now);
		if (cam != null && !isSameLocation(r.lastCameraLocation, cam)) {
			teleportAllCached(r.playerCache.values(), cam);
			r.lastCameraLocation = cam.clone();
		}

		r.tick++;

		if (r.tick > total) {
			stopSequenceInternal(id, true, true);
			try {
				if (r.onComplete != null)
					r.onComplete.run();
			} catch (Throwable ignored) {
			}
		}
	}

	private static Location sample(CinematicSequence seq, int tick) {
		if (seq == null || seq.keyframes == null || seq.keyframes.size() < 2)
			return null;
		int t = Math.max(0, tick);

		int idx = 0;
		int local = t;
		for (; idx < seq.keyframes.size() - 1; idx++) {
			CinematicKeyframe k = seq.keyframes.get(idx);
			int dur = (k == null ? 0 : Math.max(0, k.durationTicks));
			if (idx == seq.keyframes.size() - 2)
				break;
			if (local <= dur)
				break;
			local -= dur;
		}

		CinematicKeyframe a = seq.keyframes.get(Math.max(0, Math.min(idx, seq.keyframes.size() - 2)));
		CinematicKeyframe b = seq.keyframes.get(Math.max(1, Math.min(idx + 1, seq.keyframes.size() - 1)));
		if (a == null || b == null || a.location == null || b.location == null)
			return null;

		Location la = a.location;
		Location lb = b.location;
		if (la.getWorld() == null || lb.getWorld() == null || !la.getWorld().equals(lb.getWorld())) {
			return la.clone();
		}

		// durationTicks == 0 is a hard cut: teleport immediately to the next keyframe.
		if (a.durationTicks <= 0) {
			return lb.clone();
		}

		int dur = Math.max(1, a.durationTicks);
		double raw = Math.max(0.0, Math.min(1.0, (double) local / (double) dur));
		double u = (seq.linearEasing ? raw : easeInOut(raw));

		double x = la.getX() + (lb.getX() - la.getX()) * u;
		double y = la.getY() + (lb.getY() - la.getY()) * u;
		double z = la.getZ() + (lb.getZ() - la.getZ()) * u;

		float yaw = lerpAngleDeg(la.getYaw(), lb.getYaw(), u);
		float pitch = (float) (la.getPitch() + (lb.getPitch() - la.getPitch()) * u);
		pitch = Math.max(-89.9f, Math.min(89.9f, pitch));

		Location out = new Location(la.getWorld(), x, y, z, yaw, pitch);
		return out;
	}

	private void playSoundTo(Set<UUID> players, Sound sound, float volume, float pitch) {
		if (sound == null)
			return;
		for (UUID id : new HashSet<>(players)) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				// Play in the RECORDS channel so multiple note sounds don't phase with nearby sources.
				p.playSound(p, sound, SoundCategory.RECORDS, volume, pitch);
			} catch (Throwable t) {
				// Fallback
				try {
					p.playSound(p.getLocation(), sound, SoundCategory.RECORDS, volume, pitch);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	// Optimized version: use cached players (no UUID lookup)
	private void playSoundToCached(Collection<Player> players, Sound sound, float volume, float pitch) {
		if (sound == null || players == null)
			return;
		for (Player p : players) {
			if (p == null || !p.isOnline())
				continue;
			try {
				p.playSound(p, sound, SoundCategory.RECORDS, volume, pitch);
			} catch (Throwable t) {
				try {
					p.playSound(p.getLocation(), sound, SoundCategory.RECORDS, volume, pitch);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private void teleportAll(Set<UUID> players, Location loc) {
		if (loc == null || loc.getWorld() == null)
			return;
		for (UUID id : new HashSet<>(players)) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				p.teleport(loc);
			} catch (Throwable ignored) {
			}
		}
	}

	// Optimized version: use cached players (no UUID lookup)
	private void teleportAllCached(Collection<Player> players, Location loc) {
		if (loc == null || loc.getWorld() == null || players == null)
			return;
		for (Player p : players) {
			if (p == null || !p.isOnline())
				continue;
			try {
				p.teleport(loc);
			} catch (Throwable ignored) {
			}
		}
	}

	// Fast location equality check (within 0.001 threshold to account for FP errors)
	private static boolean isSameLocation(Location a, Location b) {
		if (a == null || b == null)
			return false;
		if (a.getWorld() == null || b.getWorld() == null)
			return false;
		if (!a.getWorld().equals(b.getWorld()))
			return false;
		double dx = Math.abs(a.getX() - b.getX());
		double dy = Math.abs(a.getY() - b.getY());
		double dz = Math.abs(a.getZ() - b.getZ());
		float dyaw = Math.abs(a.getYaw() - b.getYaw());
		float dpitch = Math.abs(a.getPitch() - b.getPitch());
		return dx < 0.001 && dy < 0.001 && dz < 0.001 && dyaw < 0.1f && dpitch < 0.1f;
	}

	private void tryTeleportAllToFrame(Running r, CinematicKeyframe k) {
		if (r == null || k == null || k.location == null)
			return;
		teleportAll(r.players, k.location);
	}

	public synchronized void stopForPlayer(UUID playerId, boolean restoreMode) {
		if (playerId == null)
			return;
		String id = idByPlayer.get(playerId);
		if (id == null)
			return;
		Running r = runningById.get(id);
		if (r == null)
			return;
		removePlayerFromRunning(r, playerId, restoreMode);
		if (r.players.isEmpty()) {
			stopSequenceInternal(id, false, false);
		}
	}

	public synchronized void stopForPlayers(Collection<UUID> playerIds, boolean restoreMode) {
		if (playerIds == null || playerIds.isEmpty())
			return;
		for (UUID pid : new HashSet<>(playerIds)) {
			stopForPlayer(pid, restoreMode);
		}
	}

	/**
	 * Stop all currently running cinematics.
	 *
	 * Intended for plugin shutdown to avoid leaving players in spectator mode.
	 */
	public synchronized void stopAll(boolean restoreMode) {
		for (String id : new HashSet<>(runningById.keySet())) {
			stopSequenceInternal(id, restoreMode, false);
		}
	}

	private void removePlayerFromRunning(Running r, UUID playerId, boolean restoreMode) {
		if (r == null || playerId == null)
			return;
		if (!r.players.remove(playerId))
			return;
		idByPlayer.remove(playerId);

		// When a player leaves the cinematic early, restore their own visibility of the
		// remaining cinematic players. Keep them hidden from the remaining spectators
		// until the cinematic ends, to avoid visible heads at the shared camera.
		restoreVisibilityBetween(Set.of(playerId), r.players);

		if (restoreMode) {
			GameMode gm = r.previousGameModes.remove(playerId);
			Player p = Bukkit.getPlayer(playerId);
			if (p != null && p.isOnline()) {
				try {
					if (gm != null)
						p.setGameMode(gm);
				} catch (Throwable ignored) {
				}
			} else {
				// If the player disconnected while in spectator, their gamemode persists.
				// Restore it safely on next join.
				if (gm != null)
					pendingRestoreGameModes.put(playerId, gm);
			}
		}
	}

	/**
	 * Restore a player's previous gamemode if they disconnected mid-cinematic.
	 */
	public synchronized void restorePendingGameMode(Player player) {
		if (player == null)
			return;
		UUID id = player.getUniqueId();
		if (id == null)
			return;
		GameMode gm = pendingRestoreGameModes.remove(id);
		if (gm == null)
			return;
		try {
			player.setGameMode(gm);
		} catch (Throwable ignored) {
			// If we fail, keep the entry so we can try again next join.
			pendingRestoreGameModes.put(id, gm);
		}
	}

	/**
	 * Restore pending visibility pairs that could not be restored because one side
	 * was offline when the cinematic stopped.
	 */
	public synchronized void restorePendingVisibility(Player player) {
		if (player == null)
			return;
		UUID pid = player.getUniqueId();
		if (pid == null)
			return;

		// If this player is a viewer with pending targets, try to show them now.
		Set<UUID> targets = pendingRestoreVisibility.get(pid);
		if (targets != null && !targets.isEmpty()) {
			for (UUID tid : new HashSet<>(targets)) {
				Player t = Bukkit.getPlayer(tid);
				if (t == null || !t.isOnline())
					continue;
				try {
					player.showPlayer(plugin, t);
					targets.remove(tid);
				} catch (Throwable ignored) {
				}
			}
			if (targets.isEmpty())
				pendingRestoreVisibility.remove(pid);
		}

		// If this player is a target for other viewers, restore those too.
		for (Map.Entry<UUID, Set<UUID>> e : new HashMap<>(pendingRestoreVisibility).entrySet()) {
			UUID viewerId = e.getKey();
			Set<UUID> ts = e.getValue();
			if (viewerId == null || ts == null || ts.isEmpty())
				continue;
			if (!ts.contains(pid))
				continue;
			Player viewer = Bukkit.getPlayer(viewerId);
			if (viewer == null || !viewer.isOnline())
				continue;
			try {
				viewer.showPlayer(plugin, player);
				ts.remove(pid);
			} catch (Throwable ignored) {
			}
			if (ts.isEmpty())
				pendingRestoreVisibility.remove(viewerId);
		}
	}

	private synchronized void stopSequenceInternal(String id, boolean restoreMode, boolean finished) {
		Running r = runningById.remove(id);
		if (r == null)
			return;

		// Always attempt to restore visibility between all cinematic participants.
		// Some targets may be offline; those are restored on next join.
		restoreVisibilityBetween(r.allPlayers, r.allPlayers);

		// Optional: tiny UX cue when a cinematic ends naturally.
		if (finished) {
			for (UUID pid : new HashSet<>(r.players)) {
				Player p = Bukkit.getPlayer(pid);
				if (p == null || !p.isOnline())
					continue;
				try {
					Text.msg(p, "&7Chuẩn bị sẵn sàng!");
				} catch (Throwable ignored) {
				}
			}
		}

		if (r.tickTask != null) {
			try {
				r.tickTask.cancel();
			} catch (Throwable ignored) {
			}
			r.tickTask = null;
		}

		for (UUID pid : new HashSet<>(r.players)) {
			removePlayerFromRunning(r, pid, restoreMode);
		}
	}

	public static float[] lookAt(Location from, Location to) {
		if (from == null || to == null)
			return new float[] { 0.0f, 0.0f };
		double dx = to.getX() - from.getX();
		double dy = to.getY() - from.getY();
		double dz = to.getZ() - from.getZ();

		double xz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, xz));
		pitch = Math.max(-89.9f, Math.min(89.9f, pitch));
		return new float[] { yaw, pitch };
	}
}

