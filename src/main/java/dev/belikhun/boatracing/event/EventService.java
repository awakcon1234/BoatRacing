package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.storage.EventStorage;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.util.Text;
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
	private final BoatRacingPlugin plugin;
	private final File dataFolder;
	private final PodiumService podiumService;

	private final Map<String, RaceEvent> eventsById = new HashMap<>();
	private String activeEventId;

	private int tickTaskId = -1;
	private long trackDeadlineMillis = 0L;
	private String activeTrackName;
	private boolean trackCountdownStarted = false;
	private static final int TRACK_SECONDS = 5 * 60;
	private java.util.Set<java.util.UUID> currentTrackRoster = new java.util.LinkedHashSet<>();
	private boolean trackWasRunning = false;

	public EventService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.dataFolder = plugin.getDataFolder();
		this.podiumService = new PodiumService(plugin);
		this.activeEventId = null;
		this.activeTrackName = null;
	}

	public void start() {
		loadAll();
		String active = EventStorage.loadActive(dataFolder);
		setActiveEvent(active);
		ensureTickTask();
	}

	public PodiumService getPodiumService() {
		return podiumService;
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
		return true;
	}

	public synchronized boolean addTrackToActiveEvent(String trackName) {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		if (e.state == EventState.RUNNING)
			return false;
		if (trackName == null || trackName.isBlank())
			return false;
		String tn = trackName.trim();
		if (e.trackPool == null)
			e.trackPool = new java.util.ArrayList<>();
		if (e.trackPool.contains(tn))
			return false;
		e.trackPool.add(tn);
		EventStorage.saveEvent(dataFolder, e);
		return true;
	}

	public synchronized boolean removeTrackFromActiveEvent(String trackName) {
		RaceEvent e = getActiveEvent();
		if (e == null)
			return false;
		if (e.state == EventState.RUNNING)
			return false;
		if (trackName == null || trackName.isBlank())
			return false;
		String tn = trackName.trim();
		if (e.trackPool == null)
			return false;
		boolean ok = e.trackPool.removeIf(s -> s != null && s.equalsIgnoreCase(tn));
		EventStorage.saveEvent(dataFolder, e);
		return ok;
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

		RaceManager rm;
		try {
			rm = plugin.getRaceService().getOrCreate(activeTrackName);
		} catch (Throwable ignored) {
			rm = null;
		}
		if (rm == null)
			return;

		// Start track immediately: skip waiting phase.
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
		EventStorage.saveEvent(dataFolder, e);
		broadcastToParticipants(e, "&eSự kiện &f" + safeName(e.title) + "&e đã bắt đầu!");
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
		broadcastToParticipants(e, "&eChuyển sang chặng tiếp theo: &f" + safeName(activeTrackName));
	}

	private void finishEvent(RaceEvent e, String msg) {
		if (e == null)
			return;
		e.state = EventState.COMPLETED;
		EventStorage.saveEvent(dataFolder, e);
		broadcastToParticipants(e, msg);
		activeTrackName = null;
		trackCountdownStarted = false;
		trackWasRunning = false;
		trackDeadlineMillis = 0L;
		currentTrackRoster.clear();
		try {
			if (podiumService != null)
				podiumService.spawnTop3(e);
		} catch (Throwable ignored) {
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
