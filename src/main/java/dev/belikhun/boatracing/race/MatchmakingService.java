package dev.belikhun.boatracing.race;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.track.TrackLibrary;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight matchmaking queue that starts a race when enough players join or when the
 * oldest player has waited too long.
 */
public class MatchmakingService {
	private final BoatRacingPlugin plugin;
	private final RaceService raceService;
	private final LinkedHashMap<UUID, Long> queue = new LinkedHashMap<>();
	private final int minPlayers;
	private final long maxWaitMs;
	private final long tickMs;
	private BukkitTask task;

	public MatchmakingService(BoatRacingPlugin plugin, RaceService raceService) {
		this(plugin, raceService, 4, 30_000L, 1_000L);
	}

	public MatchmakingService(BoatRacingPlugin plugin, RaceService raceService, int minPlayers, long maxWaitMs, long tickMs) {
		this.plugin = plugin;
		this.raceService = raceService;
		this.minPlayers = Math.max(2, minPlayers);
		this.maxWaitMs = Math.max(5_000L, maxWaitMs);
		this.tickMs = Math.max(250L, tickMs);
	}

	public void start() {
		if (task != null)
			return;
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, tickMs / 50L, tickMs / 50L);
	}

	public void stop() {
		if (task != null) {
			try {
				task.cancel();
			} catch (Throwable ignored) {
			}
			task = null;
		}
		queue.clear();
	}

	public synchronized boolean isQueued(UUID id) {
		return id != null && queue.containsKey(id);
	}

	public int getMinPlayers() {
		return minPlayers;
	}

	public long getMaxWaitMs() {
		return maxWaitMs;
	}

	public synchronized long getWaitMillis(UUID id) {
		if (id == null)
			return -1L;
		Long joined = queue.get(id);
		if (joined == null)
			return -1L;
		return Math.max(0L, System.currentTimeMillis() - joined);
	}

	public synchronized int queuedCount() {
		return queue.size();
	}

	public synchronized void leave(Player p) {
		if (p == null)
			return;
		if (queue.remove(p.getUniqueId()) != null) {
			Text.msg(p, "&e❌ Đã rời hàng chờ ghép trận.");
		}
	}

	public synchronized void removeIfQueued(UUID id) {
		if (id == null)
			return;
		queue.remove(id);
	}

	public synchronized void join(Player p) {
		if (p == null)
			return;
		UUID id = p.getUniqueId();
		if (raceService.findRaceFor(id) != null) {
			Text.msg(p, "&c❌ Bạn đang ở trong cuộc đua. Hãy rời cuộc đua trước.");
			return;
		}
		if (raceService.isSpectating(id)) {
			Text.msg(p, "&c❌ Bạn đang ở chế độ theo dõi. Hãy rời theo dõi trước.");
			return;
		}
		if (queue.containsKey(id)) {
			Text.msg(p, "&e⌛ Bạn đang chờ ghép trận (&f" + queue.size() + "&e người).");
			return;
		}
		queue.put(id, System.currentTimeMillis());
		Text.msg(p, "&a✔ Đã vào hàng chờ ghép trận. Số người: &f" + queue.size());
	}

	private synchronized void tick() {
		if (queue.isEmpty())
			return;
		long now = System.currentTimeMillis();

		// Drop offline players.
		queue.entrySet().removeIf(en -> {
			UUID id = en.getKey();
			Player pl = (id != null ? Bukkit.getPlayer(id) : null);
			return (pl == null || !pl.isOnline());
		});
		if (queue.isEmpty())
			return;

		int size = queue.size();
		// Performance: avoid stream overhead for min lookup
		long oldest = now;
		for (long t : queue.values()) {
			if (t < oldest)
				oldest = t;
		}
		boolean readyByCount = size >= minPlayers;
		boolean readyByWait = (now - oldest) >= maxWaitMs;
		if (!readyByCount && !readyByWait)
			return;

		launchMatch();
	}

	private void launchMatch() {
		List<UUID> ids = new ArrayList<>(queue.keySet());
		queue.clear();

		List<Player> players = new ArrayList<>();
		for (UUID id : ids) {
			Player pl = (id != null ? Bukkit.getPlayer(id) : null);
			if (pl != null && pl.isOnline())
				players.add(pl);
		}
		if (players.isEmpty())
			return;

		RaceManager target = pickTarget(players.size());
		if (target == null) {
			for (Player p : players) {
				Text.msg(p, "&c❌ Không tìm thấy đường đua sẵn sàng để ghép trận.");
			}
			return;
		}

		try {
			target.setTotalLaps(raceService.getDefaultLaps());
			target.openRegistration(target.getTotalLaps(), null);
		} catch (Throwable ignored) {
		}

		String trackName = safeTrackName(target);
		int joined = 0;
		for (Player p : players) {
			try {
				boolean ok = raceService.join(trackName, p);
				if (ok)
					joined++;
			} catch (Throwable ignored) {
			}
		}

		if (joined <= 0) {
			for (Player p : players) {
				Text.msg(p, "&c❌ Ghép trận thất bại. Hãy thử lại.");
			}
			return;
		}

		try {
			target.forceStart();
		} catch (Throwable ignored) {
		}

		for (Player p : players) {
			Text.msg(p, "&a✔ Đã ghép trận vào đường &f" + trackName + "&a. Bắt đầu ngay!");
		}
	}

	private RaceManager pickTarget(int requestedPlayers) {
		TrackLibrary lib = plugin.getTrackLibrary();
		if (lib == null)
			return null;

		RaceManager best = null;
		int bestStarts = 0;
		for (String name : lib.list()) {
			if (name == null || name.isBlank())
				continue;
			RaceManager rm = raceService.getOrCreate(name);
			if (rm == null)
				continue;
			try {
				TrackConfig cfg = rm.getTrackConfig();
				if (cfg == null || !cfg.isReady())
					continue;
				if (rm.isRunning() || rm.isAnyCountdownActive() || rm.isRegistering())
					continue;
				int starts = (cfg.getStarts() != null ? cfg.getStarts().size() : 0);
				if (starts <= 0)
					continue;
				boolean enoughSlots = starts >= requestedPlayers;
				if (best == null)
					best = rm;
				if (enoughSlots) {
					// Prefer the smallest track that still fits everyone to reduce empty slots.
					if (best == rm)
						bestStarts = starts;
					else if (starts < bestStarts || bestStarts < requestedPlayers) {
						best = rm;
						bestStarts = starts;
					}
				} else if (bestStarts < requestedPlayers && starts > bestStarts) {
					// No track fits everyone yet; take the one with the most starts as fallback.
					best = rm;
					bestStarts = starts;
				}
			} catch (Throwable ignored) {
			}
		}
		return best;
	}

	private String safeTrackName(RaceManager rm) {
		if (rm == null)
			return "(không rõ)";
		try {
			TrackConfig cfg = rm.getTrackConfig();
			String n = cfg != null ? cfg.getCurrentName() : null;
			if (n != null && !n.isBlank())
				return n;
		} catch (Throwable ignored) {
		}
		return "(không rõ)";
	}
}
