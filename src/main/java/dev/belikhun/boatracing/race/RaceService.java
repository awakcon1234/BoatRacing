package dev.belikhun.boatracing.race;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.util.Text;

import java.io.File;
import java.util.*;

/**
 * Manages independent races per track name so multiple races can run concurrently.
 */
public class RaceService {
	private final BoatRacingPlugin plugin;
	private final File dataFolder;

	private final Map<String, RaceManager> raceByTrack = new HashMap<>();
	private final Map<UUID, String> trackByPlayer = new HashMap<>();

	private int defaultLaps = 3;

	public RaceService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.dataFolder = plugin.getDataFolder();
	}

	public synchronized void setDefaultLaps(int laps) {
		this.defaultLaps = Math.max(1, laps);
		for (RaceManager rm : raceByTrack.values()) {
			try { rm.setTotalLaps(this.defaultLaps); } catch (Throwable ignored) {}
		}
	}

	public synchronized int getDefaultLaps() {
		return Math.max(1, defaultLaps);
	}

	public synchronized RaceManager getOrCreate(String trackName) {
		if (trackName == null || trackName.isBlank()) return null;
		String key = trackName.trim();
		RaceManager existing = raceByTrack.get(key);
		if (existing != null) return existing;

		TrackConfig tc = new TrackConfig(plugin, dataFolder);
		if (!tc.load(key)) return null;

		RaceManager rm = new RaceManager(plugin, tc);
		rm.setTotalLaps(getDefaultLaps());
		raceByTrack.put(key, rm);
		return rm;
	}

	public synchronized RaceManager get(String trackName) {
		if (trackName == null) return null;
		return raceByTrack.get(trackName);
	}

	public synchronized Collection<RaceManager> allRaces() {
		return java.util.Collections.unmodifiableCollection(raceByTrack.values());
	}

	public synchronized RaceManager findRaceFor(UUID playerId) {
		if (playerId == null) return null;

		String mapped = trackByPlayer.get(playerId);
		if (mapped != null) {
			RaceManager rm = raceByTrack.get(mapped);
			if (rm != null && rm.isInvolved(playerId)) return rm;
			trackByPlayer.remove(playerId);
		}

		for (Map.Entry<String, RaceManager> en : raceByTrack.entrySet()) {
			RaceManager rm = en.getValue();
			if (rm == null) continue;
			if (rm.isInvolved(playerId)) {
				trackByPlayer.put(playerId, en.getKey());
				return rm;
			}
		}
		return null;
	}

	public synchronized String findTrackNameFor(UUID playerId) {
		if (playerId == null) return null;
		String mapped = trackByPlayer.get(playerId);
		if (mapped != null) {
			RaceManager rm = raceByTrack.get(mapped);
			if (rm != null && rm.isInvolved(playerId)) return mapped;
			trackByPlayer.remove(playerId);
		}
		for (Map.Entry<String, RaceManager> en : raceByTrack.entrySet()) {
			RaceManager rm = en.getValue();
			if (rm == null) continue;
			if (rm.isInvolved(playerId)) {
				trackByPlayer.put(playerId, en.getKey());
				return en.getKey();
			}
		}
		return null;
	}

	public synchronized boolean openRegistration(String trackName, int laps) {
		RaceManager rm = getOrCreate(trackName);
		if (rm == null) return false;
		rm.setTotalLaps(Math.max(1, laps));
		return rm.openRegistration(laps, null);
	}

	/**
	 * Join a track. If registration isn't open yet, it is opened automatically.
	 */
	public synchronized boolean join(String trackName, org.bukkit.entity.Player p) {
		if (p == null) return false;
		// If an event is running, lock event tracks from manual joining.
		try {
			var es = plugin != null ? plugin.getEventService() : null;
			if (es != null && es.isTrackLocked(trackName)) {
				Text.msg(p, "&c❌ Đường đua này đang được khóa cho sự kiện.");
				p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
				return false;
			}
		} catch (Throwable ignored) {
		}
		RaceManager rm = getOrCreate(trackName);
		if (rm == null) return false;

		if (!rm.isRegistering() && !rm.isRunning() && !rm.isAnyCountdownActive()) {
			rm.openRegistration(rm.getTotalLaps(), null);
		}

		boolean ok = rm.join(p);
		if (ok) trackByPlayer.put(p.getUniqueId(), trackName);
		return ok;
	}

	public synchronized boolean leave(String trackName, org.bukkit.entity.Player p) {
		if (p == null) return false;
		RaceManager rm = getOrCreate(trackName);
		if (rm == null) return false;
		boolean ok = rm.leave(p);
		if (ok) trackByPlayer.remove(p.getUniqueId());
		return ok;
	}

	public synchronized boolean handleDisconnect(UUID playerId) {
		RaceManager rm = findRaceFor(playerId);
		if (rm == null) return false;
		boolean changed = rm.handleRacerDisconnect(playerId);
		if (changed) trackByPlayer.remove(playerId);
		return changed;
	}

	/**
	 * Remove the player from any race/registration/countdown state and teleport them back to their world spawn.
	 * This is used by the UX hotbar "leave" actions.
	 */
	public synchronized boolean leaveToLobby(org.bukkit.entity.Player p) {
		if (p == null) return false;
		java.util.UUID id = p.getUniqueId();

		RaceManager rm = findRaceFor(id);
		if (rm == null) return false;

		boolean changed = false;
		try { changed = rm.handleRacerDisconnect(id); } catch (Throwable ignored) { changed = false; }
		trackByPlayer.remove(id);

		try {
			if (p.isInsideVehicle()) p.leaveVehicle();
		} catch (Throwable ignored) {}

		try {
			org.bukkit.Location spawn = (p.getWorld() != null ? p.getWorld().getSpawnLocation() : null);
			if (spawn != null) p.teleport(spawn);
			p.setFallDistance(0f);
		} catch (Throwable ignored) {}

		// Player-facing confirmation (Vietnamese UX rule)
		try {
			Text.msg(p, "&a⎋ Đã rời khỏi cuộc đua.");
		} catch (Throwable ignored) {}

		return changed;
	}

	public synchronized boolean stopRace(String trackName, boolean teleportToSpawn) {
		RaceManager rm = getOrCreate(trackName);
		if (rm == null) return false;

		java.util.Set<UUID> involved = rm.getInvolved();
		boolean any = rm.stop(teleportToSpawn);
		for (UUID id : involved) trackByPlayer.remove(id);
		return any;
	}

	/**
	 * Stop all races and clear internal maps. Intended for plugin shutdown/unload.
	 */
	public synchronized void stopAll(boolean teleportToSpawn) {
		java.util.Set<UUID> touched = new java.util.HashSet<>();
		for (RaceManager rm : raceByTrack.values()) {
			if (rm == null) continue;
			try { touched.addAll(rm.getInvolved()); } catch (Throwable ignored) {}
			try { rm.stop(teleportToSpawn); } catch (Throwable ignored) {}
		}
		for (UUID id : touched) trackByPlayer.remove(id);
		raceByTrack.clear();
	}
}

