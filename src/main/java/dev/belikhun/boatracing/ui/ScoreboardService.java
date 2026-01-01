package dev.belikhun.boatracing.ui;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.EventParticipant;
import dev.belikhun.boatracing.event.EventParticipantStatus;
import dev.belikhun.boatracing.event.EventService;
import dev.belikhun.boatracing.event.EventState;
import dev.belikhun.boatracing.event.RaceEvent;
import dev.belikhun.boatracing.profile.PlayerProfileManager;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.race.RaceService;
import dev.belikhun.boatracing.util.ColorTranslator;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.megavex.scoreboardlibrary.api.ScoreboardLibrary;
import net.megavex.scoreboardlibrary.api.sidebar.Sidebar;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ScoreboardService {
	private final BoatRacingPlugin plugin;
	private final RaceService raceService;
	private final PlayerProfileManager pm;
	private int taskId = -1;
	private int updatePeriodTicks = 5;
	private ScoreboardLibrary lib;
	private volatile boolean debug = false;
	private static final MiniMessage MINI = MiniMessage.miniMessage();

	private static final class PlayerEntry {
		Sidebar sidebar;
		int lastLineCount;
		String lastState;
		boolean hasLastLoc;
		java.util.UUID lastWorldId;
		double lastX;
		double lastY;
		double lastZ;
		long lastLocTimeMs;
		double lastBps;
		int lastPlayerEntityId;
	}

	private static final class TemplateUsage {
		boolean actionbarEnabled;
		boolean needsSpeed;
		boolean needsNeighbors;
		boolean needsEventLobby;
	}

	private TemplateUsage usage = new TemplateUsage();
	private final java.util.Map<java.util.UUID, PlayerEntry> players = new java.util.HashMap<>();
	private final java.util.Set<java.util.UUID> onlineIdsTmp = new java.util.HashSet<>();
	private final java.util.Map<RaceManager, TickContext> ctxByRaceTmp = new java.util.IdentityHashMap<>();
	private final java.util.Map<RaceManager, TickContext> ctxCache = new java.util.WeakHashMap<>();
	private boolean placeholderApiEnabled = false;
	private long placeholderApiNextRefreshMs = 0L;
	private final java.util.LinkedHashMap<java.util.UUID, String> nameCache = new java.util.LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<java.util.UUID, String> eldest) {
			return size() > 512;
		}
	};

	private static final class SpeedColorCfg {
		double yellow;
		double green;
		String low;
		String mid;
		String high;
	}

	private final SpeedColorCfg speedCfgBps = new SpeedColorCfg();
	private final SpeedColorCfg speedCfgKmh = new SpeedColorCfg();
	private final SpeedColorCfg speedCfgBph = new SpeedColorCfg();
	private long speedCfgNextRefreshMs = 0L;

	public ScoreboardService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.raceService = plugin.getRaceService();
		this.pm = plugin.getProfileManager();
	}

	public void start() {
		if (taskId != -1) return;
		try {
			lib = ScoreboardLibrary.loadScoreboardLibrary(plugin);
			log("ScoreboardLibrary loaded: " + (lib != null));
		} catch (Throwable t) {
			plugin.getLogger().warning("ScoreboardLibrary not available: " + t.getMessage());
			lib = null;
		}
		int period = cfgInt("racing.ui.update-ticks", 5);
		period = Math.max(1, period);
		this.updatePeriodTicks = period;
		this.usage = computeTemplateUsage();
		taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, period).getTaskId();
		log("ScoreboardService started. taskId=" + taskId);
		// Always print a one-time info so admins know how to enable debug
		plugin.getLogger().info("[SB] ScoreboardService started (libLoaded=" + (lib != null) + "). Toggle debug: /boatracing sb debug on");
	}

	public void stop() {
		if (taskId != -1) {
			try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
			taskId = -1;
		}
		// Close all sidebars and library
		for (PlayerEntry e : players.values()) {
			try {
				if (e != null && e.sidebar != null)
					e.sidebar.close();
			} catch (Throwable ignored) {
			}
		}
		players.clear();
		try { if (lib != null) lib.close(); } catch (Throwable ignored) {}
		log("ScoreboardService stopped and cleaned up.");
	}

	public void restart() {
		stop();
		start();
	}

	public void forceTick() { tick(); }

	public void setDebug(boolean enabled) {
		this.debug = enabled;
		log("Debug set to " + enabled);
	}

	private void tick() {
		long nowMs = System.currentTimeMillis();
		if (raceService == null) {
			log("tick: raceService=null, waiting for initialization");
			return;
		}
		log("tick: online=" + Bukkit.getOnlinePlayers().size() + ", libLoaded=" + (lib != null));
		refreshPlaceholderApiEnabledIfNeeded(nowMs);
		ctxByRaceTmp.clear();

		java.util.Set<java.util.UUID> onlineIds = null;
		if (!players.isEmpty()) {
			onlineIdsTmp.clear();
			for (Player p : Bukkit.getOnlinePlayers())
				onlineIdsTmp.add(p.getUniqueId());
			onlineIds = onlineIdsTmp;
		}

		for (Player p : Bukkit.getOnlinePlayers()) {
			try {
				RaceManager rm = raceService.findRaceFor(p.getUniqueId());
				if (rm == null) {
					// Event-lobby scoreboard (participants in the active event who are not currently in a race)
					if (usage != null && usage.needsEventLobby) {
						RaceEvent active = null;
						try {
							EventService es = plugin != null ? plugin.getEventService() : null;
							active = (es != null ? es.getActiveEvent() : null);
						} catch (Throwable ignored) {
							active = null;
						}
						if (active != null && isEventParticipant(active, p.getUniqueId())) {
							if (active.state == EventState.DISABLED) {
								setState(p, "LOBBY");
								applyLobbyBoard(p);
								clearActionBar(p);
								continue;
							}
							setState(p, "EVENT");
							applyEventLobbyBoard(p, active);
							clearActionBar(p);
							continue;
						}
					}
					setState(p, "LOBBY");
					applyLobbyBoard(p);
					clearActionBar(p);
					continue;
				}
				TickContext ctx = ctxByRaceTmp.get(rm);
				if (ctx == null) {
					ctx = ctxCache.get(rm);
					if (ctx == null) {
						ctx = new TickContext();
						ctxCache.put(rm, ctx);
					}
					refreshTickContext(ctx, rm);
					ctxByRaceTmp.put(rm, ctx);
				}

				if (usage != null && usage.needsSpeed) {
					updateSpeedCache(p, nowMs);
				}

				updateFor(p, rm, ctx);
			} catch (Throwable ignored) {}
		}

		// Prevent memory growth when players disconnect/reconnect.
		if (onlineIds != null)
			pruneOfflineCaches(onlineIds);
	}

	private void refreshPlaceholderApiEnabledIfNeeded(long nowMs) {
		if (nowMs < placeholderApiNextRefreshMs)
			return;
		placeholderApiNextRefreshMs = nowMs + 5000L;
		boolean enabled = false;
		try {
			enabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
		} catch (Throwable ignored) {
			enabled = false;
		}
		placeholderApiEnabled = enabled;
	}

	private static boolean isEventParticipant(RaceEvent e, java.util.UUID playerId) {
		if (e == null || playerId == null)
			return false;
		try {
			if (e.participants == null)
				return false;
			EventParticipant ep = e.participants.get(playerId);
			if (ep == null)
				return false;
			return ep.status != null && ep.status != EventParticipantStatus.LEFT;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static final class EventRankEntry {
		final java.util.UUID id;
		final String name;
		final int points;
		int position;

		EventRankEntry(java.util.UUID id, String name, int points) {
			this.id = id;
			this.name = name;
			this.points = points;
		}
	}

	private static String eventStateDisplay(EventState st) {
		if (st == null)
			return "-";
		return switch (st) {
			case DRAFT -> "<gray>Nháp</gray>";
			case REGISTRATION -> "<green>Đang mở đăng ký</green>";
			case RUNNING -> "<aqua>Đang diễn ra</aqua>";
			case COMPLETED -> "<gold>Đã kết thúc</gold>";
			case DISABLED -> "<gray>Đã tắt</gray>";
			case CANCELLED -> "<red>Đã hủy</red>";
		};
	}

	private void applyEventLobbyBoard(Player p, RaceEvent e) {
		Sidebar sb = ensureSidebar(p);
		if (sb == null || p == null || e == null)
			return;

		String eventCountdownLabel = "-";
		String eventCountdown = "-";
		String eventCountdownDisplay = "-";
		try {
			EventService es = plugin != null ? plugin.getEventService() : null;
			if (es != null) {
				long now = System.currentTimeMillis();
				long end = 0L;
				String label = null;

				// Scheduled event start time (registration phase).
				try {
					if ((e.state == EventState.REGISTRATION || e.state == EventState.DRAFT)
							&& e.startTimeMillis > 0L && now < e.startTimeMillis) {
						end = e.startTimeMillis;
						label = "Bắt đầu sự kiện";
					}
				} catch (Throwable ignored) {
				}

				long introEnd = 0L;
				long lobbyEnd = 0L;
				long breakEnd = 0L;
				long trackDeadline = 0L;
				try {
					introEnd = es.getIntroEndMillis();
					lobbyEnd = es.getLobbyWaitEndMillis();
					breakEnd = es.getBreakEndMillis();
					trackDeadline = es.getTrackDeadlineMillis();
				} catch (Throwable ignored) {
					introEnd = 0L;
					lobbyEnd = 0L;
					breakEnd = 0L;
					trackDeadline = 0L;
				}

				if (label == null && introEnd > 0L && now < introEnd) {
					end = introEnd;
					label = "Bắt đầu";
				} else if (label == null && lobbyEnd > 0L && now < lobbyEnd) {
					end = lobbyEnd;
					label = "Bắt đầu";
				} else if (label == null && breakEnd > 0L && now < breakEnd) {
					end = breakEnd;
					label = "Chặng tiếp theo";
				} else if (label == null && trackDeadline > 0L && now < trackDeadline) {
					end = trackDeadline;
					label = "Hết giờ";
				}

				if (end > now && label != null) {
					int seconds = (int) Math.ceil((end - now) / 1000.0);
					seconds = Math.max(0, seconds);
					eventCountdownLabel = label;
					eventCountdown = Time.formatCountdownSeconds(seconds);
					eventCountdownDisplay = "<gray>⌛ " + label + ": <white>" + eventCountdown;
				}
			}
		} catch (Throwable ignored) {
			eventCountdownLabel = "-";
			eventCountdown = "-";
			eventCountdownDisplay = "-";
		}

		// Build ranking snapshot (points DESC)
		java.util.List<EventRankEntry> ranking = new java.util.ArrayList<>();
		try {
			if (e.participants != null) {
				for (var en : e.participants.entrySet()) {
					java.util.UUID id = en.getKey();
					EventParticipant ep = en.getValue();
					if (id == null || ep == null)
						continue;
					if (ep.status == EventParticipantStatus.LEFT)
						continue;
					String n = (ep.nameSnapshot == null || ep.nameSnapshot.isBlank()) ? nameOfCached(id) : ep.nameSnapshot;
					ranking.add(new EventRankEntry(id, n, ep.pointsTotal));
				}
			}
		} catch (Throwable ignored) {
			// keep empty
		}

		ranking.sort((a, b) -> {
			int pa = a == null ? 0 : a.points;
			int pb = b == null ? 0 : b.points;
			int c = Integer.compare(pb, pa);
			if (c != 0)
				return c;
			String na = a == null || a.name == null ? "" : a.name;
			String nb = b == null || b.name == null ? "" : b.name;
			c = na.compareToIgnoreCase(nb);
			if (c != 0)
				return c;
			java.util.UUID ua = a == null ? null : a.id;
			java.util.UUID ub = b == null ? null : b.id;
			if (ua == null && ub == null)
				return 0;
			if (ua == null)
				return 1;
			if (ub == null)
				return -1;
			return ua.compareTo(ub);
		});

		for (int i = 0; i < ranking.size(); i++) {
			EventRankEntry it = ranking.get(i);
			if (it != null)
				it.position = i + 1;
		}

		int viewerPoints = 0;
		int viewerPos = 0;
		try {
			if (e.participants != null) {
				EventParticipant self = e.participants.get(p.getUniqueId());
				if (self != null)
					viewerPoints = Math.max(0, self.pointsTotal);
			}
		} catch (Throwable ignored) {
			viewerPoints = 0;
		}
		for (EventRankEntry it : ranking) {
			if (it != null && p.getUniqueId().equals(it.id)) {
				viewerPos = it.position;
				break;
			}
		}

		PlayerProfileManager.Profile prof = pm != null ? pm.get(p.getUniqueId()) : null;
		java.util.Map<String, String> ph = new java.util.HashMap<>();
		ph.put("racer_name", p.getName());
		ph.put("racer_display", racerDisplay(p.getUniqueId(), p.getName()));
		ph.put("racer_color", prof != null ? ColorTranslator.miniColorTag(prof.color) : "<white>");
		ph.put("icon", prof != null && !empty(prof.icon) ? prof.icon : "-");
		ph.put("number", prof != null && prof.number > 0 ? String.valueOf(prof.number) : "-");
		ph.put("completed", prof != null ? String.valueOf(prof.completed) : "0");
		ph.put("wins", prof != null ? String.valueOf(prof.wins) : "0");
		ph.put("time_raced", prof != null ? Time.formatDurationShort(prof.timeRacedMillis) : "-");

		String eventId = (e.id == null || e.id.isBlank()) ? "-" : e.id;
		String eventTitle = (e.title == null || e.title.isBlank()) ? "(không rõ)" : e.title;
		String stateText = eventStateDisplay(e.state);
		ph.put("event_id", eventId);
		ph.put("event_title", eventTitle);
		ph.put("event_state", e.state == null ? "-" : e.state.name());
		ph.put("event_state_display", stateText);
		ph.put("event_points", String.valueOf(Math.max(0, viewerPoints)));
		ph.put("event_position", viewerPos > 0 ? String.valueOf(viewerPos) : "-");
		ph.put("event_position_tag", viewerPos > 0 ? colorizePlacementTag(viewerPos) : "<gray>-</gray>");
		ph.put("event_participants", String.valueOf(ranking.size()));
		String participantsMax = "-";
		try {
			int max = e != null ? Math.max(0, e.maxParticipants) : 0;
			if (max > 0)
				participantsMax = String.valueOf(max);
		} catch (Throwable ignored) {
			participantsMax = "-";
		}
		ph.put("event_participants_max", participantsMax);
		ph.put("event_countdown_label", eventCountdownLabel);
		ph.put("event_countdown", eventCountdown);
		ph.put("event_countdown_display", eventCountdownDisplay);
		ph.put("event_track_total", String.valueOf(e.trackPool == null ? 0 : e.trackPool.size()));
		ph.put("event_track_index", String.valueOf(Math.max(0, e.currentTrackIndex) + 1));
		ph.put("event_track_name", safeStr(e.currentTrackName()));

		Component title = parse(p, cfgString("racing.ui.templates.event_lobby.title", "<gold>Sự kiện"), ph);

		java.util.List<String> headerTpl = cfgStringList("racing.ui.templates.event_lobby.header", java.util.List.of());
		java.util.List<String> itemTpl = cfgStringList("racing.ui.templates.event_lobby.ranking_item", java.util.List.of());
		java.util.List<String> footerTpl = cfgStringList("racing.ui.templates.event_lobby.footer", java.util.List.of());

		java.util.List<Component> out = new java.util.ArrayList<>();
		out.addAll(parseLines(p, headerTpl, ph));

		int maxLines = 15;
		try {
			maxLines = sb.maxLines();
		} catch (Throwable ignored) {
			maxLines = 15;
		}
		int headerLines = out.size();
		int footerLines = footerTpl == null ? 0 : footerTpl.size();
		int itemLines = Math.max(1, itemTpl == null ? 0 : itemTpl.size());
		int availableForItems = Math.max(0, maxLines - headerLines - footerLines);
		int maxItems = itemLines <= 0 ? 0 : (availableForItems / itemLines);
		if (maxItems < 0)
			maxItems = 0;

		int shown = 0;
		for (EventRankEntry it : ranking) {
			if (it == null)
				continue;
			if (maxItems > 0 && shown >= maxItems)
				break;

			java.util.Map<String, String> iph = new java.util.HashMap<>(ph);
			iph.put("rank_position", String.valueOf(it.position));
			iph.put("rank_position_tag", colorizePlacementTag(it.position));
			iph.put("rank_points", String.valueOf(Math.max(0, it.points)));
			iph.put("rank_racer_name", it.name == null ? "(không rõ)" : it.name);
			iph.put("rank_racer_display", racerDisplay(it.id, it.name));

			out.addAll(parseLines(p, itemTpl, iph));
			shown++;
		}

		out.addAll(parseLines(p, footerTpl, ph));
		applySidebarComponents(p, sb, title, out);
	}

	private static String safeStr(String s) {
		if (s == null)
			return "-";
		String t = s.trim();
		return t.isEmpty() ? "-" : t;
	}

	private void pruneOfflineCaches(java.util.Set<java.util.UUID> onlineIds) {
		final java.util.Set<java.util.UUID> online = (onlineIds == null) ? java.util.Set.of() : onlineIds;
		java.util.Iterator<java.util.Map.Entry<java.util.UUID, PlayerEntry>> it = players.entrySet().iterator();
		while (it.hasNext()) {
			java.util.Map.Entry<java.util.UUID, PlayerEntry> ent = it.next();
			java.util.UUID id = ent.getKey();
			if (online.contains(id))
				continue;
			PlayerEntry e = ent.getValue();
			try {
				if (e != null && e.sidebar != null)
					e.sidebar.close();
			} catch (Throwable ignored) {
			}
			it.remove();
		}
	}

	private void refreshTickContext(TickContext ctx, RaceManager rm) {
		if (ctx == null)
			return;
		if (rm == null) {
			ctx.running = false;
			ctx.registering = false;
			ctx.countdown = false;
			ctx.anyFinished = false;
			ctx.allFinished = false;
			ctx.liveOrder = java.util.List.of();
			ctx.positionById.clear();
			ctx.standings = null;
			return;
		}

		boolean running = rm.isRunning();
		boolean registering = rm.isRegistering();
		boolean countdown = rm.isAnyCountdownActive();
		ctx.running = running;
		ctx.registering = registering;
		ctx.countdown = countdown;

		if (running) {
			ctx.liveOrder = rm.getLiveOrder();
			ctx.positionById.clear();
			for (int i = 0; i < ctx.liveOrder.size(); i++)
				ctx.positionById.put(ctx.liveOrder.get(i), i + 1);
		} else {
			ctx.liveOrder = java.util.List.of();
			ctx.positionById.clear();
		}

		ctx.standings = null;
		if (!registering) {
			ctx.anyFinished = rm.hasAnyFinishedParticipant();
			ctx.allFinished = rm.areAllParticipantsFinished();
		} else {
			ctx.anyFinished = false;
			ctx.allFinished = false;
		}
	}

	private String safeTrackName(UUID playerId) {
		try {
			if (raceService == null) return "(unknown)";
			String tn = raceService.findTrackNameFor(playerId);
			return (tn == null || tn.isBlank()) ? "(unknown)" : tn;
		} catch (Throwable ignored) {}
		return "(unknown)";
	}

	private void updateFor(Player p, RaceManager rm, TickContext ctx) {
		if (ctx.registering) {
			setState(p, "WAITING");
			applyWaitingBoard(p, rm, safeTrackName(p.getUniqueId()));
			applyActionBarForWaiting(p, rm);
			return;
		}

		if (ctx.countdown) {
			setState(p, "COUNTDOWN");
			applyCountdownBoard(p, rm, safeTrackName(p.getUniqueId()));
			// Reuse waiting actionbar template for countdown.
			applyActionBarForWaiting(p, rm);
			return;
		}

		var st = rm.getParticipantState(p.getUniqueId());

		// Race ended: everybody finished -> show full results list for everyone.
		if (ctx.allFinished) {
			setState(p, "COMPLETED");
			applyCompletedBoard(p, rm, ctx);
			if (st != null && st.finished) applyActionBarForCompleted(p, rm, st);
			else clearActionBar(p);
			return;
		}

		if (ctx.running) {
			if (st != null && st.finished) {
				setState(p, "COMPLETED");
				applyCompletedBoard(p, rm, ctx);
				applyActionBarForCompleted(p, rm, st);
				return;
			}
			setState(p, "RACING");
			applyRacingBoard(p, rm, ctx, safeTrackName(p.getUniqueId()));
			applyActionBarForRacing(p, rm, ctx);
			return;
		}

		if (ctx.anyFinished) {
			setState(p, "COMPLETED");
			applyCompletedBoard(p, rm, ctx);
			if (st != null && st.finished) applyActionBarForCompleted(p, rm, st);
			else clearActionBar(p);
			return;
		}
		setState(p, "LOBBY");
		applyLobbyBoard(p); clearActionBar(p);
	}

	private void applyCountdownBoard(Player p, RaceManager rm, String trackName) {
		Sidebar sb = ensureSidebar(p);
		String track = (trackName != null && !trackName.isBlank()) ? trackName : "(unknown)";
		int joined = rm.getRegistered().size();
		int max = rm.getTrackConfig().getStarts().size();
		int laps = rm.getTotalLaps();
		double trackLength = -1.0;
		try { trackLength = rm.getTrackConfig().getTrackLength(); } catch (Throwable ignored) { trackLength = -1.0; }
		int cps = 0;
		try { cps = rm.getTrackConfig().getCheckpoints().size(); } catch (Throwable ignored) { cps = 0; }
		PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
		java.util.Map<String,String> ph = new java.util.HashMap<>();
		ph.put("racer_name", p.getName()); ph.put("racer_color", ColorTranslator.miniColorTag(prof.color)); ph.put("icon", empty(prof.icon)?"-":prof.icon);
		ph.put("racer_display", racerDisplay(p.getUniqueId(), p.getName()));
		ph.put("number", prof.number>0?String.valueOf(prof.number):"-"); ph.put("track", track); ph.put("laps", String.valueOf(laps));
		ph.put("joined", String.valueOf(joined)); ph.put("max", String.valueOf(max));
		ph.put("checkpoint_total", String.valueOf(cps));
		ph.put("track_length", trackLength > 0.0 && Double.isFinite(trackLength) ? fmt1(trackLength) : "-");
		ph.put("track_length_unit", "m");
		ph.put("track_length_display", trackLength > 0.0 && Double.isFinite(trackLength) ? ("🛣 " + fmt1(trackLength) + "m") : "-");
		ph.put("countdown", Time.formatCountdownSeconds(rm.getCountdownRemainingSeconds()));

		Component title = parse(p, cfgString("racing.ui.templates.countdown.title", "<gold>Chuẩn bị"), ph);
		java.util.List<Component> lines = parseLines(p, cfgStringList("racing.ui.templates.countdown.lines", java.util.List.of()), ph);
		if (lines.isEmpty()) {
			lines = parseLines(p, java.util.List.of(
				"<yellow>Vào vị trí xuất phát",
				"<gray>Đường: <white>%track%",
				"<gray>Vòng: <white>%laps%",
				"<gray>Người chơi: <white>%joined%/%max%",
				"<gray>Điểm kiểm tra: <white>%checkpoint_total%",
				"",
				"<yellow>Đếm ngược",
				"<gray>Bắt đầu trong: <white>%countdown%"), ph);
		}
		applySidebarComponents(p, sb, title, lines);
	}

	private void applyActionBarForCompleted(Player p, RaceManager rm, RaceManager.ParticipantState st) {
		if (usage == null || !usage.actionbarEnabled) return;
		if (st == null || !st.finished) return;

		int pos = st.finishPosition > 0 ? st.finishPosition : 1;
		long t = Math.max(0L, st.finishTimeMillis - rm.getRaceStartMillis()) + st.penaltySeconds * 1000L;

		double avgBps = 0.0;
		try {
			double seconds = Math.max(0.001, t / 1000.0);
			avgBps = Math.max(0.0, st.distanceBlocks / seconds);
		} catch (Throwable ignored) {}

		// Per-player preferred unit overrides global
		String unitPref = pm != null ? pm.get(p.getUniqueId()).speedUnit : "";
		String unit = (unitPref != null && !unitPref.isEmpty()) ? unitPref.toLowerCase() : cfgString("scoreboard.speed.unit", "kmh").toLowerCase();

		String speedVal;
		String speedUnit;
		if ("bps".equals(unit)) { speedVal = fmt2(avgBps); speedUnit = "bps"; }
		else if ("bph".equals(unit)) { speedVal = fmt2(avgBps * 3600.0); speedUnit = "bph"; }
		else { speedVal = fmt2(avgBps * 3.6); speedUnit = "km/h"; unit = "kmh"; }
		String speedColorName = resolveSpeedColorByUnit(avgBps, unit);
		String avgSpeedDisplay = miniWrapTag(speedColorName, speedVal + " " + speedUnit);

		String tpl = cfgString(
				"racing.ui.templates.actionbar.completed",
			"%finish_pos_tag% %racer_display% <gray>●</gray> <white>%finish_time%</white> <gray>●</gray> %avg_speed_display%"
		);
		java.util.Map<String,String> ph = new java.util.HashMap<>();
		ph.put("racer_name", p.getName());
		ph.put("racer_display", racerDisplay(p.getUniqueId(), p.getName()));
		ph.put("finish_pos", colorizePlacement(pos));
		ph.put("finish_pos_tag", colorizePlacementTag(pos));
		ph.put("finish_time", Time.formatStopwatchMillis(t));
		ph.put("avg_speed", speedVal);
		ph.put("speed_unit", speedUnit);
		ph.put("avg_speed_display", avgSpeedDisplay);
		// %speed_color% now returns a full MiniMessage tag, e.g. "<red>".
		ph.put("speed_color_name", speedColorName);
		ph.put("speed_color", miniOpenTag(speedColorName));
		ph.put("speed_color_close", miniCloseTag(speedColorName));

		Component c = parse(p, tpl, ph);
		sendActionBar(p, c);
	}

	private void applyLobbyBoard(Player p) {
		Sidebar sb = ensureSidebar(p);
		if (sb == null) {
			return;
		}
		PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
		java.util.Map<String,String> ph = new java.util.HashMap<>();
		ph.put("racer_name", p.getName()); ph.put("racer_color", ColorTranslator.miniColorTag(prof.color)); ph.put("icon", empty(prof.icon)?"-":prof.icon);
		ph.put("racer_display", racerDisplay(p.getUniqueId(), p.getName()));
		ph.put("number", prof.number>0?String.valueOf(prof.number):"-"); ph.put("completed", String.valueOf(prof.completed)); ph.put("wins", String.valueOf(prof.wins));
		ph.put("time_raced", Time.formatDurationShort(prof.timeRacedMillis));
		ph.put("track_length", "-");
		ph.put("track_length_unit", "m");
		ph.put("track_length_display", "-");

		// Allow internal placeholders in the scoreboard title too.
		Component title = parse(p, cfgString("racing.ui.templates.lobby.title", "<gold>BoatRacing"), ph);
		java.util.List<Component> lines = parseLines(p, cfgStringList("racing.ui.templates.lobby.lines", java.util.List.of(
			"<yellow>Hồ sơ của bạn",
			"<gray>Tên: %racer_color%%racer_name%",
			"<gray>Màu: <white>%racer_color%",
			"<gray>Biểu tượng: <white>%icon%",
			"<gray>Số đua: <white>%number%",
			"<yellow>Thành tích",
			"<gray>Hoàn thành: <white>%completed%",
			"<gray>Chiến thắng: <white>%wins%",
			"<gray>Thời gian đã đua: <white>%time_raced%")), ph);
		applySidebarComponents(p, sb, title, lines);
	}

	private void applyWaitingBoard(Player p, RaceManager rm, String trackName) {
		Sidebar sb = ensureSidebar(p);
		if (sb == null) {
			return;
		}
		String track = (trackName != null && !trackName.isBlank()) ? trackName : "(unknown)";
		int joined = rm.getRegistered().size();
		int max = rm.getTrackConfig().getStarts().size();
		int laps = rm.getTotalLaps();
		double trackLength = -1.0;
		try { trackLength = rm.getTrackConfig().getTrackLength(); } catch (Throwable ignored) { trackLength = -1.0; }
		int cps = 0;
		try { cps = rm.getTrackConfig().getCheckpoints().size(); } catch (Throwable ignored) { cps = 0; }
		PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
		java.util.Map<String,String> ph = new java.util.HashMap<>();
		ph.put("racer_name", p.getName()); ph.put("racer_color", ColorTranslator.miniColorTag(prof.color)); ph.put("icon", empty(prof.icon)?"-":prof.icon);
		ph.put("racer_display", racerDisplay(p.getUniqueId(), p.getName()));
		ph.put("number", prof.number>0?String.valueOf(prof.number):"-"); ph.put("track", track); ph.put("laps", String.valueOf(laps));
		ph.put("joined", String.valueOf(joined)); ph.put("max", String.valueOf(max));
		ph.put("checkpoint_total", String.valueOf(cps));
		ph.put("track_length", trackLength > 0.0 && Double.isFinite(trackLength) ? fmt1(trackLength) : "-");
		ph.put("track_length_unit", "m");
		ph.put("track_length_display", trackLength > 0.0 && Double.isFinite(trackLength) ? ("🛣 " + fmt1(trackLength) + "m") : "-");
		ph.put("countdown", Time.formatCountdownSeconds(rm.getCountdownRemainingSeconds()));

		// Additional track info placeholders (metadata + structure)
		try {
			var tc = rm.getTrackConfig();
			int starts = 0;
			int lights = 0;
			int centerline = 0;
			boolean hasFinish = false;
			boolean hasBounds = false;
			boolean hasWaitSpawn = false;
			boolean ready = false;
			java.util.List<String> missing = java.util.List.of();

			try { starts = tc.getStarts().size(); } catch (Throwable ignored) { starts = 0; }
			try { lights = tc.getLights().size(); } catch (Throwable ignored) { lights = 0; }
			try { centerline = tc.getCenterline().size(); } catch (Throwable ignored) { centerline = 0; }
			try { hasFinish = tc.getFinish() != null; } catch (Throwable ignored) { hasFinish = false; }
			try { hasBounds = tc.getBounds() != null; } catch (Throwable ignored) { hasBounds = false; }
			try { hasWaitSpawn = tc.getWaitingSpawn() != null; } catch (Throwable ignored) { hasWaitSpawn = false; }
			try { ready = tc.isReady(); } catch (Throwable ignored) { ready = false; }
			try {
				java.util.List<String> m = tc.missingRequirements();
				missing = (m == null) ? java.util.List.of() : m;
			} catch (Throwable ignored) {
				missing = java.util.List.of();
			}

			ph.put("track_world", (tc.getWorldName() == null || tc.getWorldName().isBlank()) ? "-" : tc.getWorldName());
			ph.put("track_ready", String.valueOf(ready));
			ph.put("track_ready_display", ready ? "<green>Sẵn sàng</green>" : "<red>Chưa sẵn sàng</red>");
			ph.put("track_missing", missing.isEmpty() ? "-" : String.join(", ", missing));
			ph.put("track_starts", String.valueOf(starts));
			ph.put("track_lights", String.valueOf(lights));
			ph.put("track_checkpoints", String.valueOf(cps));
			ph.put("track_centerline_nodes", String.valueOf(centerline));
			ph.put("track_has_finish", String.valueOf(hasFinish));
			ph.put("track_has_bounds", String.valueOf(hasBounds));
			ph.put("track_has_waiting_spawn", String.valueOf(hasWaitSpawn));
			ph.put("track_has_finish_display", hasFinish ? "có" : "không");
			ph.put("track_has_bounds_display", hasBounds ? "có" : "không");
			ph.put("track_has_waiting_spawn_display", hasWaitSpawn ? "có" : "không");

			// Track author (from TrackConfig)
			String authorName = "-";
			String authorDisplay = "<gray>-</gray>";
			try {
				java.util.UUID aid = tc.getAuthorId();
				String an = tc.getAuthorName();
				String at = tc.getAuthorText();
				if (aid != null) {
					String n = (an == null || an.isBlank()) ? "(không rõ)" : an;
					authorName = n;
					authorDisplay = racerDisplay(aid, n);
				} else if (at != null && !at.isBlank()) {
					authorName = at;
					authorDisplay = "<white>" + at;
				}
			} catch (Throwable ignored) {
				authorName = "-";
				authorDisplay = "<gray>-</gray>";
			}
			ph.put("track_author_name", authorName);
			ph.put("track_author_display", authorDisplay);

			// Track icon material (from TrackConfig)
			String iconMat = "-";
			try {
				org.bukkit.inventory.ItemStack icon = tc.getIcon();
				if (icon != null && icon.getType() != null && icon.getType() != org.bukkit.Material.AIR) {
					iconMat = icon.getType().name();
				}
			} catch (Throwable ignored) {
				iconMat = "-";
			}
			ph.put("track_icon_material", iconMat);
		} catch (Throwable ignored) {
			// Keep placeholders absent on failure; templates can still render without them.
		}

		// Records (track/global + personal)
		String trTime = "-";
		String trHolder = "-";
		try {
			var m = plugin.getTrackRecordManager();
			var r = (m != null ? m.get(track) : null);
			if (r != null && r.bestTimeMillis > 0L) {
				trTime = Time.formatStopwatchMillis(r.bestTimeMillis);
				String hn = (r.holderName == null || r.holderName.isBlank()) ? "(không rõ)" : r.holderName;
				trHolder = racerDisplay(r.holderId, hn);
			}
		} catch (Throwable ignored) {}
		ph.put("track_record_time", trTime);
		ph.put("track_record_holder", trHolder);

		String pbTime = "-";
		try {
			if (pm != null) {
				long ms = pm.getPersonalBestMillis(p.getUniqueId(), track);
				if (ms > 0L) pbTime = Time.formatStopwatchMillis(ms);
			}
		} catch (Throwable ignored) {}
		ph.put("personal_record_time", pbTime);

		// Allow internal placeholders in the scoreboard title too.
		Component title = parse(p, cfgString("racing.ui.templates.waiting.title", "<gold>Đang chờ"), ph);
		java.util.List<Component> lines = parseLines(p, cfgStringList("racing.ui.templates.waiting.lines", java.util.List.of()), ph);
		if (lines.isEmpty()) {
			lines = parseLines(p, java.util.List.of(
				"<yellow>Thông tin đường",
				"<gray>Đường: <white>%track%",
				"<gray>Vòng: <white>%laps%",
				"<gray>Người chơi: <white>%joined%/%max%",
				"<gray>Điểm kiểm tra: <white>%checkpoint_total%",
				"<gray>⌚ Kỷ lục: <white>%track_record_time%</white> <gray>bởi</gray> %track_record_holder%",
				"<gray>⌚ Kỷ lục cá nhân: <white>%personal_record_time%",
					"<yellow>Tay đua",
				"<gray>Tên: %racer_color%%racer_name%",
				"<gray>Màu: <white>%racer_color%",
				"<gray>Biểu tượng: <white>%icon%",
				"<gray>Số đua: <white>%number%",
					"<gray>Bắt đầu: <white>%countdown%"), ph);
		}
		applySidebarComponents(p, sb, title, lines);
	}

	private void applyRacingBoard(Player p, RaceManager rm, TickContext ctx, String trackName) {
		Sidebar sb = ensureSidebar(p);
		if (sb == null) {
			return;
		}
		String track = (trackName != null && !trackName.isBlank()) ? trackName : "(unknown)";
		int laps = rm.getTotalLaps();
		long ms = rm.getRaceElapsedMillis();
		java.util.Map<String,String> ph = new java.util.HashMap<>();
		ph.put("track", track); ph.put("timer", Time.formatStopwatchMillis(ms));
		ph.put("racer_display", racerDisplay(p.getUniqueId(), p.getName()));
		double trackLength = -1.0;
		try { trackLength = rm.getTrackConfig().getTrackLength(); } catch (Throwable ignored) { trackLength = -1.0; }
		ph.put("track_length", trackLength > 0.0 && Double.isFinite(trackLength) ? fmt1(trackLength) : "-");
		ph.put("track_length_unit", "m");
		ph.put("track_length_display", trackLength > 0.0 && Double.isFinite(trackLength) ? ("🛣 " + fmt1(trackLength) + "m") : "-");
		var st = rm.getParticipantState(p.getUniqueId());
		java.util.List<Component> lines;
		if (st != null) {
			int pos = Math.max(1, ctx.positionById.getOrDefault(p.getUniqueId(), 1));
			double lapProgressPct = rm.getLapProgressRatio(p.getUniqueId()) * 100.0;
			double trackProgressPct;
			if (st.finished) {
				trackProgressPct = 100.0;
			} else {
				double lapRatio = Math.max(0.0, Math.min(1.0, rm.getLapProgressRatio(p.getUniqueId())));
				trackProgressPct = ((double) st.currentLap + lapRatio) / (double) Math.max(1, laps) * 100.0;
				trackProgressPct = Math.max(0.0, Math.min(100.0, trackProgressPct));
			}
			int lapCurrent = st.finished ? laps : Math.min(laps, st.currentLap + 1);
			ph.put("lap_current", String.valueOf(lapCurrent));
			ph.put("lap_total", String.valueOf(laps));
			ph.put("position", colorizePlacement(pos));
			ph.put("position_tag", colorizePlacementTag(pos));
			ph.put("joined", String.valueOf(ctx.liveOrder.size()));
			ph.put("lap_progress", fmt2(lapProgressPct));
			ph.put("track_progress", fmt2(trackProgressPct));
			int totalCp = rm.getTrackConfig().getCheckpoints().size();
			int passedCp = (st.nextCheckpointIndex);
			ph.put("checkpoint_passed", String.valueOf(passedCp));
			ph.put("checkpoint_total", String.valueOf(totalCp));

			// Ahead/behind racer placeholders (placement + speed + distance)
			try {
				fillNeighborRacerPlaceholders(p, rm, ctx, ph);
			} catch (Throwable ignored) {}

			lines = parseLines(p, cfgStringList("racing.ui.templates.racing.lines", java.util.List.of()), ph);
			if (lines.isEmpty()) {
				lines = parseLines(p, java.util.List.of(
						"<gray>Đường: <white>%track%",
						"<gray>Thời gian: <white>%timer%",
						"<gray>Vòng: <white>%lap_current%/%lap_total%",
						"<gray>Checkpoint: <white>%checkpoint_passed%/%checkpoint_total%",
						"<gray>Vị trí: <white>%position%/%joined%",
						"<gray>Tiến độ vòng: <white>%lap_progress%%",
						"<gray>Tiến độ tổng: <white>%track_progress%%"), ph);
			}
		} else {
			lines = parseLines(p, cfgStringList("racing.ui.templates.racing.lines", java.util.List.of("<gray>Đường: <white>%track%", "<gray>Thời gian: <white>%timer%")), ph);
		}
		// Allow internal placeholders in the scoreboard title too.
		Component title = parse(p, cfgString("racing.ui.templates.racing.title", "<gold>Đang đua"), ph);
		applySidebarComponents(p, sb, title, lines);
	}

	private void applyCompletedBoard(Player p, RaceManager rm, TickContext ctx) {
		if (rm == null) {
			applyLobbyBoard(p);
			return;
		}
		Sidebar sb = ensureSidebar(p);
		if (sb == null) {
			return;
		}
		if (ctx == null) ctx = new TickContext();
		boolean ended = ctx.allFinished;

		// Allow internal placeholders in the scoreboard title too.
		java.util.Map<String, String> phTitle = new java.util.HashMap<>();
		try { phTitle.put("racer_name", p.getName()); } catch (Throwable ignored) {}
		try { phTitle.put("racer_display", racerDisplay(p.getUniqueId(), p.getName())); } catch (Throwable ignored) {}
		try {
			String tn = null;
			try { tn = (rm != null && rm.getTrackConfig() != null) ? rm.getTrackConfig().getCurrentName() : null; } catch (Throwable ignored) { tn = null; }
			if (tn == null || tn.isBlank()) tn = "(unknown)";
			phTitle.put("track", tn);
		} catch (Throwable ignored) {}
		double trackLength = -1.0;
		try { trackLength = rm.getTrackConfig() != null ? rm.getTrackConfig().getTrackLength() : -1.0; } catch (Throwable ignored) { trackLength = -1.0; }
		phTitle.put("track_length", trackLength > 0.0 && Double.isFinite(trackLength) ? fmt1(trackLength) : "-");
		phTitle.put("track_length_unit", "m");
		phTitle.put("track_length_display", trackLength > 0.0 && Double.isFinite(trackLength) ? ("🛣 " + fmt1(trackLength) + "m") : "-");

		// Add racer info + final result placeholders for completed/ended header templates.
		try {
			PlayerProfileManager.Profile prof = (pm != null ? pm.get(p.getUniqueId()) : null);
			if (prof != null) {
				phTitle.put("racer_color", ColorTranslator.miniColorTag(prof.color));
				phTitle.put("icon", empty(prof.icon) ? "-" : prof.icon);
				phTitle.put("number", prof.number > 0 ? String.valueOf(prof.number) : "-");
				phTitle.put("completed", String.valueOf(prof.completed));
				phTitle.put("wins", String.valueOf(prof.wins));
				phTitle.put("time_raced", Time.formatDurationShort(prof.timeRacedMillis));
			}
		} catch (Throwable ignored) {}

		String unitPref = "";
		try {
			unitPref = pm != null ? pm.get(p.getUniqueId()).speedUnit : "";
		} catch (Throwable ignored) {
			unitPref = "";
		}
		String unit = (unitPref != null && !unitPref.isEmpty())
				? unitPref.toLowerCase()
				: cfgString("scoreboard.speed.unit", "kmh").toLowerCase();
		if (!"bps".equals(unit) && !"kmh".equals(unit) && !"bph".equals(unit))
			unit = "kmh";
		String unitLabel = "km/h";
		if ("bps".equals(unit)) unitLabel = "bps";
		else if ("bph".equals(unit)) unitLabel = "bph";
		phTitle.put("speed_unit", unitLabel);

		RaceManager.ParticipantState self = null;
		try { self = rm.getParticipantState(p.getUniqueId()); } catch (Throwable ignored) { self = null; }
		if (self != null && self.finished) {
			int pos = self.finishPosition > 0 ? self.finishPosition : 1;
			long t = Math.max(0L, self.finishTimeMillis - rm.getRaceStartMillis()) + self.penaltySeconds * 1000L;

			double avgBps = 0.0;
			try {
				double seconds = Math.max(0.001, t / 1000.0);
				avgBps = Math.max(0.0, self.distanceBlocks / seconds);
			} catch (Throwable ignored) {}

			double kmh = avgBps * 3.6;
			double bph = avgBps * 3600.0;
			String speedVal;
			if ("bps".equals(unit)) speedVal = fmt2(avgBps);
			else if ("bph".equals(unit)) speedVal = fmt2(bph);
			else speedVal = fmt2(kmh);

			String speedColorName = resolveSpeedColorByUnit(avgBps, unit);
			String avgSpeedDisplay = miniWrapTag(speedColorName, speedVal + " " + unitLabel);

			phTitle.put("finish_pos", colorizePlacement(pos));
			phTitle.put("finish_pos_tag", colorizePlacementTag(pos));
			phTitle.put("finish_time", Time.formatStopwatchMillis(t));
			phTitle.put("avg_speed", speedVal);
			phTitle.put("avg_speed_display", avgSpeedDisplay);
			phTitle.put("avg_speed_bps", fmt2(avgBps));
			phTitle.put("avg_speed_kmh", fmt2(kmh));
			phTitle.put("avg_speed_bph", fmt2(bph));
			phTitle.put("speed_color_name", speedColorName);
			phTitle.put("speed_color", miniOpenTag(speedColorName));
			phTitle.put("speed_color_close", miniCloseTag(speedColorName));
		} else {
			phTitle.put("finish_pos", "-");
			phTitle.put("finish_pos_tag", "-");
			phTitle.put("finish_time", "-");
			phTitle.put("avg_speed", "-");
			phTitle.put("avg_speed_display", "<gray>-</gray>");
			phTitle.put("avg_speed_bps", "-");
			phTitle.put("avg_speed_kmh", "-");
			phTitle.put("avg_speed_bph", "-");
			phTitle.put("speed_color_name", "gray");
			phTitle.put("speed_color", "<gray>");
			phTitle.put("speed_color_close", "</gray>");
		}

		Component title = parse(p,
			ended
				? cfgString("racing.ui.templates.ended.title", "<gold>Kết quả")
				: cfgString("racing.ui.templates.completed.title", "<gold>Kết quả"),
			phTitle);
		java.util.List<RaceManager.ParticipantState> standings = ensureStandings(rm, ctx);
		java.util.List<Component> lines = new java.util.ArrayList<>();

		// Common header (multi-line) for completed/ended boards.
		java.util.List<String> headerTpl = ended
				? cfgStringList("racing.ui.templates.ended.header", java.util.List.of())
				: cfgStringList("racing.ui.templates.completed.header", java.util.List.of());
		if (headerTpl != null && !headerTpl.isEmpty()) {
			lines.addAll(parseLines(p, headerTpl, phTitle));
		}

		if (ended) {
			// Race ended: show full list ordered by placement/time.
			long best = Long.MAX_VALUE;
			for (RaceManager.ParticipantState s : standings) {
				if (!s.finished) continue;
				long t = Math.max(0L, s.finishTimeMillis - rm.getRaceStartMillis()) + s.penaltySeconds * 1000L;
				if (t < best) best = t;
			}
			if (best == Long.MAX_VALUE) best = 0L;

			for (RaceManager.ParticipantState s : standings) {
				if (!s.finished) continue;
				String name = nameOfCached(s.id);
				long t = Math.max(0L, s.finishTimeMillis - rm.getRaceStartMillis()) + s.penaltySeconds * 1000L;
				long delta = Math.max(0L, t - best);
				java.util.Map<String,String> ph = new java.util.HashMap<>();
				ph.put("racer_name", name);
				ph.put("racer_display", racerDisplay(s.id, name));
				ph.put("finish_pos", colorizePlacement(s.finishPosition));
				ph.put("finish_pos_tag", colorizePlacementTag(s.finishPosition));
				ph.put("finish_time", Time.formatStopwatchMillis(t));
				ph.put("delta_time", Time.formatStopwatchMillis(delta));
				String key = (s.finishPosition == 1 || delta == 0L)
						? "racing.ui.templates.ended.winner_line"
						: "racing.ui.templates.ended.delta_line";
				String def = (s.finishPosition == 1 || delta == 0L)
					? "%finish_pos_tag% %racer_display% <gray>●</gray> <white>%finish_time%</white>"
					: "%finish_pos_tag% %racer_display% <gray>●</gray> <white>+%delta_time%</white>";
				lines.add(parse(p, cfgString(key, def), ph));
			}

			// Optional footer lines for ended board
			java.util.Map<String, String> phFooter = new java.util.HashMap<>(phTitle);
			try {
				phFooter.put("timer", Time.formatStopwatchMillis(rm.getRaceElapsedMillis()));
			} catch (Throwable ignored) {}
			java.util.List<String> footer = cfgStringList("racing.ui.templates.ended.footer", java.util.List.of());
			if (!footer.isEmpty()) lines.addAll(parseLines(p, footer, phFooter));

			applySidebarComponents(p, sb, title, lines);
			return;
		}

		// Completed board: some finished, others still racing.
		java.util.List<String> finishedHeaderTpl = cfgStringList(
				"racing.ui.templates.completed.finished-header",
				java.util.List.of("<yellow>Về đích")
		);
		java.util.List<String> finishedItemTpl = cfgStringList(
				"racing.ui.templates.completed.finished-item",
				java.util.List.of(
						"%finish_pos_tag% %racer_display%",
						"<gray>  thời gian: <white>%finish_time%"
				)
		);
		java.util.List<String> unfinishedHeaderTpl = cfgStringList(
				"racing.ui.templates.completed.unfinished-header",
				java.util.List.of("<yellow>Đang đua")
		);
		java.util.List<String> unfinishedItemTpl = cfgStringList(
				"racing.ui.templates.completed.unfinished-item",
				java.util.List.of(
						"%position_tag% %racer_display%",
						"<gray>  tiến độ vòng: <white>%lap_progress%%",
						"<gray>  tiến độ tổng: <white>%track_progress%%"
				)
		);

		int maxFinishedLines = cfgInt("racing.ui.templates.completed.finished-max-lines", 8);
		int maxUnfinishedLines = cfgInt("racing.ui.templates.completed.unfinished-max-lines", 6);
		maxFinishedLines = Math.max(0, maxFinishedLines);
		maxUnfinishedLines = Math.max(0, maxUnfinishedLines);

		// Finished section
		int finishedLinesUsed = 0;
		boolean wroteFinishedHeader = false;
		int finishedLinesPerItem = Math.max(1, finishedItemTpl == null ? 0 : finishedItemTpl.size());
		for (RaceManager.ParticipantState s : standings) {
			if (s == null || !s.finished)
				continue;
			if (maxFinishedLines > 0 && finishedLinesUsed >= maxFinishedLines)
				break;
			if (!wroteFinishedHeader) {
				lines.addAll(parseLines(p, finishedHeaderTpl, phTitle));
				wroteFinishedHeader = true;
			}

			String name = nameOfCached(s.id);
			long t = Math.max(0L, s.finishTimeMillis - rm.getRaceStartMillis()) + s.penaltySeconds * 1000L;
			java.util.Map<String,String> ph = new java.util.HashMap<>();
			ph.put("racer_name", name);
			ph.put("racer_display", racerDisplay(s.id, name));
			ph.put("finish_pos", colorizePlacement(s.finishPosition));
			ph.put("finish_pos_tag", colorizePlacementTag(s.finishPosition));
			ph.put("finish_time", Time.formatStopwatchMillis(t));
			lines.addAll(parseLines(p, finishedItemTpl, ph));
			finishedLinesUsed += finishedLinesPerItem;
		}

		// Unfinished section
		List<UUID> order = (ctx.liveOrder != null && !ctx.liveOrder.isEmpty()) ? ctx.liveOrder : rm.getLiveOrder();
		int unfinishedLinesUsed = 0;
		boolean wroteUnfinishedHeader = false;
		int unfinishedLinesPerItem = Math.max(1, unfinishedItemTpl == null ? 0 : unfinishedItemTpl.size());
		for (int i = 0; i < order.size(); i++) {
			UUID id = order.get(i);
			RaceManager.ParticipantState s = rm.getParticipantState(id);
			if (s == null || s.finished)
				continue;
			if (maxUnfinishedLines > 0 && unfinishedLinesUsed >= maxUnfinishedLines)
				break;

			// IMPORTANT: show the racer's true live placement (including finished racers),
			// not just 1..N within the unfinished subset.
			int pos;
			try {
				pos = (ctx != null && ctx.positionById != null && !ctx.positionById.isEmpty())
						? ctx.positionById.getOrDefault(id, i + 1)
						: (i + 1);
			} catch (Throwable ignored) {
				pos = i + 1;
			}
			if (!wroteUnfinishedHeader) {
				lines.addAll(parseLines(p, unfinishedHeaderTpl, phTitle));
				wroteUnfinishedHeader = true;
			}

			String name = nameOfCached(id);
			double lapProgressPct = rm.getLapProgressRatio(id) * 100.0;
			double trackProgressPct;
			double lapRatio = Math.max(0.0, Math.min(1.0, rm.getLapProgressRatio(id)));
			trackProgressPct = ((double) s.currentLap + lapRatio) / (double) Math.max(1, rm.getTotalLaps()) * 100.0;
			trackProgressPct = Math.max(0.0, Math.min(100.0, trackProgressPct));
			java.util.Map<String,String> ph = new java.util.HashMap<>();
			ph.put("racer_name", name);
			ph.put("racer_display", racerDisplay(id, name));
			ph.put("position", colorizePlacement(pos));
			ph.put("position_tag", colorizePlacementTag(pos));
			ph.put("lap_progress", fmt2(lapProgressPct));
			ph.put("track_progress", fmt2(trackProgressPct));
			lines.addAll(parseLines(p, unfinishedItemTpl, ph));
			unfinishedLinesUsed += unfinishedLinesPerItem;
		}

		java.util.List<String> footer = cfgStringList("racing.ui.templates.completed.footer", java.util.List.of());
		if (footer != null && !footer.isEmpty())
			lines.addAll(parseLines(p, footer, phTitle));
		applySidebarComponents(p, sb, title, lines);
	}

	private Sidebar ensureSidebar(Player p) {
		if (lib == null) return null;
		PlayerEntry e = players.computeIfAbsent(p.getUniqueId(), id -> new PlayerEntry());
		Sidebar sb = e.sidebar;
		if (sb == null) {
			sb = lib.createSidebar();
			e.sidebar = sb;
			e.lastPlayerEntityId = p.getEntityId();
			try { sb.addPlayer(p); } catch (Throwable ignored) {}
			log("Created sidebar for " + p.getName());
		}
		// Edge case: player disconnects/reconnects while we keep the Sidebar cached by UUID.
		// ScoreboardLibrary requires adding the (new) Player instance again after reconnect.
		try {
			int eid = p.getEntityId();
			if (e.lastPlayerEntityId != eid) {
				e.lastPlayerEntityId = eid;
				sb.addPlayer(p);
			}
		} catch (Throwable ignored) {
		}
		return sb;
	}

	private void applySidebarComponents(Player p, Sidebar sidebar, Component title, java.util.List<Component> lines) {
		if (sidebar == null) return;
		PlayerEntry e = players.get(p.getUniqueId());
		if (e == null) {
			e = new PlayerEntry();
			players.put(p.getUniqueId(), e);
		}
		sidebar.title(title);

		int last = e.lastLineCount;
		int newSize = (lines == null) ? 0 : lines.size();

		// IMPORTANT: Setting removed lines to Component.empty() still leaves blank rows visible.
		// To shrink cleanly AND minimize packets, only clear the indices that were previously set.
		int maxLines = 15;
		try { maxLines = sidebar.maxLines(); } catch (Throwable ignored) { maxLines = 15; }
		int limitNew = Math.min(newSize, maxLines);

		if (lines != null) {
			for (int idx = 0; idx < limitNew; idx++) sidebar.line(idx, lines.get(idx));
		}

		if (last > limitNew) {
			int limitOld = Math.min(last, maxLines);
			for (int idx = limitNew; idx < limitOld; idx++) sidebar.line(idx, (Component) null);
		}

		e.lastLineCount = newSize;
		log("Applied sidebar to " + p.getName() + " title='" + Text.plain(title) + "' lines=" + newSize);
	}

	// --- ActionBar support ---
	private void applyActionBarForWaiting(Player p, RaceManager rm) {
		if (usage == null || !usage.actionbarEnabled) return;
		String tpl = cfgString("racing.ui.templates.actionbar.waiting", "<yellow>Bắt đầu trong <white>%countdown%</white> ● <gray>%joined%/%max%</gray>");
		int countdown = rm.getCountdownRemainingSeconds();
		int joined = rm.getRegistered().size();
		int max = rm.getTrackConfig().getStarts().size();
		java.util.Map<String,String> ph = new java.util.HashMap<>();
		ph.put("countdown", Time.formatCountdownSeconds(countdown)); ph.put("joined", String.valueOf(joined)); ph.put("max", String.valueOf(max));
		Component c = parse(p, tpl, ph);
		sendActionBar(p, c);
		log("Applied waiting actionbar to " + p.getName() + " tpl='" + tpl + "'");
	}

	private void applyActionBarForRacing(Player p, RaceManager rm, TickContext ctx) {
		if (usage == null || !usage.actionbarEnabled) return;
		// Default template uses the unified %speed_display% placeholder (already colored).
		String tpl = cfgString("racing.ui.templates.actionbar.racing", "%position_tag% %racer_display% <white>%lap_current%/%lap_total%</white> <white>%checkpoint_passed%/%checkpoint_total%</white> <yellow>%lap_progress%%</yellow> %speed_display%");
		java.util.Map<String,String> ph = new java.util.HashMap<>();
		var st = rm.getParticipantState(p.getUniqueId());
		int pos = Math.max(1, ctx.positionById.getOrDefault(p.getUniqueId(), 1));
		int lapTotal = rm.getTotalLaps();
		int lapCurrent = 0;
		if (st != null) {
			lapCurrent = st.finished ? lapTotal : Math.min(lapTotal, st.currentLap + 1);
		}
		double lapProgressPct = rm.getLapProgressRatio(p.getUniqueId()) * 100.0;
		double trackProgressPct;
		if (st != null && st.finished) {
			trackProgressPct = 100.0;
		} else if (st != null) {
			double lapRatio = Math.max(0.0, Math.min(1.0, rm.getLapProgressRatio(p.getUniqueId())));
			trackProgressPct = ((double) st.currentLap + lapRatio) / (double) Math.max(1, lapTotal) * 100.0;
			trackProgressPct = Math.max(0.0, Math.min(100.0, trackProgressPct));
		} else {
			trackProgressPct = 0.0;
		}
		int nextCp = (st == null ? 0 : st.nextCheckpointIndex + 1);
		int totalCp = rm.getTrackConfig().getCheckpoints().size();
		int passedCp = (st == null ? 0 : st.nextCheckpointIndex);
		PlayerEntry pe = players.get(p.getUniqueId());
		double bps = (pe != null ? pe.lastBps : 0.0);
		double kmh = bps * 3.6;
		double bph = bps * 3600.0;
		// Per-player preferred unit overrides global
		String unitPref = pm != null ? pm.get(p.getUniqueId()).speedUnit : "";
		String unit = (unitPref != null && !unitPref.isEmpty()) ? unitPref.toLowerCase() : cfgString("scoreboard.speed.unit", "kmh").toLowerCase();
		String speedVal;
		String speedUnit;
		if ("bps".equals(unit)) { speedVal = fmt2(bps); speedUnit = "bps"; }
		else if ("bph".equals(unit)) { speedVal = fmt2(bph); speedUnit = "bph"; }
		else { speedVal = fmt2(kmh); speedUnit = "km/h"; unit = "kmh"; }
		String speedColorName = resolveSpeedColorByUnit(bps, unit);
		String speedDisplay = miniWrapTag(speedColorName, speedVal + " " + speedUnit);
		ph.put("position", colorizePlacement(pos));
		ph.put("position_tag", colorizePlacementTag(pos));
		ph.put("racer_name", p.getName());
		ph.put("racer_display", racerDisplay(p.getUniqueId(), p.getName()));
		ph.put("lap_current", String.valueOf(lapCurrent));
		ph.put("lap_total", String.valueOf(lapTotal));
		ph.put("lap_progress", fmt2(lapProgressPct));
		ph.put("track_progress", fmt2(trackProgressPct));
		ph.put("next_checkpoint", String.valueOf(nextCp)); ph.put("checkpoint_total", String.valueOf(totalCp));
		ph.put("checkpoint_passed", String.valueOf(passedCp));
		// Speed placeholders (configurable unit + back-compat)
		ph.put("speed", speedVal);
		ph.put("speed_unit", speedUnit);
		ph.put("speed_display", speedDisplay);
		ph.put("speed_bps", fmt2(bps));
		ph.put("speed_kmh", fmt2(kmh));
		ph.put("speed_bph", fmt2(bph));
		// Color placeholders
		// - %speed_color% returns the full tag, e.g. "<red>".
		// - %speed_color_name% returns the legacy tag name, e.g. "red".
		ph.put("speed_color_name", speedColorName);
		ph.put("speed_color", miniOpenTag(speedColorName));
		ph.put("speed_color_close", miniCloseTag(speedColorName));
		Component c = parse(p, tpl, ph);
		sendActionBar(p, c);
		log("Applied racing actionbar to " + p.getName() + " tpl='" + tpl + "' pos=" + pos + " lap=" + lapCurrent + "/" + lapTotal + " speed(bps)=" + fmt2(bps) + " unit=" + unit);
	}

	private String racerDisplay(UUID id, String name) {
		try {
			if (pm != null) return pm.formatRacerMini(id, name);
		} catch (Throwable ignored) {}
		String n = (name == null || name.isBlank()) ? "(không rõ)" : name;
		return "<white>● - " + n;
	}

	private static String fmt2(double v) {
		if (!Double.isFinite(v)) return "0.00";
		return String.format(Locale.US, "%.2f", v);
	}

	private static String miniOpenTag(String tagName) {
		if (tagName == null)
			return "";
		String t = tagName.trim();
		if (t.isEmpty())
			return "";
		if (t.startsWith("<") && t.endsWith(">"))
			return t;
		return "<" + t + ">";
	}

	private static String miniCloseTag(String tagNameOrOpenTag) {
		if (tagNameOrOpenTag == null)
			return "";
		String t = tagNameOrOpenTag.trim();
		if (t.isEmpty())
			return "";
		if (t.startsWith("<") && t.endsWith(">"))
			t = t.substring(1, t.length() - 1).trim();
		if (t.startsWith("/"))
			t = t.substring(1);
		int space = t.indexOf(' ');
		if (space > 0)
			t = t.substring(0, space);
		int colon = t.indexOf(':');
		if (colon > 0 && t.charAt(0) != '#')
			t = t.substring(0, colon);
		return "</" + t + ">";
	}

	private static String miniWrapTag(String tagName, String content) {
		String open = miniOpenTag(tagName);
		String close = miniCloseTag(tagName);
		String c = (content == null ? "" : content);
		if (open.isEmpty() || close.isEmpty())
			return c;
		return open + c + close;
	}

	private static String colorizePlacement(int placement) {
		int pos = Math.max(1, placement);
		String colored = coloredPlacement(String.valueOf(pos), pos);
		return "<shadow:#000000:0.65>" + colored + "</shadow>";
	}

	private static String colorizePlacementTag(int placement) {
		int pos = Math.max(1, placement);
		String colored = coloredPlacement("#" + pos, pos);
		return "<shadow:#000000:0.65>" + colored + "</shadow>";
	}

	private static String coloredPlacement(String content, int placement) {
		int pos = Math.max(1, placement);
		String c = (content == null ? "" : content);
		return switch (pos) {
			case 1 -> "<#FFD700>" + c + "</#FFD700>"; // vàng
			case 2 -> "<#C0C0C0>" + c + "</#C0C0C0>"; // bạc
			case 3 -> "<#CD7F32>" + c + "</#CD7F32>"; // đồng
			default -> "<#B0B0B0>" + c + "</#B0B0B0>"; // xám trung tính
		};
	}


	private static class TickContext {
		boolean running;
		boolean registering;
		boolean countdown;
		boolean anyFinished;
		boolean allFinished;
		java.util.List<java.util.UUID> liveOrder = java.util.List.of();
		final java.util.HashMap<java.util.UUID, Integer> positionById = new java.util.HashMap<>();
		java.util.List<RaceManager.ParticipantState> standings;
	}

	private String resolveSpeedColorByUnit(double bps, String unit) {
		refreshSpeedCfgIfNeeded(System.currentTimeMillis());
		SpeedColorCfg cfg;
		double v;
		if ("bps".equals(unit)) {
			cfg = speedCfgBps;
			v = bps;
		} else if ("kmh".equals(unit)) {
			cfg = speedCfgKmh;
			v = bps * 3.6;
		} else {
			cfg = speedCfgBph;
			v = bps * 3600.0;
		}
		if (cfg == null)
			return "red";
		if (v < cfg.yellow)
			return cfg.low;
		if (v < cfg.green)
			return cfg.mid;
		return cfg.high;
	}

	private void refreshSpeedCfgIfNeeded(long nowMs) {
		if (nowMs < speedCfgNextRefreshMs)
			return;
		speedCfgNextRefreshMs = nowMs + 1000L;

		// bps
		{
			int y = cfgInt("scoreboard.speed.yellow_bps", -1);
			int g = cfgInt("scoreboard.speed.green_bps", -1);
			double yellow;
			double green;
			if (y >= 0 && g >= 0) {
				yellow = y;
				green = g;
			} else {
				int yb = cfgInt("scoreboard.speed.yellow_bph", 5000);
				int gb = cfgInt("scoreboard.speed.green_bph", 20000);
				yellow = yb / 3600.0;
				green = gb / 3600.0;
			}
			if (green < yellow) {
				double t = green;
				green = yellow;
				yellow = t;
			}
			speedCfgBps.yellow = yellow;
			speedCfgBps.green = green;
			speedCfgBps.low = cfgString("scoreboard.speed.colors.bps.low", cfgString("scoreboard.speed.colors.low", "red"));
			speedCfgBps.mid = cfgString("scoreboard.speed.colors.bps.mid", cfgString("scoreboard.speed.colors.mid", "yellow"));
			speedCfgBps.high = cfgString("scoreboard.speed.colors.bps.high", cfgString("scoreboard.speed.colors.high", "green"));
		}

		// km/h
		{
			int y = cfgInt("scoreboard.speed.yellow_kmh", -1);
			int g = cfgInt("scoreboard.speed.green_kmh", -1);
			double yellow;
			double green;
			if (y >= 0 && g >= 0) {
				yellow = y;
				green = g;
			} else {
				int yb = cfgInt("scoreboard.speed.yellow_bph", 5000);
				int gb = cfgInt("scoreboard.speed.green_bph", 20000);
				yellow = yb / 1000.0;
				green = gb / 1000.0;
			}
			if (green < yellow) {
				double t = green;
				green = yellow;
				yellow = t;
			}
			speedCfgKmh.yellow = yellow;
			speedCfgKmh.green = green;
			speedCfgKmh.low = cfgString("scoreboard.speed.colors.kmh.low", cfgString("scoreboard.speed.colors.low", "red"));
			speedCfgKmh.mid = cfgString("scoreboard.speed.colors.kmh.mid", cfgString("scoreboard.speed.colors.mid", "yellow"));
			speedCfgKmh.high = cfgString("scoreboard.speed.colors.kmh.high", cfgString("scoreboard.speed.colors.high", "green"));
		}

		// bph
		{
			int yb = cfgInt("scoreboard.speed.yellow_bph", 5000);
			int gb = cfgInt("scoreboard.speed.green_bph", 20000);
			double yellow = yb;
			double green = gb;
			if (green < yellow) {
				double t = green;
				green = yellow;
				yellow = t;
			}
			speedCfgBph.yellow = yellow;
			speedCfgBph.green = green;
			speedCfgBph.low = cfgString("scoreboard.speed.colors.bph.low", cfgString("scoreboard.speed.colors.low", "red"));
			speedCfgBph.mid = cfgString("scoreboard.speed.colors.bph.mid", cfgString("scoreboard.speed.colors.mid", "yellow"));
			speedCfgBph.high = cfgString("scoreboard.speed.colors.bph.high", cfgString("scoreboard.speed.colors.high", "green"));
		}
	}

	private void clearActionBar(Player p) {
		try { sendActionBar(p, Component.empty()); } catch (Throwable ignored) {}
	}

	private void sendActionBar(Player p, Component c) {
		try { p.sendActionBar(c); } catch (Throwable ignored) {}
	}

	private java.util.List<Component> parseLines(Player p, java.util.List<String> lines, java.util.Map<String,String> placeholders) {
		java.util.List<Component> out = new java.util.ArrayList<>(lines == null ? 0 : lines.size());
		if (lines == null || lines.isEmpty()) return out;
		for (String raw : lines) {
			String expanded = expandPlaceholders(p, raw, placeholders);
			if (expanded == null) continue;
			// Control marker: skip sending this scoreboard line entirely.
			if (expanded.contains("[skip]")) continue;
			Component c = deserializeMini(expanded);
			if (c != null) out.add(c);
		}
		return out;
	}

	private Component parse(Player p, String raw, java.util.Map<String,String> placeholders) {
		String expanded = expandPlaceholders(p, raw, placeholders);
		if (expanded == null) return Component.empty();
		try {
			return MINI.deserialize(expanded);
		} catch (Throwable ignored) {
			return Text.c(expanded);
		}
	}

	private String expandPlaceholders(Player p, String raw, java.util.Map<String,String> placeholders) {
		if (raw == null) return null;
		String s = raw;
		// plugin placeholders (%key%)
		if (placeholders != null) {
			for (var e : placeholders.entrySet()) {
				String k = e.getKey();
				String v = e.getValue();
				if (k == null) continue;
				s = s.replace("%" + k + "%", v == null ? "" : v);
			}
		}
		// PlaceholderAPI if present
		if (placeholderApiEnabled) {
			try {
				s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
			} catch (Throwable ignored) {}
		}
		return s;
	}

	private Component deserializeMini(String expanded) {
		if (expanded == null) return null;
		try { return MINI.deserialize(expanded); } catch (Throwable ignored) { return Text.c(expanded); }
	}

	private static boolean isBoatLike(Entity e) {
		if (e == null) return false;
		try {
			String t = e.getType() != null ? e.getType().name() : null;
			if (t == null) return false;
			return t.endsWith("_BOAT") || t.endsWith("_CHEST_BOAT") || t.endsWith("_RAFT") || t.endsWith("_CHEST_RAFT")
					|| t.equals("BOAT") || t.equals("CHEST_BOAT");
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static Location riderOrVehicleLocation(Player p) {
		if (p == null) return null;
		try {
			Entity v = p.getVehicle();
			if (isBoatLike(v)) return v.getLocation();
		} catch (Throwable ignored) {}
		try { return p.getLocation(); } catch (Throwable ignored) { return null; }
	}

	private static String fmt1(double v) {
		if (!Double.isFinite(v)) return "0.0";
		return String.format(java.util.Locale.US, "%.1f", v);
	}

	private void fillNeighborRacerPlaceholders(Player p, RaceManager rm, TickContext ctx, java.util.Map<String, String> ph) {
		if (p == null || rm == null || ctx == null || ph == null) return;
		if (ctx.liveOrder == null || ctx.liveOrder.isEmpty()) return;
		if (usage == null || !usage.needsNeighbors) return;
		UUID self = p.getUniqueId();
		int idx = ctx.liveOrder.indexOf(self);
		UUID aheadId = (idx > 0) ? ctx.liveOrder.get(idx - 1) : null;
		UUID behindId = (idx >= 0 && idx + 1 < ctx.liveOrder.size()) ? ctx.liveOrder.get(idx + 1) : null;
		int aheadPos = (idx > 0) ? idx : -1;
		int behindPos = (idx >= 0 && idx + 1 < ctx.liveOrder.size()) ? (idx + 2) : -1;

		// Unit choice: follow the viewing player's preference (same as actionbar)
		String unitPref = "";
		try { unitPref = pm != null ? pm.get(p.getUniqueId()).speedUnit : ""; } catch (Throwable ignored) { unitPref = ""; }
		String unit = (unitPref != null && !unitPref.isEmpty()) ? unitPref.toLowerCase() : cfgString("scoreboard.speed.unit", "kmh").toLowerCase();
		if (!"bps".equals(unit) && !"kmh".equals(unit) && !"bph".equals(unit)) unit = "kmh";
		String unitLabel = "km/h";
		if ("bps".equals(unit)) unitLabel = "bps";
		else if ("bph".equals(unit)) unitLabel = "bph";

		Location selfLoc = riderOrVehicleLocation(p);

		fillNeighborOne("ahead", aheadId, aheadPos, rm, self, selfLoc, unit, unitLabel, ph);
		fillNeighborOne("behind", behindId, behindPos, rm, self, selfLoc, unit, unitLabel, ph);
	}

	private void fillNeighborOne(String prefix, UUID id, int placement, RaceManager rm, UUID selfId, Location selfLoc, String unit, String unitLabel,
			java.util.Map<String, String> ph) {
		String pfx = (prefix == null ? "" : prefix);
		if (id == null || placement <= 0) {
			ph.put(pfx + "_position", "-");
			ph.put(pfx + "_position_tag", "-");
			ph.put(pfx + "_racer_name", "-");
			ph.put(pfx + "_racer_display", "-");
			ph.put(pfx + "_speed", "-");
			ph.put(pfx + "_speed_unit", unitLabel);
			ph.put(pfx + "_speed_color_name", "gray");
			ph.put(pfx + "_speed_color", "<gray>");
			ph.put(pfx + "_speed_color_close", "</gray>");
			ph.put(pfx + "_speed_display", "<gray>-</gray>");
			ph.put(pfx + "_distance", "-");
			ph.put(pfx + "_distance_unit", "m");
			return;
		}

		ph.put(pfx + "_position", colorizePlacement(placement));
		ph.put(pfx + "_position_tag", colorizePlacementTag(placement));

		String name = nameOfCached(id);
		ph.put(pfx + "_racer_name", name);
		ph.put(pfx + "_racer_display", racerDisplay(id, name));

		// Speed
		double bps = 0.0;
		try {
			PlayerEntry pe = players.get(id);
			bps = pe != null ? pe.lastBps : 0.0;
		} catch (Throwable ignored) {
			bps = 0.0;
		}
		double kmh = bps * 3.6;
		double bph = bps * 3600.0;
		String speedVal;
		if ("bps".equals(unit)) speedVal = fmt2(bps);
		else if ("bph".equals(unit)) speedVal = fmt2(bph);
		else speedVal = fmt2(kmh);
		ph.put(pfx + "_speed", speedVal);
		ph.put(pfx + "_speed_unit", unitLabel);
		String speedColorName = resolveSpeedColorByUnit(bps, unit);
		ph.put(pfx + "_speed_color_name", speedColorName);
		ph.put(pfx + "_speed_color", miniOpenTag(speedColorName));
		ph.put(pfx + "_speed_color_close", miniCloseTag(speedColorName));
		ph.put(pfx + "_speed_display", miniWrapTag(speedColorName, speedVal + " " + unitLabel));

		// Distance (blocks)
		String distOut = "-";
		try {
			// Prefer track-following arc-length distance when available.
			if (rm != null && selfId != null) {
				double d = -1.0;
				if ("ahead".equalsIgnoreCase(pfx)) {
					d = rm.getArcDistanceMeters(selfId, id);
				} else if ("behind".equalsIgnoreCase(pfx)) {
					d = rm.getArcDistanceMeters(id, selfId);
				}
				if (d >= 0.0 && Double.isFinite(d)) {
					distOut = fmt1(d);
				}
			}

			// Fallback: straight-line distance in world space.
			if ("-".equals(distOut)) {
				Player other = Bukkit.getPlayer(id);
				Location otherLoc = riderOrVehicleLocation(other);
				if (selfLoc != null && otherLoc != null && selfLoc.getWorld() != null && otherLoc.getWorld() != null
						&& selfLoc.getWorld().equals(otherLoc.getWorld())) {
					double d = selfLoc.distance(otherLoc);
					if (Double.isFinite(d) && d >= 0.0) distOut = fmt1(d);
				}
			}
		} catch (Throwable ignored) {}
		ph.put(pfx + "_distance", distOut);
		ph.put(pfx + "_distance_unit", "m");
	}

	private String cfgString(String path, String def) {
		String v = plugin.getConfig().getString(path);
		if (v != null) return v;
		return def;
	}

	private java.util.List<String> cfgStringList(String path, java.util.List<String> def) {
		java.util.List<String> out = plugin.getConfig().getStringList(path);
		if (out != null && !out.isEmpty()) return out;
		return def;
	}

	private int cfgInt(String path, int def) { return plugin.getConfig().getInt(path, def); }

	private boolean cfgBool(String path, boolean def) {
		if (plugin.getConfig().contains(path)) return plugin.getConfig().getBoolean(path, def);
		return def;
	}

	private static boolean empty(String s) { return s == null || s.isEmpty(); }

	private String nameOfCached(UUID id) {
		if (id == null)
			return "-";
		String cached = nameCache.get(id);
		if (cached != null)
			return cached;
		String name = null;
		try {
			OfflinePlayer op = Bukkit.getOfflinePlayer(id);
			if (op != null)
				name = op.getName();
		} catch (Throwable ignored) {
			name = null;
		}
		if (name == null || name.isBlank()) {
			String s = id.toString();
			name = s.substring(0, 8);
		}
		nameCache.put(id, name);
		return name;
	}

	private void setState(Player p, String state) {
		if (!debug) return;
		PlayerEntry e = players.computeIfAbsent(p.getUniqueId(), id -> new PlayerEntry());
		String prev = e.lastState;
		e.lastState = state;
		if (prev == null || !prev.equals(state)) log("State for " + p.getName() + " -> " + state);
	}

	private void log(String msg) { if (debug) plugin.getLogger().info("[SB] " + msg); }

	private java.util.List<RaceManager.ParticipantState> ensureStandings(RaceManager rm, TickContext ctx) {
		if (rm == null)
			return java.util.List.of();
		if (ctx == null)
			return rm.getStandings();
		if (ctx.standings != null)
			return ctx.standings;
		// Only compute standings when we are about to render a completed/ended board.
		java.util.List<RaceManager.ParticipantState> s = rm.getStandings();
		ctx.standings = s;
		return s;
	}

	private void updateSpeedCache(Player p, long nowMs) {
		if (p == null)
			return;
		java.util.UUID id = p.getUniqueId();
		PlayerEntry e = players.computeIfAbsent(id, k -> new PlayerEntry());

		org.bukkit.Location now = riderOrVehicleLocation(p);
		if (now == null)
			now = p.getLocation();

		long prevT = e.lastLocTimeMs;
		double bps = 0.0;
		java.util.UUID worldId = null;
		try {
			if (now.getWorld() != null)
				worldId = now.getWorld().getUID();
		} catch (Throwable ignored) {
			worldId = null;
		}

		if (e.hasLastLoc && prevT > 0L && worldId != null && e.lastWorldId != null && e.lastWorldId.equals(worldId)) {
			long dtMsRaw = nowMs - prevT;
			if (dtMsRaw > 0L) {
				long expectedMs = Math.max(1L, (long) Math.max(1, updatePeriodTicks) * 50L);
				long dtMs = Math.max(dtMsRaw, expectedMs);
				double dist;
				try {
					double dx = now.getX() - e.lastX;
					double dy = now.getY() - e.lastY;
					double dz = now.getZ() - e.lastZ;
					dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				} catch (Throwable ignored) {
					dist = 0.0;
				}

				double maxDist = 5.0 * (double) Math.max(1, updatePeriodTicks);
				if (Double.isFinite(dist) && dist >= 0.0 && dist <= maxDist) {
					double deadzone = 0.03 * (double) Math.max(1, updatePeriodTicks);
					if (dist >= deadzone) {
						bps = Math.max(0.0, dist / (dtMs / 1000.0));
					}
				}
			}
		}

		e.lastBps = bps;
		e.hasLastLoc = true;
		e.lastWorldId = worldId;
		try {
			e.lastX = now.getX();
			e.lastY = now.getY();
			e.lastZ = now.getZ();
		} catch (Throwable ignored) {
			e.lastX = 0.0;
			e.lastY = 0.0;
			e.lastZ = 0.0;
		}
		e.lastLocTimeMs = nowMs;
	}

	private TemplateUsage computeTemplateUsage() {
		TemplateUsage u = new TemplateUsage();
		u.actionbarEnabled = cfgBool("racing.ui.templates.actionbar.enabled", true);

		java.util.List<String> pool = new java.util.ArrayList<>();
		try {
			pool.add(cfgString("racing.ui.templates.actionbar.racing", ""));
			pool.add(cfgString("racing.ui.templates.actionbar.completed", ""));
			pool.add(cfgString("racing.ui.templates.actionbar.waiting", ""));
		} catch (Throwable ignored) {
		}

		try {
			pool.add(cfgString("racing.ui.templates.racing.title", ""));
			pool.addAll(cfgStringList("racing.ui.templates.racing.lines", java.util.List.of()));
		} catch (Throwable ignored) {
		}

		try {
			pool.add(cfgString("racing.ui.templates.completed.title", ""));
			pool.addAll(cfgStringList("racing.ui.templates.completed.header", java.util.List.of()));
			pool.addAll(cfgStringList("racing.ui.templates.completed.footer", java.util.List.of()));
			pool.addAll(cfgStringList("racing.ui.templates.completed.finished-header", java.util.List.of()));
			pool.addAll(cfgStringList("racing.ui.templates.completed.finished-item", java.util.List.of()));
			pool.addAll(cfgStringList("racing.ui.templates.completed.unfinished-header", java.util.List.of()));
			pool.addAll(cfgStringList("racing.ui.templates.completed.unfinished-item", java.util.List.of()));
		} catch (Throwable ignored) {
		}

		try {
			pool.add(cfgString("racing.ui.templates.event_lobby.title", ""));
			pool.addAll(cfgStringList("racing.ui.templates.event_lobby.header", java.util.List.of()));
			pool.addAll(cfgStringList("racing.ui.templates.event_lobby.ranking_item", java.util.List.of()));
			pool.addAll(cfgStringList("racing.ui.templates.event_lobby.footer", java.util.List.of()));
		} catch (Throwable ignored) {
		}

		boolean needsSpeed = false;
		boolean needsNeighbors = false;
		boolean needsEventLobby = false;
		for (String s : pool) {
			if (s == null)
				continue;
			if (s.contains("%speed") || s.contains("%avg_speed"))
				needsSpeed = true;
			if (s.contains("%ahead_") || s.contains("%behind_"))
				needsNeighbors = true;
			if (s.contains("%event_") || s.contains("%rank_"))
				needsEventLobby = true;
		}

		u.needsSpeed = u.actionbarEnabled && needsSpeed;
		u.needsNeighbors = needsNeighbors;
		u.needsEventLobby = needsEventLobby;
		return u;
	}
}

