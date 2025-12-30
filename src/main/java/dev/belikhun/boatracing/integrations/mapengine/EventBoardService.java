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
import dev.belikhun.boatracing.integrations.mapengine.ui.ColumnContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.GraphicsElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.ImageElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.RowContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.TextElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiAlign;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiComposer;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiInsets;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiJustify;
import dev.belikhun.boatracing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Event information board rendered with MapEngine.
 *
 * Note: This service is intentionally small and mirrors LobbyBoardService's
 * viewer + rendering pipeline, but reads from EventService state.
 */
public final class EventBoardService {
	private enum Screen {
		EVENT_START_COUNTDOWN,
		NEXT_TRACK,
		CURRENT_RACE,
		EVENT_FINISHED
	}

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
	private Font fallbackFont;

	// Logo
	// Bundled logo resource path (inside plugin JAR)
	private static final String LOGO_RESOURCE_PATH = "imgs/logo.png";
	private BufferedImage logoImage;
	private long logoLastLoadMs;

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

		// Always use bundled logo; no config path.
		logoImage = null;
		logoLastLoadMs = 0L;

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

		// Fallback font: use system default (keeps Unicode support if user didn't provide a font)
		try {
			// Match the lobby board: use a monospaced fallback at the same size.
			fallbackFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, bodySize));
		} catch (Throwable ignored) {
			fallbackFont = null;
		}
	}

	private BufferedImage loadLogoIfNeeded() {
		// Always load from plugin resources.
		// Rate-limit resource reads (still useful if server reloads rapidly).
		long now = System.currentTimeMillis();
		if (logoImage != null && (now - logoLastLoadMs) < 5000L)
			return logoImage;

		logoLastLoadMs = now;
		try (java.io.InputStream is = plugin.getResource(LOGO_RESOURCE_PATH)) {
			if (is == null) {
				logoImage = null;
				return null;
			}
			BufferedImage img = ImageIO.read(is);
			logoImage = img;
			return img;
		} catch (Throwable t) {
			logoImage = null;
			if (debug) {
				try {
					plugin.getLogger().warning("[EventBoard] Không thể đọc logo trong plugin resources: " + LOGO_RESOURCE_PATH
							+ " (" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()) + ")");
				} catch (Throwable ignored) {
				}
			}
			return null;
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

	/**
	 * Player-facing diagnostics for /boatracing event board status.
	 */
	public java.util.List<String> statusLines() {
		java.util.List<String> out = new java.util.ArrayList<>();

		boolean enabled = false;
		try {
			enabled = plugin.getConfig().getBoolean("mapengine.event-board.enabled", false);
		} catch (Throwable ignored) {
			enabled = false;
		}
		boolean apiOk = MapEngineService.isAvailable();

		out.add("&eBảng sự kiện (MapEngine):");
		out.add("&7● MapEngine: " + (apiOk ? "&a✔" : "&c❌") + "&7 (" + (apiOk ? "có" : "không") + ")");
		out.add("&7● Trạng thái: " + (enabled ? "&aBật" : "&cTắt"));
		out.add("&7● Bán kính: &f" + visibleRadiusChunks + "&7 chunks");
		out.add("&7● Cập nhật: &f" + updateTicks + "&7 ticks");

		if (placement == null || !placement.isValid()) {
			out.add("&7● Vị trí: &cChưa đặt hoặc không hợp lệ");
		} else {
			out.add("&7● Vị trí: &a" + placement.world + " &7(" + placement.a.getBlockX() + "," + placement.a.getBlockY() + "," + placement.a.getBlockZ() + ")"
					+ " -> &7(" + placement.b.getBlockX() + "," + placement.b.getBlockY() + "," + placement.b.getBlockZ() + ")"
					+ " &8● &7hướng &f" + placement.facing);
			out.add("&7● Kích thước: &f" + placement.mapsWide + "&7x&f" + placement.mapsHigh + "&7 maps (&f"
					+ placement.pixelWidth() + "&7x&f" + placement.pixelHeight() + "&7 px)");
		}

		out.add("&7● Người đang thấy: &f" + spawnedTo.size());
		out.add("&7● Debug log: " + (debug ? "&aBật" : "&cTắt") + " &8(&7mapengine.event-board.debug&8)");
		return out;
	}

	public boolean setPlacementFromSelection(org.bukkit.entity.Player p, org.bukkit.util.BoundingBox box, BlockFace facing) {
		if (p == null || box == null)
			return false;
		if (p.getWorld() == null)
			return false;

		BlockFace dir;
		if (facing != null) {
			dir = normalizeCardinal(facing);
		} else {
			dir = autoFacingFromPlayer(p, box);
		}
		if (dir == null)
			return false;

		BoardPlacement pl = BoardPlacement.fromSelection(p.getWorld(), box, dir);
		if (pl == null || !pl.isValid())
			return false;

		pl.save(plugin.getConfig().createSection("mapengine.event-board.placement"));
		plugin.getConfig().set("mapengine.event-board.enabled", true);
		plugin.saveConfig();

		reloadFromConfig();

		// Preview instantly for the admin.
		try {
			previewTo(p);
		} catch (Throwable ignored) {
		}
		return true;
	}

	public void clearPlacement() {
		stop();
		plugin.getConfig().set("mapengine.event-board.enabled", false);
		plugin.getConfig().set("mapengine.event-board.placement", null);
		plugin.saveConfig();
		loadConfig();
	}

	public String placementSummary() {
		if (placement == null || !placement.isValid())
			return "&cChưa đặt bảng.";
		return "&aĐã đặt bảng tại &f" + placement.world
				+ " &7(" + placement.a.getBlockX() + "," + placement.a.getBlockY() + "," + placement.a.getBlockZ() + ")"
				+ " -> &7(" + placement.b.getBlockX() + "," + placement.b.getBlockY() + "," + placement.b.getBlockZ() + ")"
				+ " &8● &7hướng &f" + placement.facing;
	}

	private static BlockFace normalizeCardinal(BlockFace face) {
		if (face == null)
			return null;
		return switch (face) {
			case NORTH, SOUTH, EAST, WEST -> face;
			default -> null;
		};
	}

	private static BlockFace autoFacingFromPlayer(org.bukkit.entity.Player p, org.bukkit.util.BoundingBox box) {
		if (p == null || box == null)
			return null;
		org.bukkit.Location loc = p.getLocation();
		if (loc == null)
			return null;

		int minX = (int) Math.floor(Math.min(box.getMinX(), box.getMaxX()));
		int maxX = (int) Math.floor(Math.max(box.getMinX(), box.getMaxX()));
		int minZ = (int) Math.floor(Math.min(box.getMinZ(), box.getMaxZ()));
		int maxZ = (int) Math.floor(Math.max(box.getMinZ(), box.getMaxZ()));

		int dx = maxX - minX;
		int dz = maxZ - minZ;

		double cx = (minX + maxX) * 0.5;
		double cz = (minZ + maxZ) * 0.5;
		double px = loc.getX();
		double pz = loc.getZ();

		if (dx == 0 && dz > 0) {
			return (px >= cx) ? BlockFace.EAST : BlockFace.WEST;
		}
		if (dz == 0 && dx > 0) {
			return (pz >= cz) ? BlockFace.SOUTH : BlockFace.NORTH;
		}

		BlockFace looking = yawToCardinal(loc.getYaw());
		if (looking == null)
			return BlockFace.SOUTH;
		return looking.getOppositeFace();
	}

	private static BlockFace yawToCardinal(float yaw) {
		float y = yaw;
		y = (y % 360.0f + 360.0f) % 360.0f;
		// 0=south, 90=west, 180=north, 270=east
		if (y >= 315.0f || y < 45.0f)
			return BlockFace.SOUTH;
		if (y < 135.0f)
			return BlockFace.WEST;
		if (y < 225.0f)
			return BlockFace.NORTH;
		return BlockFace.EAST;
	}

	private void previewTo(org.bukkit.entity.Player p) {
		if (p == null || !p.isOnline())
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

		try {
			boardDisplay.ensureViewer(p);
			spawnedTo.add(p.getUniqueId());
		} catch (Throwable ignored) {
		}

		RaceEvent active = null;
		try {
			active = eventService != null ? eventService.getActiveEvent() : null;
		} catch (Throwable ignored) {
			active = null;
		}

		try {
			BufferedImage img = renderUi(active, placement.pixelWidth(), placement.pixelHeight());
			boardDisplay.renderAndFlush(img);
		} catch (Throwable ignored) {
		}
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

		BufferedImage img = renderUi(active, placement.pixelWidth(), placement.pixelHeight());
		boardDisplay.renderAndFlush(img);
	}

	private BufferedImage renderUi(RaceEvent active, int w, int h) {
		BufferedImage img = renderBuffers.acquire(w, h);
		UiElement root = buildRoot(active, w, h);
		UiComposer.renderInto(img, root, bodyFont, fallbackFont);
		return img;
	}

	private UiElement buildRoot(RaceEvent active, int w, int h) {
		Screen screen = decideScreen(active);
		return switch (screen) {
			case EVENT_START_COUNTDOWN -> buildEventStartCountdownUi(active, w, h);
			case NEXT_TRACK -> buildNextTrackUi(active, w, h);
			case CURRENT_RACE -> buildCurrentRaceUi(active, w, h);
			case EVENT_FINISHED -> buildEventFinishedUi(active, w, h);
		};
	}

	private Screen decideScreen(RaceEvent active) {
		if (active == null)
			return Screen.EVENT_START_COUNTDOWN;

		EventState st = active.state;
		if (st == EventState.COMPLETED)
			return Screen.EVENT_FINISHED;
		if (st == EventState.CANCELLED)
			return Screen.EVENT_FINISHED;

		if (st == EventState.REGISTRATION || st == EventState.DRAFT) {
			return Screen.EVENT_START_COUNTDOWN;
		}

		if (st != EventState.RUNNING)
			return Screen.EVENT_START_COUNTDOWN;

		// RUNNING: determine if we're currently in a race.
		String track = null;
		boolean countdownStarted = false;
		try {
			track = eventService != null ? eventService.getActiveTrackNameRuntime() : null;
			countdownStarted = eventService != null && eventService.isTrackCountdownStarted();
		} catch (Throwable ignored) {
			track = null;
			countdownStarted = false;
		}

		if (track == null || track.isBlank())
			return Screen.NEXT_TRACK;

		// If countdown not started yet or race isn't running, show "next track" screen.
		if (!countdownStarted)
			return Screen.NEXT_TRACK;

		try {
			if (plugin != null && plugin.getRaceService() != null) {
				RaceManager rm = plugin.getRaceService().getOrCreate(track);
				if (rm != null && rm.isRunning())
					return Screen.CURRENT_RACE;
			}
		} catch (Throwable ignored) {
		}
		return Screen.NEXT_TRACK;
	}

	private static String safeEventName(RaceEvent e) {
		if (e == null)
			return "(chưa có)";
		String t = e.title;
		if (t == null)
			return "(chưa có)";
		t = t.trim();
		return t.isEmpty() ? "(chưa có)" : t;
	}

	private static String fmtEventMeta(RaceEvent e) {
		return fmtEventMetaVi(e);
	}

	private static String fmtEventMetaVi(RaceEvent e) {
		int racers = 0;
		try {
			if (e != null && e.participants != null) {
				for (EventParticipant ep : e.participants.values()) {
					if (ep == null || ep.id == null)
						continue;
					if (ep.status == EventParticipantStatus.LEFT)
						continue;
					racers++;
				}
			}
		} catch (Throwable ignored) {
			racers = 0;
		}
		int tracks = 0;
		try {
			tracks = (e != null && e.trackPool != null) ? e.trackPool.size() : 0;
		} catch (Throwable ignored) {
			tracks = 0;
		}

		String when = "-";
		try {
			if (e != null && e.startTimeMillis > 0L) {
				java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(e.startTimeMillis)
						.atZone(java.time.ZoneId.systemDefault());
				when = String.format(java.util.Locale.ROOT, "%02d/%02d/%04d %02d:%02d",
						z.getDayOfMonth(), z.getMonthValue(), z.getYear(), z.getHour(), z.getMinute());
			}
		} catch (Throwable ignored) {
			when = "-";
		}

		String whenLabel = (when == null || when.isBlank() || when.equals("-"))
				? "Chưa lên lịch"
				: ("Bắt đầu: " + when);

		return "Tay đua: " + racers
				+ " ● Đường đua: " + tracks
				+ " ● " + whenLabel;
	}

	private static String formatHms(long seconds) {
		long s = Math.max(0L, seconds);
		long h = s / 3600L;
		s %= 3600L;
		long m = s / 60L;
		long sec = s % 60L;
		return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, sec);
	}

	private long secondsUntilEventStart(RaceEvent e) {
		if (e == null)
			return 0L;
		long start = e.startTimeMillis;
		if (start <= 0L)
			return 0L;
		long now = System.currentTimeMillis();
		return Math.max(0L, (start - now + 999L) / 1000L);
	}

	private long secondsUntilNextPhase(RaceEvent e) {
		long now = System.currentTimeMillis();
		long end = 0L;
		try {
			if (eventService != null) {
				long intro = eventService.getIntroEndMillis();
				long lobby = eventService.getLobbyWaitEndMillis();
				long brk = eventService.getBreakEndMillis();
				if (intro > now)
					end = intro;
				else if (lobby > now)
					end = lobby;
				else if (brk > now)
					end = brk;
			}
		} catch (Throwable ignored) {
			end = 0L;
		}
		if (end <= now)
			return 0L;
		return (end - now + 999L) / 1000L;
	}

	private UiElement buildEventStartCountdownUi(RaceEvent e, int w, int h) {
		int pad = Math.max(16, (int) Math.round(Math.min(w, h) * 0.03));
		int gap = Math.max(6, (int) Math.round(pad * 0.35));

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));
		Font big = title.deriveFont(Font.BOLD, Math.max(38f, title.getSize2D() * 2.2f));

		ColumnContainer root = new ColumnContainer()
				.gap(gap)
				.alignItems(UiAlign.STRETCH)
				.justifyContent(UiJustify.START);
		root.style().background(new Color(0xFFFFFF)).padding(UiInsets.all(pad));

		// Header
		ColumnContainer header = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.START);
		header.add(text("Sự kiện", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.1f)), new Color(0x6E6E6E), TextElement.Align.LEFT));
		header.add(text("Đếm ngược bắt đầu", body, new Color(0x808080), TextElement.Align.LEFT));
		root.add(header);

		// Body (center)
		ColumnContainer bodyCol = new ColumnContainer()
				.gap(Math.max(8, gap))
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.CENTER);
		bodyCol.style().flexGrow(1);

		// Logo
		int logoW = Math.max(140, (int) Math.round(w * 0.28));
		int logoH = Math.max(60, (int) Math.round(h * 0.10));
		logoW = Math.min(logoW, Math.max(140, w - pad * 2));
		logoH = Math.min(logoH, Math.max(60, (int) Math.round(h * 0.16)));
		BufferedImage logo = loadLogoIfNeeded();
		ImageElement logoEl = new ImageElement(logo)
				.fit(ImageElement.Fit.CONTAIN)
				.smoothing(true);
		logoEl.style()
				.widthPx(logoW)
				.heightPx(logoH)
				.padding(UiInsets.all(Math.max(8, (int) Math.round(Math.min(logoW, logoH) * 0.12))))
				.background(Color.WHITE)
				.border(new Color(0x6E6E6E), 2);
		if (logo == null)
			logoEl.style().display(false);
		bodyCol.add(logoEl);

		// Event name + meta
		bodyCol.add(text("<" + safeEventName(e) + ">",
				title.deriveFont(Font.BOLD, Math.max(22f, title.getSize2D() * 1.4f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		bodyCol.add(text(fmtEventMetaVi(e), body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), new Color(0x808080), TextElement.Align.CENTER));

		// Start in + timer row
		bodyCol.add(text("Bắt đầu sau", title.deriveFont(Font.BOLD, Math.max(20f, title.getSize2D() * 1.25f)), new Color(0x6E6E6E), TextElement.Align.CENTER));

		long sec = secondsUntilEventStart(e);
		String timer = formatHms(sec);
		int iconSize = Math.max(40, (int) (Math.min(w, h) * 0.07));
		RowContainer timerRow = new RowContainer().gap(Math.max(10, gap)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		GraphicsElement hourglass = new GraphicsElement((ctx, rect) -> {
			if (ctx == null || ctx.g == null)
				return;
			drawHourglassIcon(ctx.g, rect.x(), rect.y(), Math.min(rect.w(), rect.h()));
		});
		hourglass.style().widthPx(iconSize).heightPx(iconSize);
		timerRow.add(hourglass);
		timerRow.add(text(timer, big, new Color(0x7A7A7A), TextElement.Align.LEFT));
		bodyCol.add(timerRow);

		root.add(bodyCol);

		// Footer (bottom-right time)
		RowContainer footer = new RowContainer().gap(0).alignItems(UiAlign.END).justifyContent(UiJustify.END);
		footer.style().flexGrow(0);
		String nowText = nowClockString();
		footer.add(text(nowText, body.deriveFont(Font.PLAIN, Math.max(12f, body.getSize2D())), new Color(0x808080), TextElement.Align.RIGHT));
		root.add(footer);

		return root;
	}

	private UiElement buildNextTrackUi(RaceEvent e, int w, int h) {
		int pad = Math.max(16, (int) Math.round(Math.min(w, h) * 0.03));
		int gap = Math.max(6, (int) Math.round(pad * 0.35));

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));
		Font big = title.deriveFont(Font.BOLD, Math.max(34f, title.getSize2D() * 2.0f));
		Font huge = title.deriveFont(Font.BOLD, Math.max(46f, title.getSize2D() * 2.6f));

		ColumnContainer root = new ColumnContainer().gap(gap).alignItems(UiAlign.STRETCH);
		root.style().background(new Color(0xFFFFFF)).padding(UiInsets.all(pad));

		ColumnContainer header = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.START);
		header.add(text("Sự kiện", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.1f)), new Color(0x6E6E6E), TextElement.Align.LEFT));
		header.add(text("Chặng tiếp theo", body, new Color(0x808080), TextElement.Align.LEFT));
		root.add(header);

		ColumnContainer bodyCol = new ColumnContainer().gap(Math.max(8, gap)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		bodyCol.style().flexGrow(1);
		bodyCol.add(text("<" + safeEventName(e) + ">",
				title.deriveFont(Font.BOLD, Math.max(22f, title.getSize2D() * 1.3f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		bodyCol.add(text(fmtEventMetaVi(e), body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), new Color(0x808080), TextElement.Align.CENTER));

		String track = (e != null ? e.currentTrackName() : null);
		if (track == null || track.isBlank())
			track = "-";
		bodyCol.add(text("Cuộc đua tiếp theo", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.15f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		bodyCol.add(text(track.toUpperCase(java.util.Locale.ROOT), big, new Color(0x7A7A7A), TextElement.Align.CENTER));

		long sec = secondsUntilNextPhase(e);
		bodyCol.add(text("Bắt đầu sau", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.15f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		bodyCol.add(text(formatHms(sec), huge, new Color(0x7A7A7A), TextElement.Align.CENTER));

		List<EventRankEntry> ranking = buildRanking(e);
		bodyCol.add(text("Bảng điểm", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), new Color(0x6E6E6E), TextElement.Align.CENTER));

		int maxRows = Math.max(3, (int) Math.round((double) h / (double) Math.max(1, body.getSize()) / 2.2));
		ColumnContainer list = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.CENTER);
		int shown = 0;
		for (EventRankEntry it : ranking) {
			if (shown >= maxRows)
				break;
			String line = String.format(java.util.Locale.ROOT, "#%d  %s  ●  %d điểm", it.position, it.name, it.points);
			list.add(text(line, body, new Color(0x808080), TextElement.Align.CENTER));
			shown++;
		}
		bodyCol.add(list);
		root.add(bodyCol);
		return root;
	}

	private UiElement buildCurrentRaceUi(RaceEvent e, int w, int h) {
		int pad = Math.max(16, (int) Math.round(Math.min(w, h) * 0.03));
		int gap = Math.max(6, (int) Math.round(pad * 0.35));

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));
		Font big = title.deriveFont(Font.BOLD, Math.max(34f, title.getSize2D() * 2.0f));
		Font huge = title.deriveFont(Font.BOLD, Math.max(46f, title.getSize2D() * 2.6f));

		ColumnContainer root = new ColumnContainer().gap(gap).alignItems(UiAlign.STRETCH);
		root.style().background(new Color(0xFFFFFF)).padding(UiInsets.all(pad));

		ColumnContainer header = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.START);
		header.add(text("SỰ KIỆN", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.1f)), new Color(0x6E6E6E), TextElement.Align.LEFT));
		header.add(text("CUỘC ĐUA ĐANG DIỄN RA", body, new Color(0x808080), TextElement.Align.LEFT));
		root.add(header);

		String track = null;
		try {
			track = eventService != null ? eventService.getActiveTrackNameRuntime() : null;
		} catch (Throwable ignored) {
			track = null;
		}
		if (track == null)
			track = (e != null ? e.currentTrackName() : null);
		if (track == null || track.isBlank())
			track = "-";

		long elapsedMs = 0L;
		try {
			if (plugin != null && plugin.getRaceService() != null) {
				RaceManager rm = plugin.getRaceService().getOrCreate(track);
				if (rm != null)
					elapsedMs = rm.getRaceElapsedMillis();
			}
		} catch (Throwable ignored) {
			elapsedMs = 0L;
		}
		String timer = dev.belikhun.boatracing.util.Time.formatStopwatchMillis(Math.max(0L, elapsedMs));

		ColumnContainer bodyCol = new ColumnContainer().gap(Math.max(10, gap)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		bodyCol.style().flexGrow(1);
		bodyCol.add(text("<" + safeEventName(e) + ">",
				title.deriveFont(Font.BOLD, Math.max(22f, title.getSize2D() * 1.3f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		bodyCol.add(text(track.toUpperCase(java.util.Locale.ROOT), big, new Color(0x7A7A7A), TextElement.Align.CENTER));
		bodyCol.add(text("THỜI GIAN ĐÃ TRÔI QUA", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.15f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		bodyCol.add(text(timer, huge, new Color(0x7A7A7A), TextElement.Align.CENTER));
		root.add(bodyCol);
		return root;
	}

	private UiElement buildEventFinishedUi(RaceEvent e, int w, int h) {
		int pad = Math.max(16, (int) Math.round(Math.min(w, h) * 0.03));
		int gap = Math.max(6, (int) Math.round(pad * 0.35));

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));

		ColumnContainer root = new ColumnContainer().gap(gap).alignItems(UiAlign.STRETCH);
		root.style().background(new Color(0xFFFFFF)).padding(UiInsets.all(pad));

		ColumnContainer header = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.START);
		header.add(text("SỰ KIỆN", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.1f)), new Color(0x6E6E6E), TextElement.Align.LEFT));
		header.add(text("ĐÃ KẾT THÚC", body, new Color(0x808080), TextElement.Align.LEFT));
		root.add(header);

		List<EventRankEntry> ranking = buildRanking(e);

		ColumnContainer bodyCol = new ColumnContainer().gap(Math.max(8, gap)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		bodyCol.style().flexGrow(1);
		bodyCol.add(text("<" + safeEventName(e) + ">",
				title.deriveFont(Font.BOLD, Math.max(22f, title.getSize2D() * 1.3f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		bodyCol.add(text("TOP 3", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.15f)), new Color(0x6E6E6E), TextElement.Align.CENTER));

		for (int i = 0; i < Math.min(3, ranking.size()); i++) {
			EventRankEntry it = ranking.get(i);
			String line = String.format(java.util.Locale.ROOT, "#%d  %s  ●  %d điểm", it.position, it.name, it.points);
			bodyCol.add(text(line, body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), new Color(0x808080), TextElement.Align.CENTER));
		}

		bodyCol.add(text("Xếp hạng cuối", title.deriveFont(Font.BOLD, Math.max(18f, title.getSize2D() * 1.15f)), new Color(0x6E6E6E), TextElement.Align.CENTER));
		int maxRows = Math.max(6, (int) Math.round((double) h / (double) Math.max(1, body.getSize()) / 1.9));
		ColumnContainer list = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.CENTER);
		int shown = 0;
		for (EventRankEntry it : ranking) {
			if (shown >= maxRows)
				break;
			String line = String.format(java.util.Locale.ROOT, "#%d  %s  ●  %d điểm", it.position, it.name, it.points);
			list.add(text(line, body, new Color(0x808080), TextElement.Align.CENTER));
			shown++;
		}
		bodyCol.add(list);
		root.add(bodyCol);
		return root;
	}

	private TextElement text(String s, Font font, Color color, TextElement.Align align) {
		TextElement t = new TextElement(s);
		t.font(font).color(color).align(align == null ? TextElement.Align.LEFT : align).ellipsis(true);
		return t;
	}

	private static String nowClockString() {
		try {
			java.time.LocalTime t = java.time.LocalTime.now();
			int hr12 = t.getHour() % 12;
			if (hr12 == 0)
				hr12 = 12;
			return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d %s",
					hr12, t.getMinute(), t.getSecond(), (t.getHour() >= 12 ? "PM" : "AM"));
		} catch (Throwable ignored) {
			return "";
		}
	}

	private static void drawHourglassIcon(java.awt.Graphics2D g, int x, int y, int size) {
		if (g == null || size <= 0)
			return;
		int s = size;
		int stroke = Math.max(3, s / 14);
		int pad = Math.max(2, s / 12);
		int innerX = x + pad;
		int innerY = y + pad;
		int innerW = s - pad * 2;
		int innerH = s - pad * 2;

		g.setColor(new Color(0x111111));
		g.fillRoundRect(x, y, s, s, s / 5, s / 5);
		g.setColor(new Color(0xFFFFFF));
		g.fillRoundRect(x + stroke, y + stroke, s - stroke * 2, s - stroke * 2, s / 6, s / 6);
		g.setColor(new Color(0x111111));
		int midY = y + s / 2;
		g.drawLine(innerX, innerY, innerX + innerW, innerY);
		g.drawLine(innerX, innerY + innerH, innerX + innerW, innerY + innerH);
		g.drawLine(innerX, innerY, x + s / 2, midY);
		g.drawLine(innerX + innerW, innerY, x + s / 2, midY);
		g.drawLine(innerX, innerY + innerH, x + s / 2, midY);
		g.drawLine(innerX + innerW, innerY + innerH, x + s / 2, midY);

		g.setColor(new Color(0xF2C94C));
		int sandW = innerW / 2;
		int sandH = innerH / 4;
		g.fillOval(x + s / 2 - sandW / 2, y + s / 2 + sandH / 2, sandW, sandH);
		g.setColor(new Color(0xF2994A));
		g.fillOval(x + s / 2 - sandW / 2, y + s / 2 - sandH, sandW, sandH);
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
