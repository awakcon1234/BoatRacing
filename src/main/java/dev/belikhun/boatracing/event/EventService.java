package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.storage.EventStorage;
import dev.belikhun.boatracing.integrations.mapengine.EventBoardService;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
	private final EventBoardService eventBoardService;

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
	private static final int INTRO_SECONDS = 30;
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
		this.eventBoardService = new EventBoardService(plugin, this);
		this.activeEventId = null;
		this.activeTrackName = null;
	}

	public void start() {
		loadAll();
		String active = EventStorage.loadActive(dataFolder);
		setActiveEvent(active);
		try {
			if (eventBoardService != null)
				eventBoardService.reloadFromConfig();
		} catch (Throwable ignored) {
		}
		ensureTickTask();
	}

	public PodiumService getPodiumService() {
		return podiumService;
	}

	public EventBoardService getEventBoardService() {
		return eventBoardService;
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
				&& !active.id.equals(e.id)) {
			return false;
		}
		setActiveEvent(e.id);
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
		if (e.state == EventState.CANCELLED || e.state == EventState.COMPLETED)
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

		long now = System.currentTimeMillis();

		// Intro phase: just wait.
		if (introEndMillis > 0L && now < introEndMillis)
			return;

		// After intro ends, gather everyone back to lobby then wait 60s before starting the first track.
		if (introEndMillis > 0L && now >= introEndMillis && !lobbyGatherDone && e.currentTrackIndex == 0) {
			try {
				gatherEligibleToLobby();
			} catch (Throwable ignored) {
			}
			lobbyGatherDone = true;
			lobbyWaitEndMillis = now + (FIRST_TRACK_WAIT_SECONDS * 1000L);
			broadcastToParticipants(e, "&e⌛ Đang chuẩn bị chặng 1. &7Bắt đầu sau &f"
					+ Time.formatCountdownSeconds(FIRST_TRACK_WAIT_SECONDS) + "&7.");
		}

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
			try {
				startTrackCountdownNow(e, rm);
			} catch (Throwable ignored) {
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
		e.startTimeMillis = 0L;
		activeTrackName = e.currentTrackName();
		trackCountdownStarted = false;
		trackDeadlineMillis = 0L;
		currentTrackRoster.clear();
		trackWasRunning = false;
		// Phase schedule
		long now = System.currentTimeMillis();
		introEndMillis = now + (INTRO_SECONDS * 1000L);
		lobbyWaitEndMillis = 0L;
		breakEndMillis = 0L;
		lobbyGatherDone = false;
		EventStorage.saveEvent(dataFolder, e);
		broadcastToParticipants(e, "&eSự kiện &f" + safeName(e.title) + "&e đã bắt đầu!");
		broadcastToParticipants(e, "&7⏳ Đang giới thiệu... bắt đầu sau &f" + Time.formatCountdownSeconds(INTRO_SECONDS)
				+ "&7.");
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

		java.util.List<Player> placed = rm.placeAtStartsWithBoats(ordered);
		if (placed.isEmpty()) {
			broadcastToParticipants(e, "&c❌ Không thể đặt người chơi vào vị trí xuất phát.");
			return;
		}

		currentTrackRoster.clear();
		for (Player p : placed) {
			if (p != null)
				currentTrackRoster.add(p.getUniqueId());
		}

		rm.startLightsCountdown(placed);
		trackCountdownStarted = true;
		trackWasRunning = false;
		trackDeadlineMillis = 0L;
		// Clear pre-start/break timers once a track countdown begins.
		introEndMillis = 0L;
		lobbyWaitEndMillis = 0L;
		breakEndMillis = 0L;
		broadcastToParticipants(e, "&eBắt đầu chặng: &f" + safeName(activeTrackName) + "&e.");
	}

	private void finishTrack(RaceEvent e, RaceManager rm, boolean timedOut) {
		if (e == null || rm == null)
			return;

		if (timedOut)
			broadcastToParticipants(e, "&c⌛ Hết giờ chặng! &7DNF = 0 điểm.");

		awardPointsForTrack(e, rm);

		try {
			rm.stop(true);
		} catch (Throwable ignored) {
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
		lines.add("&6&l┏━━━━━━ &eXẾP HẠNG CHUNG CUỘC &6&l━━━━━━┓");
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
				name = "(không rõ)";
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
		lines.add("&6&l┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛");

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
