package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.storage.EventStorage;
import dev.belikhun.boatracing.integrations.mapengine.EventBoardService;
import dev.belikhun.boatracing.integrations.mapengine.OpeningTitlesBoardService;
import dev.belikhun.boatracing.race.RaceFx;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

import java.time.Duration;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Event orchestrator (draft).
 *
 * This is intentionally minimal scaffolding to establish the public surface.
 * The actual runtime orchestration (start tracks, hook race completion, podium NPCs)
 * will be implemented in follow-up steps.
 */
public class EventService {
	private static final LegacyComponentSerializer TITLE_LEGACY = LegacyComponentSerializer.legacySection();
	public enum TrackPoolResult {
		OK,
		NO_SUCH_EVENT,
		EVENT_RUNNING,
		TRACK_INVALID,
		DUPLICATE,
		NOT_FOUND
	}

	private final BoatRacingPlugin plugin;
	private final File dataFolder;
	private final PodiumService podiumService;
	private final EventRegistrationNpcService registrationNpcService;
	private final EventBoardService eventBoardService;
	private final OpeningTitlesBoardService openingTitlesBoardService;
	private final OpeningTitlesController openingTitlesController;

	private final Map<String, RaceEvent> eventsById = new HashMap<>();
	private String activeEventId;

	private int tickTaskId = -1;
	private long trackDeadlineMillis = 0L;
	private String activeTrackName;
	private boolean trackCountdownStarted = false;
	private static final int TRACK_SECONDS = 5 * 60;
	private java.util.Set<java.util.UUID> currentTrackRoster = new java.util.LinkedHashSet<>();
	private boolean trackWasRunning = false;

	// Event phase scheduling (runtime-only; not persisted)
	private static final int FIRST_TRACK_WAIT_SECONDS = 60;
	private static final int BREAK_SECONDS = 5 * 60;
	private long introEndMillis = 0L;
	private long lobbyWaitEndMillis = 0L;
	private long breakEndMillis = 0L;
	private boolean lobbyGatherDone = false;

	public EventService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.dataFolder = plugin.getDataFolder();
		this.podiumService = new PodiumService(plugin);
		this.registrationNpcService = new EventRegistrationNpcService(plugin, this);
		this.eventBoardService = new EventBoardService(plugin, this);
		this.openingTitlesBoardService = new OpeningTitlesBoardService(plugin);
		this.openingTitlesController = new OpeningTitlesController(plugin, this, openingTitlesBoardService);
		this.activeEventId = null;
		this.activeTrackName = null;
	}

	public void start() {
		loadAll();
		String active = EventStorage.loadActive(dataFolder);
		setActiveEvent(active);
		boolean tmpPodiumDebug = false;
		try {
			tmpPodiumDebug = plugin != null && plugin.getConfig().getBoolean("event.podium.debug", false);
		} catch (Throwable ignored) {
			tmpPodiumDebug = false;
		}
		final boolean podiumDebug = tmpPodiumDebug;
		try {
			if (eventBoardService != null)
				eventBoardService.reloadFromConfig();
		} catch (Throwable ignored) {
		}
		try {
			if (openingTitlesBoardService != null)
				openingTitlesBoardService.reloadFromConfig();
		} catch (Throwable ignored) {
		}
		ensureTickTask();

		// Auto-show the registration NPC after restart if registration is open.
		try {
			RaceEvent e0 = getActiveEvent();
			if (e0 != null && e0.state == EventState.REGISTRATION && registrationNpcService != null)
				registrationNpcService.tick(e0);
		} catch (Throwable ignored) {
		}

		// Auto-spawn podium after restart if the active event is already completed.
		try {
			RaceEvent e = getActiveEvent();
			if (podiumDebug) {
				try {
					String eid = (e == null ? "<null>" : (e.id == null ? "<no-id>" : e.id));
					String st = (e == null || e.state == null) ? "<null>" : e.state.name();
					plugin.getLogger().info("[Event][PodiumDBG] Startup: activeEventId=" + activeEventId + " loadedEventId=" + eid + " state=" + st);
				} catch (Throwable ignored) {
				}
			}
			if (e != null && e.state == EventState.COMPLETED && podiumService != null) {
				if (podiumDebug) {
					try {
						plugin.getLogger().info("[Event][PodiumDBG] Active event is COMPLETED; scheduling podium auto-spawn retries.");
					} catch (Throwable ignored) {
					}
				}
				// Retry a few times in case chunks/plugins aren't ready immediately.
				final int maxAttempts = 5;
				for (int i = 0; i < maxAttempts; i++) {
					final int attempt = i + 1;
					long delay = 20L + (i * 40L); // 1s, 3s, 5s, 7s, 9s
					if (podiumDebug) {
						try {
							plugin.getLogger().info("[Event][PodiumDBG] Scheduling auto-spawn attempt " + attempt + "/" + maxAttempts + " in " + delay + " ticks.");
						} catch (Throwable ignored) {
						}
					}
					Bukkit.getScheduler().runTaskLater(plugin, () -> {
						try {
							if (podiumDebug) {
								try {
									plugin.getLogger().info("[Event][PodiumDBG] Running auto-spawn attempt " + attempt + "/" + maxAttempts
											+ " (alreadySpawned=" + podiumService.hasSpawnedAnything() + ")");
								} catch (Throwable ignored) {
								}
							}
							if (podiumService.hasSpawnedAnything())
								return;
							podiumService.spawnTop3(e);
							if (podiumDebug) {
								try {
									plugin.getLogger().info("[Event][PodiumDBG] Attempt " + attempt + " completed (spawnedAnything=" + podiumService.hasSpawnedAnything() + ")");
								} catch (Throwable ignored) {
								}
							}
						} catch (Throwable ignored) {
							if (podiumDebug) {
								try {
									plugin.getLogger().warning("[Event][PodiumDBG] Exception while spawning podium on attempt " + attempt + ": " + ignored.getMessage());
								} catch (Throwable ignored2) {
								}
							}
						}
						try {
							if (!podiumService.hasSpawnedAnything() && attempt == maxAttempts) {
								plugin.getLogger().warning("[Event] Podium auto-spawn failed after restart (no entities spawned).");
							}
						} catch (Throwable ignored) {
						}
					}, delay);
				}
			} else if (e != null && e.state == EventState.DISABLED && podiumService != null) {
				if (podiumDebug) {
					try {
						plugin.getLogger().info("[Event][PodiumDBG] Active event is DISABLED; scheduling podium auto-spawn retries.");
					} catch (Throwable ignored) {
					}
				}
				// Retry a few times in case chunks/plugins aren't ready immediately.
				final int maxAttempts = 5;
				for (int i = 0; i < maxAttempts; i++) {
					final int attempt = i + 1;
					long delay = 20L + (i * 40L); // 1s, 3s, 5s, 7s, 9s
					if (podiumDebug) {
						try {
							plugin.getLogger().info("[Event][PodiumDBG] Scheduling auto-spawn attempt " + attempt + "/" + maxAttempts + " in " + delay + " ticks.");
						} catch (Throwable ignored) {
						}
					}
					Bukkit.getScheduler().runTaskLater(plugin, () -> {
						try {
							if (podiumDebug) {
								try {
									plugin.getLogger().info("[Event][PodiumDBG] Running auto-spawn attempt " + attempt + "/" + maxAttempts
											+ " (alreadySpawned=" + podiumService.hasSpawnedAnything() + ")");
								} catch (Throwable ignored) {
								}
							}
							if (podiumService.hasSpawnedAnything())
								return;
							podiumService.spawnTop3(e);
							if (podiumDebug) {
								try {
									plugin.getLogger().info("[Event][PodiumDBG] Attempt " + attempt + " completed (spawnedAnything=" + podiumService.hasSpawnedAnything() + ")");
								} catch (Throwable ignored) {
								}
							}
						} catch (Throwable ignored) {
							if (podiumDebug) {
								try {
									plugin.getLogger().warning("[Event][PodiumDBG] Exception while spawning podium on attempt " + attempt + ": " + ignored.getMessage());
								} catch (Throwable ignored2) {
								}
							}
						}
						try {
							if (!podiumService.hasSpawnedAnything() && attempt == maxAttempts) {
								plugin.getLogger().warning("[Event] Podium auto-spawn failed after restart (no entities spawned).");
							}
						} catch (Throwable ignored) {
						}
					}, delay);
				}
			} else if (podiumDebug) {
				try {
					String st = (e == null || e.state == null) ? "<null>" : e.state.name();
					plugin.getLogger().info("[Event][PodiumDBG] Skipping auto-spawn (event=" + (e == null ? "null" : "present") + ", state=" + st
							+ ", podiumService=" + (podiumService == null ? "null" : "present") + ")");
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public PodiumService getPodiumService() {
		return podiumService;
	}

	public EventRegistrationNpcService getRegistrationNpcService() {
		return registrationNpcService;
	}

	public EventBoardService getEventBoardService() {
		return eventBoardService;
	}

	public OpeningTitlesBoardService getOpeningTitlesBoardService() {
		return openingTitlesBoardService;
	}

	public boolean isOpeningTitlesRunning() {
		try {
			return openingTitlesController != null && openingTitlesController.isRunning();
		} catch (Throwable ignored) {
			return false;
		}
	}

	public boolean startOpeningTitlesNow() {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		// Avoid breaking a running track/countdown.
		try {
			if (activeTrackName != null || trackCountdownStarted)
				return false;
		} catch (Throwable ignored) {
		}
		try {
			if (openingTitlesController != null)
				openingTitlesController.start(e, () -> {
				});
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	public void stopOpeningTitlesNow(boolean restorePlayers) {
		try {
			if (openingTitlesController != null)
				openingTitlesController.stop(restorePlayers);
		} catch (Throwable ignored) {
		}
		try {
			if (openingTitlesBoardService != null)
				openingTitlesBoardService.stop();
		} catch (Throwable ignored) {
		}
	}

	public void previewOpeningTitlesBoard(Player p) {
		if (p == null)
			return;
		try {
			if (openingTitlesBoardService != null)
				openingTitlesBoardService.previewTo(p);
		} catch (Throwable ignored) {
		}
	}

	public void previewOpeningTitlesBoard(Player viewer, Player racer) {
		if (viewer == null)
			return;
		Player subject = (racer != null ? racer : viewer);
		try {
			if (openingTitlesBoardService != null)
				openingTitlesBoardService.previewRacerCardTo(viewer, subject.getUniqueId(), subject.getName());
		} catch (Throwable ignored) {
		}
	}

	public void resetOpeningTitlesBoardPreview(Player viewer) {
		if (viewer == null)
			return;
		try {
			if (openingTitlesBoardService != null)
				openingTitlesBoardService.resetViewer(viewer);
		} catch (Throwable ignored) {
		}
	}

	public void restorePendingOpeningTitles(Player p) {
		if (openingTitlesController == null || p == null)
			return;
		try {
			openingTitlesController.restorePending(p);
		} catch (Throwable ignored) {
		}
	}

	// ===================== Runtime snapshot getters (for UI/boards) =====================
	public synchronized String getActiveTrackNameRuntime() {
		return (activeTrackName == null || activeTrackName.isBlank()) ? null : activeTrackName;
	}

	public synchronized boolean isTrackCountdownStarted() {
		return trackCountdownStarted;
	}

	public synchronized long getIntroEndMillis() {
		return introEndMillis;
	}

	public synchronized long getLobbyWaitEndMillis() {
		return lobbyWaitEndMillis;
	}

	public synchronized long getBreakEndMillis() {
		return breakEndMillis;
	}

	public synchronized long getTrackDeadlineMillis() {
		return trackDeadlineMillis;
	}

	public synchronized void loadAll() {
		eventsById.clear();
		for (String id : EventStorage.listEventIds(dataFolder)) {
			RaceEvent e = EventStorage.loadEvent(dataFolder, id);
			if (e != null && e.id != null && !e.id.isBlank())
				eventsById.put(e.id.trim(), e);
		}
	}

	public synchronized void saveAll() {
		for (RaceEvent e : eventsById.values()) {
			try {
				EventStorage.saveEvent(dataFolder, e);
			} catch (Throwable ignored) {
			}
		}
		try {
			EventStorage.saveActive(dataFolder, activeEventId);
		} catch (Throwable ignored) {
		}
	}

	public synchronized RaceEvent getActiveEvent() {
		if (activeEventId == null)
			return null;
		return eventsById.get(activeEventId);
	}

	public synchronized void setActiveEvent(String id) {
		this.activeEventId = (id == null || id.isBlank()) ? null : id.trim();
		try {
			EventStorage.saveActive(dataFolder, this.activeEventId);
		} catch (Throwable ignored) {
		}
	}

	/**
	 * During a RUNNING event, all tracks in the pool are locked.
	 * This prevents non-event races from being started on event tracks.
	 */
	public synchronized boolean isTrackLocked(String trackName) {
		if (trackName == null || trackName.isBlank())
			return false;
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		if (e.state != EventState.RUNNING)
			return false;
		if (e.trackPool == null || e.trackPool.isEmpty())
			return false;
		String tn = trackName.trim();
		for (String s : e.trackPool) {
			if (s != null && s.equalsIgnoreCase(tn))
				return true;
		}
		return false;
	}

	public synchronized RaceEvent get(String id) {
		if (id == null || id.isBlank())
			return null;
		return eventsById.get(id.trim());
	}

	public synchronized Collection<RaceEvent> allEvents() {
		return Collections.unmodifiableCollection(eventsById.values());
	}

	public synchronized boolean put(RaceEvent e) {
		if (e == null)
			return false;
		if (e.id == null || e.id.isBlank())
			return false;
		eventsById.put(e.id.trim(), e);
		try {
			EventStorage.saveEvent(dataFolder, e);
		} catch (Throwable ignored) {
		}
		return true;
	}

	public synchronized boolean remove(String id) {
		if (id == null || id.isBlank())
			return false;
		String key = id.trim();
		if (activeEventId != null && activeEventId.equals(key))
			activeEventId = null;
		boolean ok = eventsById.remove(key) != null;
		try {
			java.io.File f = EventStorage.eventFile(dataFolder, key);
			if (f.exists()) {
				//noinspection ResultOfMethodCallIgnored
				f.delete();
			}
		} catch (Throwable ignored) {
		}
		try {
			EventStorage.saveActive(dataFolder, activeEventId);
		} catch (Throwable ignored) {
		}
		return ok;
	}

	public void stop() {
		if (tickTaskId != -1) {
			try {
				Bukkit.getScheduler().cancelTask(tickTaskId);
			} catch (Throwable ignored) {
			}
			tickTaskId = -1;
		}
		try {
			if (openingTitlesController != null)
				openingTitlesController.stop(true);
		} catch (Throwable ignored) {
		}
		try {
			if (openingTitlesBoardService != null)
				openingTitlesBoardService.stop();
		} catch (Throwable ignored) {
		}
		try {
			if (eventBoardService != null)
				eventBoardService.stop();
		} catch (Throwable ignored) {
		}
		try {
			if (podiumService != null)
				podiumService.clear();
		} catch (Throwable ignored) {
		}
		try {
			if (registrationNpcService != null)
				registrationNpcService.clear();
		} catch (Throwable ignored) {
		}
		try {
			saveAll();
		} catch (Throwable ignored) {
		}
	}

	// ===================== Commands API =====================
	public synchronized boolean createEvent(String id, String title) {
		if (id == null || id.isBlank())
			return false;
		String key = id.trim();
		if (eventsById.containsKey(key))
			return false;
		RaceEvent e = new RaceEvent();
		e.id = key;
		e.title = title == null ? "" : title;
		e.description = "";
		e.state = EventState.DRAFT;
		e.startTimeMillis = 0L;
		put(e);

		// If there is no ongoing active event, treat the newly created event as the new active
		// context so UI/boards don't keep showing stale data from the previous event.
		try {
			RaceEvent active = getActiveEvent();
			boolean finished = (active != null && (active.state == EventState.CANCELLED || active.state == EventState.COMPLETED || active.state == EventState.DISABLED));
			if (active == null || finished) {
				setActiveEvent(e.id);
				activeTrackName = null;
				trackCountdownStarted = false;
				trackDeadlineMillis = 0L;
				currentTrackRoster.clear();
				trackWasRunning = false;
				introEndMillis = 0L;
				lobbyWaitEndMillis = 0L;
				breakEndMillis = 0L;
				lobbyGatherDone = false;
				try {
					if (podiumService != null)
						podiumService.clear();
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
		return true;
	}

	public synchronized boolean openRegistration(String eventId) {
		RaceEvent e = get(eventId);
		if (e == null)
			return false;
		RaceEvent active = getActiveEvent();
		if (active != null && active.state != null
				&& active.state != EventState.CANCELLED
				&& active.state != EventState.COMPLETED
				&& active.state != EventState.DISABLED
				&& !active.id.equals(e.id)) {
			return false;
		}
		setActiveEvent(e.id);

		// Opening registration for a brand-new event should always start fresh.
		// (Prevents stale participant scores/rosters from being carried over.)
		try {
			if (e.state == null || e.state == EventState.DRAFT) {
				if (e.participants != null)
					e.participants.clear();
				else
					e.participants = new java.util.HashMap<>();
				if (e.registrationOrder != null)
					e.registrationOrder.clear();
				else
					e.registrationOrder = new java.util.ArrayList<>();
			}
		} catch (Throwable ignored) {
		}

		e.state = EventState.REGISTRATION;
		e.currentTrackIndex = 0;
		e.startTimeMillis = 0L;
		activeTrackName = null;
		trackCountdownStarted = false;
		trackDeadlineMillis = 0L;
		currentTrackRoster.clear();
		trackWasRunning = false;
		introEndMillis = 0L;
		lobbyWaitEndMillis = 0L;
		breakEndMillis = 0L;
		lobbyGatherDone = false;
		try {
			if (podiumService != null)
				podiumService.clear();
		} catch (Throwable ignored) {
		}
		EventStorage.saveEvent(dataFolder, e);
		return true;
	}

	public synchronized boolean scheduleActiveEvent(int secondsFromNow) {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		if (e.state != EventState.REGISTRATION)
			return false;
		int sec = Math.max(0, secondsFromNow);
		e.startTimeMillis = System.currentTimeMillis() + (sec * 1000L);
		EventStorage.saveEvent(dataFolder, e);
		return true;
	}

	public synchronized boolean scheduleActiveEventAtMillis(long startTimeMillis) {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		if (e.state != EventState.REGISTRATION)
			return false;
		long t = Math.max(0L, startTimeMillis);
		e.startTimeMillis = t;
		EventStorage.saveEvent(dataFolder, e);
		return true;
	}

	public synchronized boolean startActiveEventNow() {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		return startEvent(e);
	}

	public synchronized boolean cancelActiveEvent() {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		e.state = EventState.CANCELLED;
		e.startTimeMillis = 0L;
		EventStorage.saveEvent(dataFolder, e);
		try {
			if (activeTrackName != null && !activeTrackName.isBlank()) {
				plugin.getRaceService().stopRace(activeTrackName, true);
			}
		} catch (Throwable ignored) {
		}
		activeTrackName = null;
		trackCountdownStarted = false;
		trackDeadlineMillis = 0L;
		currentTrackRoster.clear();
		trackWasRunning = false;
		introEndMillis = 0L;
		lobbyWaitEndMillis = 0L;
		breakEndMillis = 0L;
		lobbyGatherDone = false;
		return true;
	}

	/**
	 * Disable a finished event:
	 * - Keep the MapEngine event board showing final results
	 * - Keep podium NPCs
	 * - Restore normal lobby scoreboards for players
	 */
	public synchronized boolean disableActiveEvent() {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		if (e.state != EventState.COMPLETED && e.state != EventState.CANCELLED)
			return false;
		e.state = EventState.DISABLED;
		EventStorage.saveEvent(dataFolder, e);
		try {
			if (plugin != null && plugin.getScoreboardService() != null)
				plugin.getScoreboardService().forceTick();
		} catch (Throwable ignored) {
		}
		return true;
	}

	public synchronized boolean addTrackToActiveEvent(String trackName) {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		TrackPoolResult r = addTrackToEvent(e.id, trackName);
		return r == TrackPoolResult.OK;
	}

	public synchronized TrackPoolResult addTrackToEvent(String eventId, String trackName) {
		RaceEvent e = get(eventId);
		if (e == null)
			return TrackPoolResult.NO_SUCH_EVENT;
		if (e.state == EventState.RUNNING)
			return TrackPoolResult.EVENT_RUNNING;
		if (trackName == null || trackName.isBlank())
			return TrackPoolResult.TRACK_INVALID;
		String tn = trackName.trim();
		if (tn.isBlank())
			return TrackPoolResult.TRACK_INVALID;
		if (e.trackPool == null)
			e.trackPool = new java.util.ArrayList<>();
		for (String s : e.trackPool) {
			if (s != null && s.equalsIgnoreCase(tn))
				return TrackPoolResult.DUPLICATE;
		}
		e.trackPool.add(tn);
		EventStorage.saveEvent(dataFolder, e);
		return TrackPoolResult.OK;
	}

	public synchronized boolean removeTrackFromActiveEvent(String trackName) {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		TrackPoolResult r = removeTrackFromEvent(e.id, trackName);
		return r == TrackPoolResult.OK;
	}

	public synchronized TrackPoolResult removeTrackFromEvent(String eventId, String trackName) {
		RaceEvent e = get(eventId);
		if (e == null)
			return TrackPoolResult.NO_SUCH_EVENT;
		if (e.state == EventState.RUNNING)
			return TrackPoolResult.EVENT_RUNNING;
		if (trackName == null || trackName.isBlank())
			return TrackPoolResult.TRACK_INVALID;
		String tn = trackName.trim();
		if (tn.isBlank())
			return TrackPoolResult.TRACK_INVALID;
		if (e.trackPool == null)
			return TrackPoolResult.NOT_FOUND;
		boolean ok = e.trackPool.removeIf(s -> s != null && s.equalsIgnoreCase(tn));
		EventStorage.saveEvent(dataFolder, e);
		return ok ? TrackPoolResult.OK : TrackPoolResult.NOT_FOUND;
	}

	public synchronized boolean registerToActiveEvent(Player p) {
		if (p == null)
			return false;
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		if (e.state != EventState.REGISTRATION)
			return false;
		UUID id = p.getUniqueId();
		if (e.participants == null)
			e.participants = new java.util.HashMap<>();
		EventParticipant ep = e.participants.get(id);
		if (ep == null) {
			ep = new EventParticipant(id);
			ep.pointsTotal = 0;
			e.participants.put(id, ep);
		}
		ep.status = EventParticipantStatus.REGISTERED;
		ep.nameSnapshot = p.getName();
		ep.lastSeenMillis = System.currentTimeMillis();

		if (e.registrationOrder == null)
			e.registrationOrder = new java.util.ArrayList<>();
		if (!e.registrationOrder.contains(id))
			e.registrationOrder.add(id);

		EventStorage.saveEvent(dataFolder, e);
		return true;
	}

	public synchronized boolean leaveActiveEvent(Player p) {
		if (p == null)
			return false;
		RaceEvent e = getActiveEvent();
		if (e == null || e.participants == null)
			return false;
		EventParticipant ep = e.participants.get(p.getUniqueId());
		if (ep == null)
			return false;
		ep.status = EventParticipantStatus.LEFT;
		ep.lastSeenMillis = System.currentTimeMillis();
		EventStorage.saveEvent(dataFolder, e);
		return true;
	}

	// ===================== Runtime tick =====================
	private void ensureTickTask() {
		if (plugin == null)
			return;
		if (tickTaskId != -1)
			return;
		tickTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L).getTaskId();
	}

	private void tick() {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return;
		try {
			if (registrationNpcService != null)
				registrationNpcService.tick(e);
		} catch (Throwable ignored) {
		}
		if (e.state == EventState.CANCELLED || e.state == EventState.COMPLETED || e.state == EventState.DISABLED)
			return;

		// Auto-start at startTime when in registration.
		if (e.state == EventState.REGISTRATION && e.startTimeMillis > 0L && System.currentTimeMillis() >= e.startTimeMillis) {
			try {
				startEvent(e);
			} catch (Throwable ignored) {
			}
		}

		if (e.state != EventState.RUNNING)
			return;
		if (activeTrackName == null || activeTrackName.isBlank())
			return;

		// Opening titles (per-tick runtime). When active, block the normal pre-track timers.
		try {
			if (openingTitlesController != null && openingTitlesController.isRunning() && e.currentTrackIndex == 0)
				return;
		} catch (Throwable ignored) {
		}

		long now = System.currentTimeMillis();

		// Pre-first-track waiting period.
		if (lobbyWaitEndMillis > 0L && now < lobbyWaitEndMillis)
			return;

		// Between-track break.
		if (breakEndMillis > 0L && now < breakEndMillis)
			return;

		RaceManager rm;
		try {
			rm = plugin.getRaceService().getOrCreate(activeTrackName);
		} catch (Throwable ignored) {
			rm = null;
		}
		if (rm == null)
			return;

		// Start track (after intro/lobby wait/break).
		if (!trackCountdownStarted) {
			boolean starting = false;
			try {
				starting = rm.isAnyCountdownActive();
			} catch (Throwable ignored) {
				starting = false;
			}
			if (!starting) {
				try {
					startTrackCountdownNow(e, rm);
				} catch (Throwable ignored) {
				}
			}
		}

		// Start 5-minute timer when the race actually begins running.
		boolean runningNow = false;
		try {
			runningNow = rm.isRunning();
		} catch (Throwable ignored) {
			runningNow = false;
		}
		if (!trackWasRunning && runningNow) {
			trackWasRunning = true;
			trackDeadlineMillis = System.currentTimeMillis() + (TRACK_SECONDS * 1000L);
		}

		// Finish the track as soon as everyone finished.
		try {
			if (rm.areAllParticipantsFinished()) {
				finishTrack(e, rm, false);
				return;
			}
		} catch (Throwable ignored) {
		}

		// Per-track time limit.
		if (trackWasRunning && trackDeadlineMillis > 0L && System.currentTimeMillis() >= trackDeadlineMillis) {
			try {
				finishTrack(e, rm, true);
			} catch (Throwable ignored) {
			}
		}
	}

	public synchronized boolean startEvent(RaceEvent e) {
		if (e == null)
			return false;
		if (e.trackPool == null || e.trackPool.isEmpty())
			return false;
		if (e.state != EventState.REGISTRATION && e.state != EventState.DRAFT)
			return false;

		e.state = EventState.RUNNING;
		e.currentTrackIndex = Math.max(0, e.currentTrackIndex);
		// Preserve the event's start timestamp for UI (boards/HUD). This used to be
		// reused as a "scheduled start" timestamp during registration, but once the
		// event begins we want to display the actual start time.
		e.startTimeMillis = System.currentTimeMillis();
		activeTrackName = e.currentTrackName();
		trackCountdownStarted = false;
		trackDeadlineMillis = 0L;
		currentTrackRoster.clear();
		trackWasRunning = false;
		// Phase schedule
		long now = System.currentTimeMillis();
		lobbyWaitEndMillis = 0L;
		breakEndMillis = 0L;
		lobbyGatherDone = false;

		// Replace legacy 30s placeholder intro with the real opening titles intro sequence.
		// Only run it for the first track.
		boolean useOpeningTitles = (e.currentTrackIndex == 0);
		introEndMillis = 0L;
		if (useOpeningTitles) {
			try {
				gatherEligibleToLobby();
			} catch (Throwable ignored) {
			}
			try {
				if (openingTitlesController != null)
					openingTitlesController.start(e, () -> {
						// After titles: ensure everyone is in lobby, then allow starting track countdown.
						try {
							gatherEligibleToLobby();
						} catch (Throwable ignored) {
						}
						lobbyGatherDone = true;
						introEndMillis = 0L;
						lobbyWaitEndMillis = 0L;
					});
			} catch (Throwable ignored) {
			}
		}
		EventStorage.saveEvent(dataFolder, e);
		broadcastToParticipants(e, "&eSự kiện &f" + safeName(e.title) + "&e đã bắt đầu!");
		if (useOpeningTitles)
			broadcastToParticipants(e, "&7⏳ Đang giới thiệu... &fchuẩn bị chặng 1&7.");
		return true;
	}

	private void startTrackCountdownNow(RaceEvent e, RaceManager rm) {
		if (e == null || rm == null)
			return;
		if (rm.getTrackConfig() == null || !rm.getTrackConfig().isReady()) {
			broadcastToParticipants(e, "&c❌ Track không sẵn sàng: &f" + safeName(activeTrackName));
			finishEvent(e, "&cSự kiện bị dừng do track không sẵn sàng.");
			return;
		}

		java.util.List<Player> online = collectEligibleOnline(e);
		if (online.isEmpty()) {
			broadcastToParticipants(e, "&eChưa có người chơi online để bắt đầu chặng.");
			return;
		}

		int startSlots = 0;
		try {
			startSlots = rm.getTrackConfig().getStarts().size();
		} catch (Throwable ignored) {
			startSlots = 0;
		}
		startSlots = Math.max(0, startSlots);

		java.util.List<Player> ordered = orderByRegistration(e, online);
		if (startSlots > 0 && ordered.size() > startSlots) {
			ordered = ordered.subList(0, startSlots);
			broadcastToParticipants(e, "&e⚠ Track này thiếu vị trí xuất phát. &7Ưu tiên theo thứ tự đăng ký.");
		}

		boolean ok = rm.startIntroThenCountdown(ordered, (placed) -> {
			if (placed == null || placed.isEmpty()) {
				broadcastToParticipants(e, "&c❌ Không thể đặt người chơi vào vị trí xuất phát.");
				return;
			}

			currentTrackRoster.clear();
			for (Player p : placed) {
				if (p != null)
					currentTrackRoster.add(p.getUniqueId());
			}

			trackCountdownStarted = true;
			trackWasRunning = false;
			trackDeadlineMillis = 0L;
			// Clear pre-start/break timers once a track countdown begins.
			introEndMillis = 0L;
			lobbyWaitEndMillis = 0L;
			breakEndMillis = 0L;
			broadcastToParticipants(e, "&eBắt đầu chặng: &f" + safeName(activeTrackName) + "&e.");
		});
		if (!ok) {
			broadcastToParticipants(e, "&c❌ Không thể bắt đầu giới thiệu đường đua.");
		}
	}

	private void finishTrack(RaceEvent e, RaceManager rm, boolean timedOut) {
		if (e == null || rm == null)
			return;

		if (timedOut)
			broadcastToParticipants(e, "&c⌛ Hết giờ chặng! &7DNF = 0 điểm.");

		awardPointsForTrack(e, rm);

		// IMPORTANT: Do not hard-stop the race on normal completion.
		// RaceManager triggers the completion sequence (fireworks/boards/spectator) and
		// schedules its own cleanup stop(false). If we call stop(true) here, we cancel
		// the firework show immediately.
		if (timedOut) {
			try {
				rm.stop(false);
			} catch (Throwable ignored) {
			}
		}

		advanceToNextTrackOrFinish(e);
	}

	private void awardPointsForTrack(RaceEvent e, RaceManager rm) {
		if (e == null || rm == null)
			return;
		if (e.participants == null)
			return;

		java.util.Map<java.util.UUID, Integer> finishPos = new java.util.HashMap<>();
		java.util.Map<java.util.UUID, Boolean> finished = new java.util.HashMap<>();
		for (java.util.UUID id : currentTrackRoster) {
			finished.put(id, false);
		}

		try {
			for (RaceManager.ParticipantState s : rm.getStandings()) {
				if (s == null || s.id == null)
					continue;
				finishPos.put(s.id, Math.max(0, s.finishPosition));
				finished.put(s.id, s.finished);
			}
		} catch (Throwable ignored) {
		}

		int startedCount = currentTrackRoster.size();
		for (java.util.UUID id : currentTrackRoster) {
			EventParticipant ep = e.participants.get(id);
			if (ep == null)
				continue;
			boolean fin = finished.getOrDefault(id, false);
			int pts;
			if (!fin) {
				pts = MarioKartPoints.pointsForDnf();
			} else {
				int pos = finishPos.getOrDefault(id, 0);
				pts = pos > 0 ? MarioKartPoints.pointsForPlace(pos, startedCount) : MarioKartPoints.pointsForDnf();
			}
			ep.pointsTotal += Math.max(0, pts);
			try {
				ep.lastSeenMillis = System.currentTimeMillis();
			} catch (Throwable ignored) {
			}
		}

		EventStorage.saveEvent(dataFolder, e);
	}

	private void advanceToNextTrackOrFinish(RaceEvent e) {
		if (e == null)
			return;
		int next = e.currentTrackIndex + 1;
		int total = (e.trackPool == null ? 0 : e.trackPool.size());
		if (next >= total) {
			finishEvent(e, "&a✔ Sự kiện đã kết thúc!");
			return;
		}
		e.currentTrackIndex = next;
		EventStorage.saveEvent(dataFolder, e);
		activeTrackName = e.currentTrackName();
		trackCountdownStarted = false;
		trackWasRunning = false;
		trackDeadlineMillis = 0L;
		currentTrackRoster.clear();
		// Break time before next track.
		breakEndMillis = System.currentTimeMillis() + (BREAK_SECONDS * 1000L);
		broadcastToParticipants(e, "&e⏳ Nghỉ giải lao &f" + Time.formatCountdownSeconds(BREAK_SECONDS)
				+ "&e. Chặng tiếp theo: &f" + safeName(activeTrackName));
	}

	private void finishEvent(RaceEvent e, String msg) {
		if (e == null)
			return;
		try {
			if (openingTitlesController != null)
				openingTitlesController.stop(true);
		} catch (Throwable ignored) {
		}
		e.state = EventState.COMPLETED;
		EventStorage.saveEvent(dataFolder, e);
		broadcastToParticipants(e, msg);
		try {
			broadcastFinalRanking(e);
		} catch (Throwable ignored) {
		}
		activeTrackName = null;
		trackCountdownStarted = false;
		trackWasRunning = false;
		trackDeadlineMillis = 0L;
		currentTrackRoster.clear();
		introEndMillis = 0L;
		lobbyWaitEndMillis = 0L;
		breakEndMillis = 0L;
		lobbyGatherDone = false;
		try {
			if (podiumService != null)
				podiumService.spawnTop3(e);
		} catch (Throwable ignored) {
		}
	}

	private void gatherEligibleToLobby() {
		if (plugin == null)
			return;
		RaceEvent e = getActiveEvent();
		if (e == null)
			return;
		for (Player p : collectEligibleOnline(e)) {
			if (p == null || !p.isOnline())
				continue;
			try {
				plugin.getRaceService().leaveToLobby(p);
			} catch (Throwable ignored) {
			}
		}
	}

	private void broadcastFinalRanking(RaceEvent e) {
		if (e == null || e.participants == null)
			return;
		java.util.List<EventParticipant> list = new java.util.ArrayList<>(e.participants.values());
		list.removeIf(p -> p == null || p.id == null || p.status == EventParticipantStatus.LEFT);
		list.sort(java.util.Comparator
				.comparingInt((EventParticipant p) -> p == null ? Integer.MIN_VALUE : p.pointsTotal).reversed()
				.thenComparing(p -> p == null ? "" : (p.nameSnapshot == null ? "" : p.nameSnapshot),
						String.CASE_INSENSITIVE_ORDER));

		java.util.List<String> lines = new java.util.ArrayList<>();
		// Decorative box: keep header/footer widths consistent.
		final String boxTitle = "XẾP HẠNG CHUNG CUỘC";
		int innerW = Math.max(28, boxTitle.length() + 2 + 12); // title + spaces + padding
		int filler = Math.max(0, innerW - (boxTitle.length() + 2));
		int left = filler / 2;
		int right = filler - left;
		lines.add("&6&l┏" + "━".repeat(left) + " &e" + boxTitle + " &6&l" + "━".repeat(right) + "┓");
		lines.add("&7Sự kiện: &f" + safeName(e.title) + " &8● &7Tổng chặng: &f" + (e.trackPool == null ? 0 : e.trackPool.size()));

		int shown = 0;
		for (EventParticipant ep : list) {
			if (ep == null || ep.id == null)
				continue;
			shown++;
			if (shown > 10)
				break;
			String name = (ep.nameSnapshot == null || ep.nameSnapshot.isBlank())
					? Bukkit.getOfflinePlayer(ep.id).getName()
					: ep.nameSnapshot;
			if (name == null || name.isBlank())
				name = "Tay đua " + shortId(ep.id);
			String display;
			try {
				var pm = plugin.getProfileManager();
				display = (pm != null) ? pm.formatRacerLegacy(ep.id, name) : ("&f" + name);
			} catch (Throwable ignored) {
				display = "&f" + name;
			}
			String tag;
			if (shown == 1)
				tag = "&6#1";
			else if (shown == 2)
				tag = "&7#2";
			else if (shown == 3)
				tag = "&c#3";
			else
				tag = "&f#" + shown;

			lines.add(tag + " &f" + display + " &8● &e" + Math.max(0, ep.pointsTotal) + " &7điểm");
		}

		if (shown == 0)
			lines.add("&7Không có dữ liệu xếp hạng.");
		lines.add("&6&l┗" + "━".repeat(innerW) + "┛");

		broadcastToParticipants(e, "&eBảng xếp hạng chung cuộc:");
		for (UUID id : new java.util.ArrayList<>(e.participants.keySet())) {
			try {
				if (!isEligible(e, id))
					continue;
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;
				for (String line : lines) {
					Text.tell(p, line);
				}
			} catch (Throwable ignored) {
			}
		}
	}

	// ===================== Opening titles runtime =====================
	private static final class OpeningTitlesController {
		private enum Phase {
			WELCOME_FLYBY,
			INTRO_GAP,
			RACERS,
			OUTRO
		}

		private final BoatRacingPlugin plugin;
		private final EventService eventService;
		private final OpeningTitlesBoardService board;

		private org.bukkit.scheduler.BukkitTask cameraTask;
		private final java.util.Set<Integer> scheduledTaskIds = new java.util.HashSet<>();

		private final java.util.Map<UUID, GameMode> savedModes = new java.util.HashMap<>();
		private final java.util.Map<UUID, Location> savedLocations = new java.util.HashMap<>();
		private final java.util.Map<UUID, GameMode> pendingRestoreModes = new java.util.HashMap<>();
		private final java.util.Map<UUID, Location> pendingRestoreLocations = new java.util.HashMap<>();

		private volatile boolean running = false;
		private volatile UUID featuredRacer;
		private volatile Phase phase;

		private long phaseStartMs;
		private long phaseDurationMs;
		private java.util.List<Location> flybyPoints = java.util.Collections.emptyList();
		private Location flybyCenter;
		private Location fixedCamera;
		private Location stageLocation;

		private int welcomeSeconds;
		private int introGapSeconds;
		private int perRacerSeconds;

		private java.util.List<UUID> racerOrder = java.util.Collections.emptyList();
		private int racerIndex = 0;
		private Runnable onComplete;

		OpeningTitlesController(BoatRacingPlugin plugin, EventService eventService, OpeningTitlesBoardService board) {
			this.plugin = plugin;
			this.eventService = eventService;
			this.board = board;
		}

		boolean isRunning() {
			return running;
		}

		void restorePending(Player p) {
			if (p == null)
				return;
			UUID id = p.getUniqueId();
			if (id == null)
				return;
			GameMode gm = pendingRestoreModes.remove(id);
			Location loc = pendingRestoreLocations.remove(id);
			try {
				if (gm != null)
					p.setGameMode(gm);
			} catch (Throwable ignored) {
				if (gm != null)
					pendingRestoreModes.put(id, gm);
			}
			try {
				if (loc != null && loc.getWorld() != null)
					p.teleport(loc);
			} catch (Throwable ignored) {
				if (loc != null)
					pendingRestoreLocations.put(id, loc);
			}
		}

		void start(RaceEvent e, Runnable onComplete) {
			if (plugin == null || eventService == null)
				return;
			if (e == null)
				return;
			if (running)
				stop(true);

			this.onComplete = onComplete;
			this.racerIndex = 0;
			this.featuredRacer = null;

			loadConfig();
			loadStageAndCamera();
			buildRacerOrder(e);

			// Start MapEngine board if available (optional).
			try {
				if (board != null)
					board.start();
			} catch (Throwable ignored) {
			}
			try {
				if (board != null)
					board.showFavicon();
			} catch (Throwable ignored) {
			}

			running = true;
			startCameraTask();
			beginWelcome(e);
		}

		void stop(boolean restorePlayers) {
			running = false;

			if (cameraTask != null) {
				try {
					cameraTask.cancel();
				} catch (Throwable ignored) {
				}
				cameraTask = null;
			}

			for (Integer id : new java.util.HashSet<>(scheduledTaskIds)) {
				try {
					Bukkit.getScheduler().cancelTask(id);
				} catch (Throwable ignored) {
				}
			}
			scheduledTaskIds.clear();

			try {
				if (board != null) {
					board.setViewers(java.util.Collections.emptySet());
					board.stop();
				}
			} catch (Throwable ignored) {
			}

			if (restorePlayers) {
				restoreAllPlayers();
			} else {
				// Even when not explicitly restoring, keep pending restore safe.
				flushPendingRestoreFromSaved();
			}
		}

		private void loadConfig() {
			welcomeSeconds = clamp(plugin.getConfig().getInt("event.opening-titles.welcome-seconds", 6), 1, 60);
			introGapSeconds = clamp(plugin.getConfig().getInt("event.opening-titles.intro-gap-seconds", 2), 0, 30);
			perRacerSeconds = clamp(plugin.getConfig().getInt("event.opening-titles.per-racer-seconds", 3), 1, 30);
		}

		private void loadStageAndCamera() {
			stageLocation = readLocation("mapengine.opening-titles.stage");
			fixedCamera = readLocation("mapengine.opening-titles.camera");
			if (fixedCamera == null) {
				Player any = firstOnline();
				if (any != null)
					fixedCamera = any.getLocation().clone();
			}
		}

		private Location readLocation(String path) {
			try {
				var sec = plugin.getConfig().getConfigurationSection(path);
				if (sec == null)
					return null;
				String worldName = sec.getString("world", "");
				if (worldName == null || worldName.isBlank())
					return null;
				org.bukkit.World w = Bukkit.getWorld(worldName);
				if (w == null)
					return null;
				double x = sec.getDouble("x", 0.0);
				double y = sec.getDouble("y", 0.0);
				double z = sec.getDouble("z", 0.0);
				float yaw = (float) sec.getDouble("yaw", 0.0);
				float pitch = (float) sec.getDouble("pitch", 0.0);
				return new Location(w, x, y, z, yaw, pitch);
			} catch (Throwable ignored) {
				return null;
			}
		}

		private void buildRacerOrder(RaceEvent e) {
			if (e == null || e.participants == null || e.participants.isEmpty()) {
				racerOrder = java.util.Collections.emptyList();
				return;
			}
			java.util.List<UUID> ids = new java.util.ArrayList<>(e.participants.keySet());
			ids.removeIf(id -> id == null || !eventService.isEligible(e, id));
			java.util.Collections.shuffle(ids);
			racerOrder = ids;
		}

		private void beginWelcome(RaceEvent e) {
			phase = Phase.WELCOME_FLYBY;
			phaseStartMs = System.currentTimeMillis();
			phaseDurationMs = welcomeSeconds * 1000L;
			flybyCenter = guessLobbyCenter();
			flybyPoints = buildFlybyPoints();

			// Audio cue: start of intro.
			try {
				playSoundToAudience(e, Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.7f);
				playSoundToAudience(e, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 1.2f);
			} catch (Throwable ignored) {
			}

			showTitleToAudience(e,
					cfgText("event.opening-titles.text.welcome_title", "Chào mừng!"),
					cfgText("event.opening-titles.text.welcome_subtitle", "%event_title%"),
					welcomeSeconds);
			try {
				if (board != null)
					board.showFavicon();
			} catch (Throwable ignored) {
			}

			scheduleLater(welcomeSeconds * 20L, () -> beginIntroGap(e));
		}

		private void beginIntroGap(RaceEvent e) {
			if (!running)
				return;
			phase = Phase.INTRO_GAP;
			phaseStartMs = System.currentTimeMillis();
			phaseDurationMs = introGapSeconds * 1000L;

			// Audio cue: moving into racer roll call.
			try {
				playSoundToAudience(e, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.1f);
			} catch (Throwable ignored) {
			}

			showTitleToAudience(e,
					cfgText("event.opening-titles.text.racers_intro_title", ""),
					cfgText("event.opening-titles.text.racers_intro_subtitle", "Đây là những gương mặt sẽ tranh tài hôm nay"),
					Math.max(1, introGapSeconds));

			try {
				if (board != null)
					board.showFavicon();
			} catch (Throwable ignored) {
			}

			scheduleLater(Math.max(0, introGapSeconds) * 20L, () -> beginNextRacer(e));
		}

		private void beginNextRacer(RaceEvent e) {
			if (!running)
				return;
			phase = Phase.RACERS;

			Player featured = pickNextFeaturedOnline(e);
			if (featured == null) {
				beginOutro(e);
				return;
			}

			featuredRacer = featured.getUniqueId();
			moveFeaturedToStage(featured);

			String display = buildRacerDisplay(featured.getUniqueId(), featured.getName());
			String title = cfgText("event.opening-titles.text.racer_card_title", "");
			String subtitleTpl = cfgText("event.opening-titles.text.racer_card_subtitle", "%racer_display%");
			String subtitle = subtitleTpl.replace("%racer_display%", display);
			showTitleToAudience(e, title, subtitle, perRacerSeconds);

			try {
				if (board != null)
					board.showRacer(featured.getUniqueId(), featured.getName());
			} catch (Throwable ignored) {
			}

			// Audio + firework cue for each featured racer.
			try {
				playSoundToAudience(e, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.6f);
				playSoundToAudience(e, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.8f);
				Location fx = pickFxLocation(featured);
				if (fx != null)
					spawnFireworkBurst(fx, 1);
			} catch (Throwable ignored) {
			}

			scheduleLater(perRacerSeconds * 20L, () -> endRacerAndContinue(e, featured.getUniqueId()));
		}

		private void endRacerAndContinue(RaceEvent e, UUID racerId) {
			if (!running)
				return;
			try {
				Player p = (racerId != null) ? Bukkit.getPlayer(racerId) : null;
				if (p != null && p.isOnline()) {
					try {
						p.setGameMode(GameMode.SPECTATOR);
					} catch (Throwable ignored) {
					}
					try {
						if (fixedCamera != null && fixedCamera.getWorld() != null)
							p.teleport(fixedCamera);
					} catch (Throwable ignored) {
					}
				}
			} catch (Throwable ignored) {
			}

			featuredRacer = null;
			scheduleLater(1L, () -> beginNextRacer(e));
		}

		private void beginOutro(RaceEvent e) {
			if (!running)
				return;
			phase = Phase.OUTRO;
			featuredRacer = null;
			phaseStartMs = System.currentTimeMillis();
			phaseDurationMs = 2000L;

			// Finale cue.
			try {
				playSoundToAudience(e, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.9f, 1.2f);
				playSoundToAudience(e, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.9f, 1.0f);
				Location fx = pickFxLocation(null);
				if (fx != null)
					spawnFireworkBurst(fx, 3);
			} catch (Throwable ignored) {
			}

			showTitleToAudience(e,
					cfgText("event.opening-titles.text.outro_title", "Bắt đầu thôi!"),
					cfgText("event.opening-titles.text.outro_subtitle", "Đang chuẩn bị chặng đua đầu tiên…"),
					2);
			try {
				if (board != null)
					board.showFavicon();
			} catch (Throwable ignored) {
			}

			scheduleLater(40L, this::finish);
		}

		private void finish() {
			if (!running)
				return;
			running = false;
			if (cameraTask != null) {
				try {
					cameraTask.cancel();
				} catch (Throwable ignored) {
				}
				cameraTask = null;
			}
			for (Integer id : new java.util.HashSet<>(scheduledTaskIds)) {
				try {
					Bukkit.getScheduler().cancelTask(id);
				} catch (Throwable ignored) {
				}
			}
			scheduledTaskIds.clear();

			try {
				if (board != null) {
					board.setViewers(java.util.Collections.emptySet());
					board.stop();
				}
			} catch (Throwable ignored) {
			}

			restoreAllPlayers();
			try {
				if (onComplete != null)
					onComplete.run();
			} catch (Throwable ignored) {
			}
			onComplete = null;
		}

		private void startCameraTask() {
			if (cameraTask != null) {
				try {
					cameraTask.cancel();
				} catch (Throwable ignored) {
				}
				cameraTask = null;
			}
			cameraTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cameraTick, 1L, 1L);
		}

		private void playSoundToAudience(RaceEvent e, Sound sound, float volume, float pitch) {
			if (sound == null)
				return;
			java.util.Set<UUID> audience = collectAudience();
			for (UUID id : audience) {
				try {
					Player p = (id != null) ? Bukkit.getPlayer(id) : null;
					if (p == null || !p.isOnline())
						continue;
					p.playSound(p.getLocation(), sound, volume, pitch);
				} catch (Throwable ignored) {
				}
			}
		}

		private Location pickFxLocation(Player featured) {
			try {
				Location base = null;
				if (stageLocation != null)
					base = stageLocation;
				else if (flybyCenter != null)
					base = flybyCenter;
				else if (fixedCamera != null)
					base = fixedCamera;
				else if (featured != null)
					base = featured.getLocation();
				if (base == null || base.getWorld() == null)
					return null;
				Location out = base.clone().add(0.0, 2.2, 0.0);
				return out;
			} catch (Throwable ignored) {
				return null;
			}
		}

		private void spawnFireworkBurst(Location base, int count) {
			if (plugin == null)
				return;
			if (base == null || base.getWorld() == null)
				return;
			int n = clamp(count, 1, 6);
			for (int i = 0; i < n; i++) {
				try {
					Location spawn = base.clone().add((i - (n - 1) / 2.0) * 0.8, 0.0, 0.0);
					Firework fw = spawn.getWorld().spawn(spawn, Firework.class);
					try {
						RaceFx.markFirework(plugin, fw);
					} catch (Throwable ignored) {
					}
					FireworkMeta meta = fw.getFireworkMeta();
					meta.setPower(0);
					meta.addEffect(org.bukkit.FireworkEffect.builder()
							.with(org.bukkit.FireworkEffect.Type.STAR)
							.withColor(Color.AQUA, Color.LIME, Color.YELLOW)
							.withFade(Color.WHITE)
							.flicker(true)
							.trail(true)
							.build());
					fw.setFireworkMeta(meta);
					Bukkit.getScheduler().runTaskLater(plugin, () -> {
						try {
							if (!fw.isDead())
								fw.detonate();
						} catch (Throwable ignored) {
						}
					}, 2L);
				} catch (Throwable ignored) {
				}
			}
		}

		private void cameraTick() {
			if (!running)
				return;
			if (plugin == null)
				return;

			java.util.Set<UUID> audience = collectAudience();
			try {
				if (board != null)
					board.setViewers(audience);
			} catch (Throwable ignored) {
			}

			Location cam = computeCameraLocation();
			if (cam == null || cam.getWorld() == null)
				return;

			UUID featured = featuredRacer;
			for (UUID id : audience) {
				if (id == null)
					continue;
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;

				// Save original state once.
				savedModes.putIfAbsent(id, safeGameMode(p));
				savedLocations.putIfAbsent(id, safeLocation(p));

				// Featured racer has control: do not lock their camera during showcase.
				if (featured != null && featured.equals(id) && phase == Phase.RACERS)
					continue;

				try {
					if (p.isInsideVehicle())
						p.leaveVehicle();
				} catch (Throwable ignored) {
				}
				try {
					if (p.getGameMode() != GameMode.SPECTATOR)
						p.setGameMode(GameMode.SPECTATOR);
				} catch (Throwable ignored) {
				}
				try {
					p.teleport(cam);
				} catch (Throwable ignored) {
				}
			}
		}

		private java.util.Set<UUID> collectAudience() {
			java.util.Set<UUID> out = new java.util.HashSet<>();
			try {
				if (plugin == null || plugin.getRaceService() == null)
					return out;
				for (Player p : Bukkit.getOnlinePlayers()) {
					if (p == null || !p.isOnline())
						continue;
					UUID id = p.getUniqueId();
					if (id == null)
						continue;
					// Lobby = not in any race.
					if (plugin.getRaceService().findRaceFor(id) != null)
						continue;
					out.add(id);
				}
			} catch (Throwable ignored) {
			}
			return out;
		}

		private Location computeCameraLocation() {
			if (phase == Phase.WELCOME_FLYBY) {
				return sampleFlyby();
			}
			return fixedCamera;
		}

		private Location sampleFlyby() {
			if (flybyPoints == null || flybyPoints.size() < 2)
				return fixedCamera;
			long now = System.currentTimeMillis();
			long dur = Math.max(1L, phaseDurationMs);
			double t = Math.max(0.0, Math.min(1.0, (double) (now - phaseStartMs) / (double) dur));
			int segs = flybyPoints.size() - 1;
			double x = t * segs;
			int i = Math.max(0, Math.min(segs - 1, (int) Math.floor(x)));
			double u = x - i;

			Location a = flybyPoints.get(i);
			Location b = flybyPoints.get(i + 1);
			if (a == null || b == null)
				return fixedCamera;
			if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld()))
				return a.clone();

			double px = a.getX() + (b.getX() - a.getX()) * u;
			double py = a.getY() + (b.getY() - a.getY()) * u;
			double pz = a.getZ() + (b.getZ() - a.getZ()) * u;
			Location out = new Location(a.getWorld(), px, py, pz, a.getYaw(), a.getPitch());

			try {
				if (flybyCenter != null) {
					float[] ang = dev.belikhun.boatracing.cinematic.CinematicCameraService.lookAt(out, flybyCenter);
					out.setYaw(ang[0]);
					out.setPitch(ang[1]);
				}
			} catch (Throwable ignored) {
			}

			return out;
		}

		private Location guessLobbyCenter() {
			Player any = firstOnline();
			if (any != null) {
				try {
					Location spawn = any.getWorld() != null ? any.getWorld().getSpawnLocation() : null;
					if (spawn != null)
						return spawn;
				} catch (Throwable ignored) {
				}
				try {
					return any.getLocation().clone();
				} catch (Throwable ignored) {
				}
			}
			try {
				org.bukkit.World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
				if (w != null)
					return w.getSpawnLocation();
			} catch (Throwable ignored) {
			}
			return null;
		}

		private java.util.List<Location> buildFlybyPoints() {
			Location center = flybyCenter;
			if (center == null || center.getWorld() == null)
				return java.util.Collections.emptyList();
			double radius = plugin.getConfig().getDouble("event.opening-titles.lobby-camera.radius", 16.0);
			double height = plugin.getConfig().getDouble("event.opening-titles.lobby-camera.height", 12.0);
			int points = 4;
			try {
				java.util.List<?> raw = plugin.getConfig().getList("event.opening-titles.lobby-camera.points");
				if (raw != null && !raw.isEmpty()) {
					// If admins filled explicit points, ignore for now (future extension).
				}
			} catch (Throwable ignored) {
			}

			java.util.List<Location> out = new java.util.ArrayList<>();
			for (int i = 0; i <= points; i++) {
				double ang = (Math.PI * 2.0) * ((double) i / (double) points);
				double x = center.getX() + Math.cos(ang) * radius;
				double z = center.getZ() + Math.sin(ang) * radius;
				double y = center.getY() + height;
				out.add(new Location(center.getWorld(), x, y, z, 0.0f, 0.0f));
			}
			return out;
		}

		private Player pickNextFeaturedOnline(RaceEvent e) {
			if (racerOrder == null || racerOrder.isEmpty())
				return null;
			for (; racerIndex < racerOrder.size(); racerIndex++) {
				UUID id = racerOrder.get(racerIndex);
				Player p = (id != null) ? Bukkit.getPlayer(id) : null;
				if (p == null || !p.isOnline())
					continue;
				racerIndex++;
				return p;
			}
			return null;
		}

		private void moveFeaturedToStage(Player p) {
			if (p == null)
				return;
			try {
				savedModes.putIfAbsent(p.getUniqueId(), safeGameMode(p));
				savedLocations.putIfAbsent(p.getUniqueId(), safeLocation(p));
			} catch (Throwable ignored) {
			}
			try {
				if (p.isInsideVehicle())
					p.leaveVehicle();
			} catch (Throwable ignored) {
			}
			try {
				p.setGameMode(GameMode.ADVENTURE);
			} catch (Throwable ignored) {
			}
			try {
				Location st = stageLocation;
				if (st != null && st.getWorld() != null)
					p.teleport(st);
			} catch (Throwable ignored) {
			}
		}

		private void showTitleToAudience(RaceEvent e, String title, String subtitle, int seconds) {
			java.util.Set<UUID> audience = collectAudience();
			String t = expandEventTitle(e, title);
			String sub = expandEventTitle(e, subtitle);
			int fadeIn = 10;
			int fadeOut = 10;
			int stay = Math.max(20, seconds * 20 - fadeIn - fadeOut);
			String tColored = Text.colorize(t);
			String subColored = Text.colorize(sub);
			for (UUID id : audience) {
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;
				try {
					showLegacyTitle(p, tColored, subColored, fadeIn, stay, fadeOut);
				} catch (Throwable ignored) {
				}
			}
		}

		private static void showLegacyTitle(Player p, String titleLegacy, String subtitleLegacy, int fadeInTicks,
				int stayTicks, int fadeOutTicks) {
			if (p == null)
				return;
			try {
				Component title = (titleLegacy == null || titleLegacy.isBlank())
						? Component.empty()
						: TITLE_LEGACY.deserialize(titleLegacy);
				Component subtitle = (subtitleLegacy == null || subtitleLegacy.isBlank())
						? Component.empty()
						: TITLE_LEGACY.deserialize(subtitleLegacy);

				Title.Times times = Title.Times.times(
						Duration.ofMillis(Math.max(0L, fadeInTicks) * 50L),
						Duration.ofMillis(Math.max(0L, stayTicks) * 50L),
						Duration.ofMillis(Math.max(0L, fadeOutTicks) * 50L));

				p.showTitle(Title.title(title, subtitle, times));
			} catch (Throwable ignored) {
			}
		}

		private String cfgText(String path, String def) {
			try {
				String s = plugin.getConfig().getString(path);
				return (s == null) ? def : s;
			} catch (Throwable ignored) {
				return def;
			}
		}

		private String expandEventTitle(RaceEvent e, String raw) {
			String out = raw == null ? "" : raw;
			String title = (e == null) ? "" : eventService.safeName(e.title);
			out = out.replace("%event_title%", title);
			return out;
		}

		private String buildRacerDisplay(UUID id, String name) {
			try {
				var pm = plugin.getProfileManager();
				if (pm != null)
					return pm.formatRacerLegacy(id, name);
			} catch (Throwable ignored) {
			}
			return "&f" + (name == null ? "(không rõ)" : name);
		}

		private void restoreAllPlayers() {
			for (UUID id : new java.util.HashSet<>(savedModes.keySet())) {
				GameMode gm = savedModes.get(id);
				Location loc = savedLocations.get(id);
				Player p = Bukkit.getPlayer(id);
				if (p != null && p.isOnline()) {
					try {
						if (gm != null)
							p.setGameMode(gm);
					} catch (Throwable ignored) {
					}
					try {
						if (loc != null && loc.getWorld() != null)
							p.teleport(loc);
					} catch (Throwable ignored) {
					}
				} else {
					if (gm != null)
						pendingRestoreModes.put(id, gm);
					if (loc != null)
						pendingRestoreLocations.put(id, loc);
				}
			}
			savedModes.clear();
			savedLocations.clear();
		}

		private void flushPendingRestoreFromSaved() {
			for (UUID id : new java.util.HashSet<>(savedModes.keySet())) {
				GameMode gm = savedModes.get(id);
				Location loc = savedLocations.get(id);
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline()) {
					if (gm != null)
						pendingRestoreModes.put(id, gm);
					if (loc != null)
						pendingRestoreLocations.put(id, loc);
				}
			}
			savedModes.clear();
			savedLocations.clear();
		}

		private void scheduleLater(long ticks, Runnable r) {
			if (!running)
				return;
			try {
				int id = Bukkit.getScheduler().runTaskLater(plugin, () -> {
					try {
						if (running)
							r.run();
					} catch (Throwable ignored) {
					}
				}, Math.max(1L, ticks)).getTaskId();
				scheduledTaskIds.add(id);
			} catch (Throwable ignored) {
			}
		}

		private Player firstOnline() {
			try {
				for (Player p : Bukkit.getOnlinePlayers()) {
					if (p != null && p.isOnline())
						return p;
				}
			} catch (Throwable ignored) {
			}
			return null;
		}

		private static GameMode safeGameMode(Player p) {
			try {
				return p.getGameMode();
			} catch (Throwable ignored) {
				return GameMode.SURVIVAL;
			}
		}

		private static Location safeLocation(Player p) {
			try {
				Location l = p.getLocation();
				return l == null ? null : l.clone();
			} catch (Throwable ignored) {
				return null;
			}
		}

		private static int clamp(int v, int min, int max) {
			return Math.max(min, Math.min(max, v));
		}
	}

	private static String shortId(java.util.UUID id) {
		if (id == null)
			return "--------";
		String s = id.toString();
		return s.length() >= 8 ? s.substring(0, 8) : s;
	}

	private java.util.List<Player> collectEligibleOnline(RaceEvent e) {
		java.util.List<Player> out = new java.util.ArrayList<>();
		if (e == null || e.participants == null)
			return out;
		for (var en : e.participants.entrySet()) {
			UUID id = en.getKey();
			if (!isEligible(e, id))
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p != null && p.isOnline())
				out.add(p);
		}
		return out;
	}

	private java.util.List<Player> orderByRegistration(RaceEvent e, java.util.List<Player> online) {
		java.util.List<Player> ordered = new java.util.ArrayList<>();
		java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
		if (e != null && e.registrationOrder != null) {
			for (UUID id : e.registrationOrder) {
				if (id == null)
					continue;
				Player p = Bukkit.getPlayer(id);
				if (p == null || !p.isOnline())
					continue;
				if (!isEligible(e, id))
					continue;
				ordered.add(p);
				seen.add(id);
			}
		}
		for (Player p : online) {
			if (p == null)
				continue;
			if (seen.contains(p.getUniqueId()))
				continue;
			ordered.add(p);
		}
		return ordered;
	}

	private boolean isEligible(RaceEvent e, UUID id) {
		if (e == null || id == null)
			return false;
		if (e.participants == null)
			return false;
		EventParticipant p = e.participants.get(id);
		if (p == null)
			return false;
		return p.status != EventParticipantStatus.LEFT;
	}

	private void broadcastToParticipants(RaceEvent e, String msg) {
		if (e == null || msg == null)
			return;
		if (e.participants == null)
			return;
		for (UUID id : e.participants.keySet()) {
			if (id == null)
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				Text.msg(p, msg);
			} catch (Throwable ignored) {
			}
		}
	}

	private static String safeName(String s) {
		if (s == null)
			return "(không rõ)";
		String t = s.trim();
		return t.isEmpty() ? "(không rõ)" : t;
	}
}
