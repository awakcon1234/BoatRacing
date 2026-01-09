package dev.belikhun.boatracing.race;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.util.Text;

import org.bukkit.Bukkit;

import java.io.File;
import java.util.*;

/**
 * Manages independent races per track name so multiple races can run concurrently.
 */
public class RaceService {
	private final BoatRacingPlugin plugin;
	private final File dataFolder;
	private final MatchmakingService matchmaking;

	private final Map<String, RaceManager> raceByTrack = new HashMap<>();
	private final Map<UUID, String> trackByPlayer = new HashMap<>();
	private final Set<UUID> pendingLobbyTeleport = new HashSet<>();

	// --- Spectate (watch a running race without joining) ---
	private static final class SpectateState {
		final String trackName;
		final org.bukkit.Location returnLocation;
		final org.bukkit.GameMode returnGameMode;
		org.bukkit.scheduler.BukkitTask monitorTask;

		SpectateState(String trackName, org.bukkit.Location returnLocation, org.bukkit.GameMode returnGameMode) {
			this.trackName = trackName;
			this.returnLocation = returnLocation;
			this.returnGameMode = returnGameMode;
		}
	}

	private final Map<UUID, SpectateState> spectateByPlayer = new HashMap<>();
	private final Map<UUID, org.bukkit.GameMode> pendingRestoreSpectateModes = new HashMap<>();

	public MatchmakingService getMatchmaking() {
		return matchmaking;
	}

	private void teleportToLobby(org.bukkit.entity.Player p) {
		if (p == null)
			return;
		org.bukkit.Location spawn = null;
		try {
			spawn = (plugin != null ? plugin.resolveLobbySpawn(p)
					: (p.getWorld() != null ? p.getWorld().getSpawnLocation() : null));
		} catch (Throwable ignored) {
			spawn = null;
		}
		if (spawn == null)
			return;

		org.bukkit.Location target;
		try {
			target = spawn.clone();
		} catch (Throwable ignored) {
			target = spawn;
		}

		final float yaw = target.getYaw();
		final float pitch = target.getPitch();

		try {
			p.teleport(target);
		} catch (Throwable ignored) {
		}
		try {
			p.setRotation(yaw, pitch);
		} catch (Throwable ignored) {
		}
		try {
			p.setFallDistance(0f);
		} catch (Throwable ignored) {
		}
		try {
			if (plugin != null)
				plugin.applyLobbyFlight(p);
		} catch (Throwable ignored) {
		}

		// Some client/vehicle flows can override yaw/pitch right after teleport.
		// Re-apply next tick to make the facing stable.
		try {
			if (plugin != null) {
				Bukkit.getScheduler().runTaskLater(plugin, () -> {
					try {
						if (!p.isOnline())
							return;
						p.setRotation(yaw, pitch);
					} catch (Throwable ignored) {
					}
				}, 1L);
			}
		} catch (Throwable ignored) {
		}
	}

	private int defaultLaps = 3;

	public RaceService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.dataFolder = plugin.getDataFolder();
		this.matchmaking = new MatchmakingService(plugin, this);
		try {
			this.matchmaking.start();
		} catch (Throwable ignored) {
		}
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

	public boolean matchmakingJoin(org.bukkit.entity.Player p) {
		if (p == null) return false;
		matchmaking.join(p);
		return true;
	}

	public boolean matchmakingLeave(org.bukkit.entity.Player p) {
		if (p == null) return false;
		matchmaking.leave(p);
		return true;
	}

	public boolean isInMatchmaking(UUID id) {
		return matchmaking.isQueued(id);
	}

	public void matchmakingRemove(UUID id) {
		matchmaking.removeIfQueued(id);
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

	public synchronized boolean isSpectating(UUID playerId) {
		if (playerId == null)
			return false;
		return spectateByPlayer.containsKey(playerId);
	}

	private org.bukkit.Location resolveSpectateLocation(RaceManager rm) {
		if (rm == null)
			return null;
		TrackConfig cfg = null;
		try {
			cfg = rm.getTrackConfig();
		} catch (Throwable ignored) {
			cfg = null;
		}
		if (cfg == null)
			return null;

		org.bukkit.Location base = null;
		try {
			base = cfg.getWaitingSpawn();
		} catch (Throwable ignored) {
			base = null;
		}
		if (base == null) {
			try {
				base = cfg.getStartCenter();
			} catch (Throwable ignored) {
				base = null;
			}
		}
		if (base == null || base.getWorld() == null)
			return null;

		org.bukkit.Location out;
		try {
			out = base.clone();
		} catch (Throwable ignored) {
			out = base;
		}
		// Give a bit of height so the first view isn't inside the course.
		try {
			out.add(0.0, 6.0, 0.0);
			out.setPitch(25.0f);
		} catch (Throwable ignored) {
		}
		return out;
	}

	private void trySetSpectatorTarget(org.bukkit.entity.Player viewer, RaceManager rm) {
		if (viewer == null || rm == null)
			return;
		if (!viewer.isOnline())
			return;
		try {
			if (viewer.getGameMode() != org.bukkit.GameMode.SPECTATOR)
				return;
		} catch (Throwable ignored) {
			return;
		}

		org.bukkit.entity.Player target = null;
		try {
			for (java.util.UUID id : rm.getRegistered()) {
				org.bukkit.entity.Player rp = org.bukkit.Bukkit.getPlayer(id);
				if (rp == null || !rp.isOnline())
					continue;
				target = rp;
				break;
			}
		} catch (Throwable ignored) {
			target = null;
		}
		if (target == null)
			return;
		try {
			viewer.setSpectatorTarget(target);
		} catch (Throwable ignored) {
		}
	}

	public synchronized boolean spectateStart(String trackName, org.bukkit.entity.Player p) {
		if (plugin == null)
			return false;
		if (p == null || !p.isOnline())
			return false;
		if (trackName == null || trackName.isBlank())
			return false;
		try { matchmakingRemove(p.getUniqueId()); } catch (Throwable ignored) {}

		String key = trackName.trim();
		RaceManager rm = getOrCreate(key);
		if (rm == null)
			return false;
		if (!rm.isRunning())
			return false;

		java.util.UUID id = p.getUniqueId();
		// Don't allow spectate while the player is involved in any race state.
		try {
			RaceManager own = findRaceFor(id);
			if (own != null)
				return false;
		} catch (Throwable ignored) {
			return false;
		}

		// Stop any cinematic so we don't fight over spectator mode.
		try {
			if (plugin.getCinematicCameraService() != null)
				plugin.getCinematicCameraService().stopForPlayer(id, true);
		} catch (Throwable ignored) {
		}

		// If already spectating something else, stop it first.
		stopSpectateInternal(id, false);

		org.bukkit.Location returnLoc;
		try {
			returnLoc = p.getLocation();
		} catch (Throwable ignored) {
			returnLoc = null;
		}
		org.bukkit.GameMode prevMode;
		try {
			prevMode = p.getGameMode();
		} catch (Throwable ignored) {
			prevMode = null;
		}

		SpectateState st = new SpectateState(key, returnLoc, prevMode);
		spectateByPlayer.put(id, st);

		org.bukkit.Location viewLoc = resolveSpectateLocation(rm);
		if (viewLoc == null) {
			stopSpectateInternal(id, false);
			return false;
		}

		try {
			if (p.isInsideVehicle())
				p.leaveVehicle();
		} catch (Throwable ignored) {
		}
		try {
			p.setGameMode(org.bukkit.GameMode.SPECTATOR);
		} catch (Throwable ignored) {
		}
		try {
			p.teleport(viewLoc);
		} catch (Throwable ignored) {
		}
		try {
			p.setRotation(viewLoc.getYaw(), viewLoc.getPitch());
		} catch (Throwable ignored) {
		}

		trySetSpectatorTarget(p, rm);

		// Auto-exit spectate when the race ends.
		try {
			st.monitorTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
				try {
					if (!p.isOnline())
						return;
					RaceManager cur = get(key);
					if (cur == null || !cur.isRunning()) {
						spectateStop(p, true);
					}
				} catch (Throwable ignored) {
				}
			}, 20L, 20L);
		} catch (Throwable ignored) {
		}

		try {
			Text.msg(p, "&a👁 Bạn đang theo dõi đường đua: &f" + key);
			Text.tell(p, "&7Dùng: &f/spawn &7hoặc &fitem ⎋ Thoát theo dõi &7để về sảnh.");
		} catch (Throwable ignored) {
		}

		return true;
	}

	private void stopSpectateInternal(UUID playerId, boolean teleportLobbyIfMissing) {
		if (playerId == null)
			return;
		SpectateState st = spectateByPlayer.remove(playerId);
		if (st == null)
			return;
		try {
			if (st.monitorTask != null)
				st.monitorTask.cancel();
		} catch (Throwable ignored) {
		}
		st.monitorTask = null;

		org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(playerId);
		if (p == null || !p.isOnline()) {
			// If the player disconnected while in spectator, gamemode persists. Restore it on next join.
			if (st.returnGameMode != null)
				pendingRestoreSpectateModes.put(playerId, st.returnGameMode);
			return;
		}

		try {
			if (st.returnGameMode != null)
				p.setGameMode(st.returnGameMode);
			else
				p.setGameMode(org.bukkit.GameMode.ADVENTURE);
		} catch (Throwable ignored) {
		}

		org.bukkit.Location back = st.returnLocation;
		if (back != null && back.getWorld() != null) {
			try {
				p.teleport(back);
			} catch (Throwable ignored) {
				back = null;
			}
		}
		if (back == null && teleportLobbyIfMissing) {
			try {
				teleportToLobby(p);
			} catch (Throwable ignored) {
			}
		}
	}

	public synchronized boolean spectateStop(org.bukkit.entity.Player p, boolean teleportToLobbyIfNeeded) {
		if (p == null)
			return false;
		java.util.UUID id = p.getUniqueId();
		if (id == null)
			return false;
		if (!spectateByPlayer.containsKey(id))
			return false;
		stopSpectateInternal(id, teleportToLobbyIfNeeded);
		try {
			Text.msg(p, "&a👁 Đã thoát chế độ theo dõi.");
		} catch (Throwable ignored) {
		}
		return true;
	}

	/**
	 * Leave spectate mode and always teleport the player to the lobby.
	 *
	 * This is intended for quick UX exits like /spawn or a hotbar button.
	 */
	public synchronized boolean spectateLeaveToLobby(org.bukkit.entity.Player p) {
		if (p == null)
			return false;
		java.util.UUID id = p.getUniqueId();
		if (id == null)
			return false;

		SpectateState st = spectateByPlayer.remove(id);
		if (st == null)
			return false;

		try {
			if (st.monitorTask != null)
				st.monitorTask.cancel();
		} catch (Throwable ignored) {
		}
		st.monitorTask = null;

		if (!p.isOnline()) {
			// Restore their gamemode and teleport them to lobby on next join.
			try {
				org.bukkit.GameMode gm = st.returnGameMode != null ? st.returnGameMode : org.bukkit.GameMode.ADVENTURE;
				pendingRestoreSpectateModes.put(id, gm);
			} catch (Throwable ignored) {
			}
			try {
				pendingLobbyTeleport.add(id);
			} catch (Throwable ignored) {
			}
			return true;
		}

		try {
			if (st.returnGameMode != null)
				p.setGameMode(st.returnGameMode);
			else
				p.setGameMode(org.bukkit.GameMode.ADVENTURE);
		} catch (Throwable ignored) {
		}

		try {
			if (p.isInsideVehicle())
				p.leaveVehicle();
		} catch (Throwable ignored) {
		}

		try {
			teleportToLobby(p);
		} catch (Throwable ignored) {
		}

		try {
			Text.msg(p, "&a⎋ Đã thoát chế độ theo dõi và về sảnh.");
		} catch (Throwable ignored) {
		}

		return true;
	}

	public synchronized void restorePendingSpectateMode(org.bukkit.entity.Player p) {
		if (p == null)
			return;
		java.util.UUID id = p.getUniqueId();
		if (id == null)
			return;
		org.bukkit.GameMode gm = pendingRestoreSpectateModes.remove(id);
		if (gm == null)
			return;
		try {
			p.setGameMode(gm);
		} catch (Throwable ignored) {
			pendingRestoreSpectateModes.put(id, gm);
			return;
		}
		// Prefer returning them to lobby to avoid leaving them stuck at the track.
		try {
			teleportToLobby(p);
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Reload a track's runtime RaceManager if it is idle.
	 *
	 * This is used after saving track edits so TrackSelect/join flows don't keep
	 * using an old cached TrackConfig instance.
	 *
	 * @return true if the race manager was reloaded, false otherwise.
	 */
	public synchronized boolean reloadIfIdle(String trackName) {
		if (trackName == null || trackName.isBlank())
			return false;
		String key = trackName.trim();
		RaceManager existing = raceByTrack.get(key);
		if (existing == null)
			return false;

		try {
			if (existing.isRunning() || existing.isAnyCountdownActive() || existing.isRegistering())
				return false;
			java.util.Set<java.util.UUID> involved = existing.getInvolved();
			if (involved != null && !involved.isEmpty())
				return false;
		} catch (Throwable ignored) {
			// If we can't safely determine state, do not reload.
			return false;
		}

		raceByTrack.remove(key);
		TrackConfig tc = new TrackConfig(plugin, dataFolder);
		if (!tc.load(key)) {
			return false;
		}
		RaceManager rm = new RaceManager(plugin, tc);
		rm.setTotalLaps(getDefaultLaps());
		raceByTrack.put(key, rm);
		return true;
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
		try { matchmakingRemove(p.getUniqueId()); } catch (Throwable ignored) {}
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
		try { matchmakingRemove(p.getUniqueId()); } catch (Throwable ignored) {}
		if (ok) {
			trackByPlayer.remove(p.getUniqueId());
			// Free memory: if race is empty and idle, remove it.
			boolean hasCountdown = false;
			try {
				for (UUID id : rm.getInvolved()) {
					if (rm.isCountdownActiveFor(id)) {
						hasCountdown = true;
						break;
					}
				}
			} catch (Throwable ignored) {}
			if (!rm.isRunning() && !hasCountdown && rm.getInvolved().isEmpty()) {
				raceByTrack.remove(trackName);
			}
		}
		return ok;
	}

	public synchronized boolean handleDisconnect(UUID playerId) {
		// If this player was spectating, remember to restore their mode on next join.
		try {
			SpectateState st = spectateByPlayer.remove(playerId);
			if (st != null) {
				try {
					if (st.monitorTask != null)
						st.monitorTask.cancel();
				} catch (Throwable ignored) {
				}
				if (st.returnGameMode != null)
					pendingRestoreSpectateModes.put(playerId, st.returnGameMode);
				return true;
			}
		} catch (Throwable ignored) {
		}

		// Remove from matchmaking queue on disconnect.
		try {
			matchmakingRemove(playerId);
		} catch (Throwable ignored) {
		}

		RaceManager rm = findRaceFor(playerId);
		if (rm == null) return false;
		boolean changed = rm.handleRacerDisconnect(playerId);
		if (changed) trackByPlayer.remove(playerId);
		// If they disconnected while involved, teleport them back to lobby on next join.
		try {
			if (playerId != null)
				pendingLobbyTeleport.add(playerId);
		} catch (Throwable ignored) {
		}
		return changed;
	}

	/**
	 * Abandon the current race immediately for an online player.
	 *
	 * Unlike {@link #handleDisconnect(UUID)}, this does not mark the player as
	 * pending lobby teleport; it is intended for interactive actions like /spawn.
	 */
	public void abandonNow(org.bukkit.entity.Player p, boolean teleportToLobby) {
		if (p == null)
			return;
		UUID id = p.getUniqueId();
		if (id == null)
			return;
		RaceManager rm;
		synchronized (this) {
			rm = findRaceFor(id);
			if (rm != null) {
				try {
					rm.handleRacerDisconnect(id);
				} catch (Throwable ignored) {
				}
				trackByPlayer.remove(id);
				pendingLobbyTeleport.remove(id);
			}
		}
		if (!teleportToLobby || plugin == null)
			return;
		try {
			if (p.isInsideVehicle())
				p.leaveVehicle();
		} catch (Throwable ignored) {
		}
		teleportToLobby(p);
	}

	/**
	 * If a player disconnected while in a race/intro, they can't be teleported immediately.
	 * On next join, force them back to their world spawn (lobby).
	 */
	public void restorePendingLobbyTeleport(org.bukkit.entity.Player p) {
		if (p == null)
			return;
		// If this player disconnected mid-spectate and got stuck in spectator mode, restore it.
		try {
			restorePendingSpectateMode(p);
		} catch (Throwable ignored) {
		}
		UUID id = p.getUniqueId();
		if (id == null)
			return;

		boolean shouldTeleport;
		synchronized (this) {
			shouldTeleport = pendingLobbyTeleport.remove(id);
		}
		if (!shouldTeleport)
			return;
		if (plugin == null)
			return;

		try {
			Bukkit.getScheduler().runTask(plugin, () -> {
				try {
					if (!p.isOnline())
						return;

					// Safety: ensure the player is fully removed from any race/intro state.
					// If they are still considered "involved", lobby/event MapEngine boards will
					// intentionally skip spawning to them.
					try {
						synchronized (RaceService.this) {
							RaceManager rm = findRaceFor(id);
							if (rm != null) {
								try {
									rm.handleRacerDisconnect(id);
								} catch (Throwable ignored) {
								}
							}
							trackByPlayer.remove(id);
						}
					} catch (Throwable ignored) {
					}

					try {
						if (p.isInsideVehicle())
							p.leaveVehicle();
					} catch (Throwable ignored) {
					}
					teleportToLobby(p);
				} catch (Throwable ignored) {
				}
			});
		} catch (Throwable ignored) {
			// If scheduling failed, re-add so we try again next join.
			synchronized (this) {
				pendingLobbyTeleport.add(id);
			}
		}
	}

	/**
	 * Debug helper: whether this player is queued to be teleported to lobby on next join.
	 */
	public synchronized boolean isPendingLobbyTeleport(UUID playerId) {
		if (playerId == null)
			return false;
		return pendingLobbyTeleport.contains(playerId);
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

		teleportToLobby(p);

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
	 * Force-stop a race and teleport involved players back to lobby.
	 */
	public synchronized boolean forceStopRace(String trackName) {
		return stopRace(trackName, true);
	}

	/**
	 * Force-finish a race: immediately mark all unfinished racers as finished in
	 * current standing order and trigger normal completion flow (fireworks,
	 * results boards, cleanup).
	 */
	public synchronized boolean forceFinishRace(String trackName) {
		RaceManager rm = get(trackName);
		if (rm == null)
			return false;
		return rm.forceFinishAllRemaining();
	}

	/**
	 * Stop and forget a track entirely: ends any running race, clears player mappings, and
	 * removes spectate state. Use before deleting the track file.
	 */
	public synchronized boolean deleteTrack(String trackName) {
		if (trackName == null || trackName.isBlank()) return false;
		String key = trackName.trim();
		boolean touched = false;

		RaceManager rm = raceByTrack.remove(key);
		if (rm != null) {
			java.util.Set<UUID> involved;
			try { involved = new java.util.HashSet<>(rm.getInvolved()); } catch (Throwable ignored) { involved = java.util.Collections.emptySet(); }
			try { rm.stop(true); } catch (Throwable ignored) {}
			for (UUID id : involved) trackByPlayer.remove(id);
			touched = true;
		}

		java.util.List<UUID> restoreSpectators = new java.util.ArrayList<>();
		for (var en : spectateByPlayer.entrySet()) {
			SpectateState st = en.getValue();
			if (st == null) continue;
			if (!key.equalsIgnoreCase(st.trackName)) continue;
			try { if (st.monitorTask != null) st.monitorTask.cancel(); } catch (Throwable ignored) {}
			restoreSpectators.add(en.getKey());
		}
		for (UUID id : restoreSpectators) {
			spectateByPlayer.remove(id);
			pendingRestoreSpectateModes.remove(id);
			try {
				org.bukkit.entity.Player pl = org.bukkit.Bukkit.getPlayer(id);
				if (pl != null) teleportToLobby(pl);
			} catch (Throwable ignored) {}
		}

		return touched || !restoreSpectators.isEmpty();
	}

	/**
	 * Revert any active phase (running/countdown/registration) back to "registration open".
	 * Keeps the current roster by re-joining previously involved online players.
	 *
	 * Notes:
	 * - Uses RaceManager APIs directly to bypass event-lock checks.
	 * - Does NOT teleport players to lobby; it resets race state, then RaceManager.join() will
	 *   handle moving players to the track's waiting spawn.
	 */
	public synchronized boolean revertRace(String trackName) {
		if (trackName == null || trackName.isBlank())
			return false;
		String key = trackName.trim();
		RaceManager rm = getOrCreate(key);
		if (rm == null)
			return false;

		java.util.List<org.bukkit.entity.Player> roster = new java.util.ArrayList<>();
		try {
			for (java.util.UUID id : rm.getInvolved()) {
				org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(id);
				if (p != null && p.isOnline())
					roster.add(p);
			}
		} catch (Throwable ignored) {
		}

		int laps = 3;
		try {
			laps = rm.getTotalLaps();
		} catch (Throwable ignored) {
			laps = getDefaultLaps();
		}

		boolean hadAny = false;
		try {
			hadAny = rm.stop(false);
		} catch (Throwable ignored) {
			hadAny = false;
		}

		// If track isn't ready, we can still stop, but can't reopen registration.
		try {
			if (rm.getTrackConfig() == null || !rm.getTrackConfig().isReady())
				return hadAny;
		} catch (Throwable ignored) {
			return hadAny;
		}

		boolean opened = false;
		try {
			opened = rm.openRegistration(Math.max(1, laps), null);
		} catch (Throwable ignored) {
			opened = false;
		}
		if (!opened)
			return hadAny;

		boolean anyJoined = false;
		for (org.bukkit.entity.Player p : roster) {
			if (p == null || !p.isOnline())
				continue;
			try {
				if (rm.join(p)) {
					anyJoined = true;
					trackByPlayer.put(p.getUniqueId(), key);
				}
			} catch (Throwable ignored) {
			}
		}

		return hadAny || anyJoined;
	}

	/**
	 * Restart a race: revert to open registration (keeping roster), then start the countdown.
	 */
	public synchronized boolean restartRace(String trackName) {
		if (trackName == null || trackName.isBlank())
			return false;
		String key = trackName.trim();
		RaceManager rm = getOrCreate(key);
		if (rm == null)
			return false;

		boolean reverted = revertRace(key);
		// If revert couldn't reopen registration (track not ready), don't start.
		try {
			if (rm.getTrackConfig() == null || !rm.getTrackConfig().isReady())
				return reverted;
		} catch (Throwable ignored) {
			return reverted;
		}

		java.util.List<org.bukkit.entity.Player> participants = new java.util.ArrayList<>();
		try {
			java.util.Set<java.util.UUID> regs = new java.util.LinkedHashSet<>(rm.getRegistered());
			for (java.util.UUID id : regs) {
				org.bukkit.entity.Player rp = org.bukkit.Bukkit.getPlayer(id);
				if (rp != null && rp.isOnline())
					participants.add(rp);
			}
		} catch (Throwable ignored) {
		}

		if (participants.isEmpty())
			return reverted;

		java.util.List<org.bukkit.entity.Player> placed;
		try {
			placed = rm.placeAtStartsWithBoats(participants);
		} catch (Throwable ignored) {
			placed = java.util.Collections.emptyList();
		}
		if (placed.isEmpty())
			return reverted;

		try {
			rm.startLightsCountdown(placed);
		} catch (Throwable ignored) {
			return reverted;
		}

		return true;
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
		// Cleanup spectate state to prevent memory leak.
		for (SpectateState st : spectateByPlayer.values()) {
			try {
				if (st != null && st.monitorTask != null)
					st.monitorTask.cancel();
			} catch (Throwable ignored) {}
		}
		spectateByPlayer.clear();
		pendingRestoreSpectateModes.clear();
		pendingLobbyTeleport.clear();
		try {
			matchmaking.stop();
		} catch (Throwable ignored) {
		}
	}
}

