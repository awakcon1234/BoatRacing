package dev.belikhun.boatracing.integrations.placeholderapi;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.EventParticipant;
import dev.belikhun.boatracing.event.EventParticipantStatus;
import dev.belikhun.boatracing.event.EventState;
import dev.belikhun.boatracing.event.RaceEvent;
import dev.belikhun.boatracing.profile.PlayerProfileManager;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.race.RaceService;
import dev.belikhun.boatracing.util.ColorTranslator;
import dev.belikhun.boatracing.util.Time;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlaceholderApiService {
	private final BoatRacingPlugin plugin;
	private BoatRacingExpansion expansion;
	private boolean listenerRegistered = false;

	public PlaceholderApiService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	public synchronized void start() {
		ensureListener();
		tryRegisterNow("startup");
	}

	public synchronized void stop() {
		if (expansion == null)
			return;
		try {
			expansion.unregister();
		} catch (Throwable ignored) {
		}
		expansion = null;
	}

	private synchronized void ensureListener() {
		if (listenerRegistered)
			return;
		if (plugin == null)
			return;
		try {
			Bukkit.getPluginManager().registerEvents(new PapiHookListener(this), plugin);
			listenerRegistered = true;
		} catch (Throwable ignored) {
			listenerRegistered = false;
		}
	}

	private synchronized void tryRegisterNow(String reason) {
		if (expansion != null)
			return;
		if (!isPlaceholderApiEnabled())
			return;

		try {
			expansion = new BoatRacingExpansion(plugin);
			boolean ok = false;
			try {
				ok = expansion.register();
			} catch (Throwable ignored) {
				ok = false;
			}
			if (!ok) {
				expansion = null;
				try {
					if (plugin != null)
						plugin.getLogger().warning("[PAPI] Không thể đăng ký expansion br (" + reason + ").");
				} catch (Throwable ignored) {
				}
				return;
			}
			try {
				if (plugin != null)
					plugin.getLogger().info("[PAPI] Đã đăng ký placeholder expansion: br (" + reason + ")");
			} catch (Throwable ignored) {
			}
		} catch (Throwable t) {
			expansion = null;
			try {
				if (plugin != null)
					plugin.getLogger().warning("[PAPI] Lỗi khi đăng ký expansion br: " + t.getMessage());
			} catch (Throwable ignored) {
			}
		}
	}

	private synchronized void onPlaceholderApiEnabled() {
		tryRegisterNow("PlaceholderAPI enabled");
	}

	private synchronized void onPlaceholderApiDisabled() {
		// Allow re-register after reload
		try {
			stop();
		} catch (Throwable ignored) {
		}
	}

	private static final class PapiHookListener implements Listener {
		private final PlaceholderApiService svc;

		PapiHookListener(PlaceholderApiService svc) {
			this.svc = svc;
		}

		@EventHandler
		public void onPluginEnable(PluginEnableEvent e) {
			if (svc == null || e == null || e.getPlugin() == null)
				return;
			try {
				if (!"PlaceholderAPI".equalsIgnoreCase(e.getPlugin().getName()))
					return;
			} catch (Throwable ignored) {
				return;
			}
			svc.onPlaceholderApiEnabled();
		}

		@EventHandler
		public void onPluginDisable(PluginDisableEvent e) {
			if (svc == null || e == null || e.getPlugin() == null)
				return;
			try {
				if (!"PlaceholderAPI".equalsIgnoreCase(e.getPlugin().getName()))
					return;
			} catch (Throwable ignored) {
				return;
			}
			svc.onPlaceholderApiDisabled();
		}
	}

	private static boolean isPlaceholderApiEnabled() {
		try {
			return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static final class EventRankEntry {
		final UUID id;
		final String name;
		final int points;
		int position;

		EventRankEntry(UUID id, String name, int points) {
			this.id = id;
			this.name = name;
			this.points = points;
		}
	}

	private static boolean isEventParticipant(RaceEvent e, UUID playerId) {
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

	private static String safeStr(String s) {
		if (s == null)
			return "-";
		String t = s.trim();
		return t.isEmpty() ? "-" : t;
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

	private static int safeInt(int v) {
		return Math.max(0, v);
	}

	private static String fmt1(double v) {
		if (!Double.isFinite(v))
			return "-";
		return String.format(java.util.Locale.US, "%.1f", v);
	}

	private static final class BoatRacingExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
		private final BoatRacingPlugin plugin;

		BoatRacingExpansion(BoatRacingPlugin plugin) {
			this.plugin = plugin;
		}

		@Override
		public String getIdentifier() {
			return "br";
		}

		@Override
		public String getAuthor() {
			try {
				var a = plugin != null ? plugin.getPluginAuthors() : null;
				if (a != null && !a.isEmpty())
					return String.join(", ", a);
			} catch (Throwable ignored) {
			}
			return "unknown";
		}

		@Override
		public String getVersion() {
			try {
				return plugin != null ? plugin.getPluginVersion() : "unknown";
			} catch (Throwable ignored) {
				return "unknown";
			}
		}

		@Override
		public boolean persist() {
			return true;
		}

		@Override
		public boolean canRegister() {
			return true;
		}

		@Override
		public String onPlaceholderRequest(Player p, String params) {
			if (p == null)
				return "-";
			if (params == null || params.isBlank())
				return "-";

			final String key = params.trim().toLowerCase();

			final PlayerProfileManager pm = (plugin != null ? plugin.getProfileManager() : null);
			final RaceService rs = (plugin != null ? plugin.getRaceService() : null);
			final dev.belikhun.boatracing.event.EventService es = (plugin != null ? plugin.getEventService() : null);

			PlayerProfileManager.Profile prof = null;
			try {
				prof = pm != null ? pm.get(p.getUniqueId()) : null;
			} catch (Throwable ignored) {
				prof = null;
			}

			if (key.equals("racer_name"))
				return safeStr(p.getName());
			if (key.equals("racer_display")) {
				try {
					return pm != null ? pm.formatRacerMini(p.getUniqueId(), p.getName()) : safeStr(p.getName());
				} catch (Throwable ignored) {
					return safeStr(p.getName());
				}
			}
			if (key.equals("racer_color")) {
				try {
					return prof != null ? ColorTranslator.miniColorTag(prof.color) : "<white>";
				} catch (Throwable ignored) {
					return "<white>";
				}
			}
			if (key.equals("racer_icon")) {
				try {
					return (prof != null && prof.icon != null && !prof.icon.isBlank()) ? prof.icon : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}
			if (key.equals("racer_number")) {
				try {
					return (prof != null && prof.number > 0) ? String.valueOf(prof.number) : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}
			if (key.equals("racer_completed")) {
				try {
					return String.valueOf(prof != null ? safeInt(prof.completed) : 0);
				} catch (Throwable ignored) {
					return "0";
				}
			}
			if (key.equals("racer_wins")) {
				try {
					return String.valueOf(prof != null ? safeInt(prof.wins) : 0);
				} catch (Throwable ignored) {
					return "0";
				}
			}
			if (key.equals("racer_time_raced")) {
				try {
					return (prof != null) ? Time.formatDurationShort(prof.timeRacedMillis) : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}
			if (key.equals("racer_boat_type")) {
				try {
					return (prof != null && prof.boatType != null && !prof.boatType.isBlank()) ? prof.boatType : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}
			if (key.equals("racer_speed_unit")) {
				try {
					return (prof != null && prof.speedUnit != null && !prof.speedUnit.isBlank()) ? prof.speedUnit : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}

			// Track (current race context for this player)
			String trackName = null;
			RaceManager rm = null;
			try {
				trackName = rs != null ? rs.findTrackNameFor(p.getUniqueId()) : null;
				rm = rs != null ? rs.findRaceFor(p.getUniqueId()) : null;
			} catch (Throwable ignored) {
				trackName = null;
				rm = null;
			}

			if (key.equals("track_name") || key.equals("track"))
				return safeStr(trackName);

			if (key.equals("track_joined")) {
				try {
					return String.valueOf(rm != null ? safeInt(rm.getRegistered().size()) : 0);
				} catch (Throwable ignored) {
					return "0";
				}
			}

			if (key.equals("track_max")) {
				try {
					int max = 0;
					if (rm != null && rm.getTrackConfig() != null) {
						max = rm.getTrackConfig().getStarts().size();
					}
					return String.valueOf(safeInt(max));
				} catch (Throwable ignored) {
					return "0";
				}
			}

			if (key.equals("track_laps")) {
				try {
					return String.valueOf(rm != null ? safeInt(rm.getTotalLaps()) : 0);
				} catch (Throwable ignored) {
					return "0";
				}
			}

			if (key.equals("track_checkpoint_total")) {
				try {
					int cps = 0;
					if (rm != null && rm.getTrackConfig() != null) {
						cps = rm.getTrackConfig().getCheckpoints().size();
					}
					return String.valueOf(safeInt(cps));
				} catch (Throwable ignored) {
					return "0";
				}
			}

			if (key.equals("track_length")) {
				try {
					double len = -1.0;
					if (rm != null && rm.getTrackConfig() != null) {
						len = rm.getTrackConfig().getTrackLength();
					}
					return (len > 0.0 && Double.isFinite(len)) ? fmt1(len) : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}

			if (key.equals("track_length_display")) {
				try {
					double len = -1.0;
					if (rm != null && rm.getTrackConfig() != null) {
						len = rm.getTrackConfig().getTrackLength();
					}
					return (len > 0.0 && Double.isFinite(len)) ? ("🛣 " + fmt1(len) + "m") : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}

			// Event (active global event)
			RaceEvent e = null;
			try {
				e = es != null ? es.getActiveEvent() : null;
			} catch (Throwable ignored) {
				e = null;
			}

			if (key.equals("event_id"))
				return e == null ? "-" : safeStr(e.id);
			if (key.equals("event_title"))
				return e == null ? "-" : safeStr(e.title);
			if (key.equals("event_state"))
				return (e == null || e.state == null) ? "-" : e.state.name();
			if (key.equals("event_state_display"))
				return e == null ? "-" : eventStateDisplay(e.state);
			if (key.equals("event_track_total")) {
				try {
					return String.valueOf(e == null || e.trackPool == null ? 0 : e.trackPool.size());
				} catch (Throwable ignored) {
					return "0";
				}
			}
			if (key.equals("event_track_index")) {
				try {
					return String.valueOf(e == null ? 0 : (Math.max(0, e.currentTrackIndex) + 1));
				} catch (Throwable ignored) {
					return "0";
				}
			}
			if (key.equals("event_track_name")) {
				try {
					return e == null ? "-" : safeStr(e.currentTrackName());
				} catch (Throwable ignored) {
					return "-";
				}
			}
			if (key.equals("event_participants")) {
				if (e == null)
					return "0";
				try {
					List<EventRankEntry> ranking = buildRanking(e);
					return String.valueOf(ranking.size());
				} catch (Throwable ignored) {
					return "0";
				}
			}
			if (key.equals("event_participants_max")) {
				try {
					int max = (e == null) ? 0 : safeInt(e.maxParticipants);
					return max > 0 ? String.valueOf(max) : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}
			if (key.equals("event_points")) {
				if (e == null)
					return "0";
				try {
					if (e.participants == null)
						return "0";
					EventParticipant ep = e.participants.get(p.getUniqueId());
					if (ep == null || ep.status == EventParticipantStatus.LEFT)
						return "0";
					return String.valueOf(safeInt(ep.pointsTotal));
				} catch (Throwable ignored) {
					return "0";
				}
			}
			if (key.equals("event_position")) {
				if (e == null)
					return "-";
				try {
					if (!isEventParticipant(e, p.getUniqueId()))
						return "-";
					List<EventRankEntry> ranking = buildRanking(e);
					int pos = 0;
					for (EventRankEntry it : ranking) {
						if (it != null && p.getUniqueId().equals(it.id)) {
							pos = it.position;
							break;
						}
					}
					return pos > 0 ? String.valueOf(pos) : "-";
				} catch (Throwable ignored) {
					return "-";
				}
			}

			return null;
		}

		private static List<EventRankEntry> buildRanking(RaceEvent e) {
			List<EventRankEntry> ranking = new ArrayList<>();
			if (e == null || e.participants == null)
				return ranking;

			for (var en : e.participants.entrySet()) {
				UUID id = en.getKey();
				EventParticipant ep = en.getValue();
				if (id == null || ep == null)
					continue;
				if (ep.status == EventParticipantStatus.LEFT)
					continue;
				String name = (ep.nameSnapshot == null || ep.nameSnapshot.isBlank()) ? "(không rõ)" : ep.nameSnapshot;
				ranking.add(new EventRankEntry(id, name, safeInt(ep.pointsTotal)));
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
				UUID ua = a == null ? null : a.id;
				UUID ub = b == null ? null : b.id;
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
			return ranking;
		}
	}
}
