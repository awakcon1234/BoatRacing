package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.EventParticipant;
import dev.belikhun.boatracing.event.EventParticipantStatus;
import dev.belikhun.boatracing.event.EventService;
import dev.belikhun.boatracing.event.EventState;
import dev.belikhun.boatracing.event.RaceEvent;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardFontLoader;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardPlacement;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardViewers;
import dev.belikhun.boatracing.integrations.mapengine.board.MapEngineBoardDisplay;
import dev.belikhun.boatracing.integrations.mapengine.board.RenderBuffers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Event information board rendered with MapEngine.
 *
 * Note: This service is intentionally small and mirrors LobbyBoardService's
 * viewer + rendering pipeline, but reads from EventService state.
 */
public final class EventBoardService {
	private final BoatRacingPlugin plugin;
	private final EventService eventService;

	private final MapEngineBoardDisplay boardDisplay = new MapEngineBoardDisplay();
	private final RenderBuffers renderBuffers = new RenderBuffers();

	private BukkitTask tickTask;

	private BoardPlacement placement;
	private int visibleRadiusChunks = 12;
	private int updateTicks = 20;
	private boolean debug = false;
	private boolean mapBuffering = true;
	private boolean mapBundling = false;

	// Fonts
	private Font titleFont;
	private Font bodyFont;

	// Runtime
	private final Set<UUID> spawnedTo = new HashSet<>();

	public EventBoardService(BoatRacingPlugin plugin, EventService eventService) {
		this.plugin = plugin;
		this.eventService = eventService;
	}

	public void reloadFromConfig() {
		stop();
		loadConfig();
		start();
	}

	private void loadConfig() {
		placement = BoardPlacement.load(plugin.getConfig().getConfigurationSection("mapengine.event-board"));
		visibleRadiusChunks = clamp(plugin.getConfig().getInt("mapengine.event-board.visible-radius-chunks", 12), 1, 64);
		updateTicks = clamp(plugin.getConfig().getInt("mapengine.event-board.update-ticks", 20), 1, 200);
		debug = plugin.getConfig().getBoolean("mapengine.event-board.debug", false);
		mapBuffering = plugin.getConfig().getBoolean("mapengine.event-board.pipeline.buffering", true);
		mapBundling = plugin.getConfig().getBoolean("mapengine.event-board.pipeline.bundling", false);

		// Fonts: load a shared font file (same loader as lobby-board). If absent, fall back to Monospaced.
		String fontFile = plugin.getConfig().getString("mapengine.event-board.font-file", "");
		Font base = BoardFontLoader.tryLoadBoardFont(plugin, fontFile, debug ? (m) -> {
			try {
				plugin.getLogger().info("[EventBoard] " + m);
			} catch (Throwable ignored) {
			}
		} : null);

		int titleSize = clamp(plugin.getConfig().getInt("mapengine.event-board.font.title-size", 18), 10, 96);
		int bodySize = clamp(plugin.getConfig().getInt("mapengine.event-board.font.body-size", 14), 8, 72);

		if (base != null) {
			titleFont = base.deriveFont(Font.BOLD, (float) titleSize);
			bodyFont = base.deriveFont(Font.PLAIN, (float) bodySize);
		} else {
			titleFont = new Font("Monospaced", Font.BOLD, titleSize);
			bodyFont = new Font("Monospaced", Font.PLAIN, bodySize);
		}
	}

	public void start() {
		if (tickTask != null)
			return;

		if (!plugin.getConfig().getBoolean("mapengine.event-board.enabled", false))
			return;
		if (placement == null || !placement.isValid())
			return;

		MapEngineApi api = MapEngineService.get();
		if (api == null)
			return;

		boardDisplay.ensure(api, placement, mapBuffering, mapBundling);
		if (!boardDisplay.isReady())
			return;

		tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, updateTicks);
	}

	public void stop() {
		if (tickTask != null) {
			try {
				tickTask.cancel();
			} catch (Throwable ignored) {
			}
			tickTask = null;
		}

		for (UUID id : new HashSet<>(spawnedTo)) {
			Player p = Bukkit.getPlayer(id);
			if (p != null && p.isOnline()) {
				try {
					boardDisplay.removeViewer(p);
				} catch (Throwable ignored) {
				}
			}
		}
		spawnedTo.clear();

		try {
			boardDisplay.destroy();
		} catch (Throwable ignored) {
		}
	}

	private void tick() {
		if (placement == null || !placement.isValid())
			return;
		if (!plugin.getConfig().getBoolean("mapengine.event-board.enabled", false))
			return;

		MapEngineApi api = MapEngineService.get();
		if (api == null) {
			stop();
			return;
		}

		boardDisplay.ensure(api, placement, mapBuffering, mapBundling);
		if (!boardDisplay.isReady())
			return;

		RaceEvent active = null;
		try {
			active = eventService != null ? eventService.getActiveEvent() : null;
		} catch (Throwable ignored) {
			active = null;
		}

		// Determine eligible viewers:
		// - within radius
		// - are active participants (not LEFT)
		Set<UUID> eligible = new HashSet<>();
		if (active != null && active.participants != null && !active.participants.isEmpty()) {
			for (Player p : Bukkit.getOnlinePlayers()) {
				if (p == null || !p.isOnline() || p.getWorld() == null)
					continue;
				if (!BoardViewers.isWithinRadiusChunks(p, placement, visibleRadiusChunks))
					continue;

				EventParticipant ep = null;
				try {
					ep = active.participants.get(p.getUniqueId());
				} catch (Throwable ignored) {
					ep = null;
				}
				if (ep == null)
					continue;
				if (ep.status == EventParticipantStatus.LEFT)
					continue;
				eligible.add(p.getUniqueId());
			}
		}

		// Spawn/despawn
		for (UUID id : new HashSet<>(spawnedTo)) {
			if (eligible.contains(id))
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p != null && p.isOnline()) {
				try {
					boardDisplay.removeViewer(p);
				} catch (Throwable ignored) {
				}
			}
			spawnedTo.remove(id);
		}

		for (UUID id : eligible) {
			if (spawnedTo.contains(id))
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				boardDisplay.ensureViewer(p);
				spawnedTo.add(id);
			} catch (Throwable ignored) {
			}
		}

		BufferedImage img = renderImage(active, placement.pixelWidth(), placement.pixelHeight());
		boardDisplay.renderAndFlush(img);
	}

	private BufferedImage renderImage(RaceEvent active, int w, int h) {
		BufferedImage img = renderBuffers.acquire(w, h);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

			g.setColor(new Color(0x0B0E14));
			g.fillRect(0, 0, w, h);

			Font title = titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18);
			Font body = bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14);

			g.setFont(title);
			FontMetrics fmTitle = g.getFontMetrics();
			g.setFont(body);
			FontMetrics fmBody = g.getFontMetrics();

			int pad = Math.max(8, (int) Math.round(Math.min(w, h) * 0.03));
			int x = pad;
			int y = pad + fmTitle.getAscent();

			String header = "SỰ KIỆN";
			String titleText = active != null && active.title != null && !active.title.isBlank() ? active.title : "(chưa có)";
			String state = eventStateDisplay(active != null ? active.state : null);

			g.setFont(title);
			g.setColor(new Color(0xF7C948));
			g.drawString(header, x, y);
			y += fmTitle.getHeight();

			g.setFont(body);
			g.setColor(new Color(0xE6E6E6));
			g.drawString("Tên: " + titleText, x, y);
			y += fmBody.getHeight();
			g.setColor(new Color(0xB0B0B0));
			g.drawString("Trạng thái: " + state, x, y);
			y += fmBody.getHeight();

			y += fmBody.getHeight() / 2;

			List<EventRankEntry> ranking = buildRanking(active);
			g.setColor(new Color(0xF7C948));
			g.drawString("Bảng điểm", x, y);
			y += fmBody.getHeight();

			int maxLines = Math.max(1, (h - y - pad) / fmBody.getHeight());
			int shown = 0;
			for (EventRankEntry it : ranking) {
				if (shown >= maxLines)
					break;
				String line = "#" + it.position + " " + it.name + "  (" + it.points + ")";
				g.setColor(new Color(0xE6E6E6));
				g.drawString(line, x, y);
				y += fmBody.getHeight();
				shown++;
			}

			if (ranking.isEmpty()) {
				g.setColor(new Color(0xB0B0B0));
				g.drawString("Chưa có tay đua tham gia.", x, y);
			}
		} finally {
			g.dispose();
		}
		return img;
	}

	private static String eventStateDisplay(EventState st) {
		if (st == null)
			return "-";
		return switch (st) {
			case DRAFT -> "Nháp";
			case REGISTRATION -> "Đang đăng ký";
			case RUNNING -> "Đang chạy";
			case COMPLETED -> "Đã kết thúc";
			case CANCELLED -> "Đã hủy";
		};
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

	private List<EventRankEntry> buildRanking(RaceEvent e) {
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
			String n = (ep.nameSnapshot == null || ep.nameSnapshot.isBlank()) ? nameOf(id) : ep.nameSnapshot;
			ranking.add(new EventRankEntry(id, n, Math.max(0, ep.pointsTotal)));
		}

		ranking.sort(Comparator
				.<EventRankEntry>comparingInt(a -> a == null ? 0 : a.points).reversed()
				.thenComparing(a -> a == null || a.name == null ? "" : a.name, String::compareToIgnoreCase)
				.thenComparing(a -> a == null || a.id == null ? new UUID(0L, 0L) : a.id));

		for (int i = 0; i < ranking.size(); i++) {
			ranking.get(i).position = i + 1;
		}
		return ranking;
	}

	private static String nameOf(UUID id) {
		try {
			var op = Bukkit.getOfflinePlayer(id);
			if (op != null && op.getName() != null)
				return op.getName();
		} catch (Throwable ignored) {
		}
		String s = id.toString();
		return s.substring(0, 8);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
