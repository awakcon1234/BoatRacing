package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardFontLoader;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardPlacement;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardViewers;
import dev.belikhun.boatracing.integrations.mapengine.board.MapEngineBoardDisplay;
import dev.belikhun.boatracing.integrations.mapengine.board.RenderBuffers;
import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.profile.PlayerProfileManager;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.race.RaceService;
import dev.belikhun.boatracing.track.TrackLibrary;
import dev.belikhun.boatracing.track.TrackRecordManager;
import dev.belikhun.boatracing.util.ColorTranslator;
import dev.belikhun.boatracing.util.Time;
import dev.belikhun.boatracing.integrations.mapengine.ui.ColumnContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.GraphicsElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.RowContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.TextElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiAlign;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiInsets;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiJustify;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiRenderContext;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiRect;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Lobby information board rendered with MapEngine.
 *
 * Visibility rules (per user request):
 * - Only for online players who are currently in the lobby (not in any race)
 * - AND are within a configurable chunk radius (default: 12 chunks) of the
 * board
 */
public final class LobbyBoardService {
	// Icons used on the lobby board UI (Unicode). These are rendered with font
	// fallback.
	private static final String ICON_INFO = "ⓘ";
	private static final String ICON_CLOCK = "⏰";

	private final RenderBuffers renderBuffers = new RenderBuffers();

	private BufferedImage acquireRenderBuffer(int w, int h) {
		return renderBuffers.acquire(w, h);
	}

	// Inner padding for left track rows (moves the leading status dot away from the
	// border)
	private static final int TRACK_ROW_INNER_PAD = 8;

	private static int trackRowInnerPad(Font bodyFont) {
		int size = (bodyFont != null ? bodyFont.getSize() : 16);
		// Scale gently with font size so spacing remains consistent across board
		// resolutions.
		return Math.max(TRACK_ROW_INNER_PAD, (int) Math.round(size * 0.40));
	}

	private static float minimapStrokeFromBorder(int border) {
		// Border already scales with overall UI; derive minimap stroke from it.
		// Clamp to keep the map readable without becoming chunky.
		float b = Math.max(1, border);
		float s = b * 0.90f;
		if (s < 1.5f)
			s = 1.5f;
		if (s > 6.0f)
			s = 6.0f;
		return s;
	}

	private final BoatRacingPlugin plugin;
	private final RaceService raceService;
	private final TrackLibrary trackLibrary;
	private final PlayerProfileManager profileManager;

	private BukkitTask tickTask;
	private final MapEngineBoardDisplay boardDisplay = new MapEngineBoardDisplay();

	private BoardPlacement placement;
	private int visibleRadiusChunks = 12;
	private int updateTicks = 20;
	private boolean debug = false;

	// Perf debug (rate-limited, only when debug=true)
	private long lastPerfLogMillis = 0L;
	private long lastPerfCollectNs = 0L;
	private long lastPerfUpdateFrameNs = 0L;
	private long lastPerfLayoutNs = 0L;
	private long lastPerfRenderNs = 0L;
	private long lastPerfTotalNs = 0L;
	private int lastPerfW = 0;
	private int lastPerfH = 0;

	// MapEngine drawing pipeline toggles
	private boolean mapBuffering = true;
	private boolean mapBundling = false;

	// Optional custom font (Minecraft-style). We do not ship a font file; users can
	// provide one.
	private String fontFile;
	private volatile Font boardFontBase;
	private volatile Font boardFallbackFont;

	// Cached, DOM-like UI tree to avoid rebuilding UI elements every tick.
	// Rebuilt only when board size or font sizing changes.
	private UiCache uiCache;

	private long lastDebugTickLogMillis = 0L;

	private final Set<UUID> spawnedTo = new HashSet<>();
	private final Set<UUID> eligibleViewers = new HashSet<>();
	private static final long REENSURE_EXISTING_VIEWERS_MS = 5000L;
	private long lastReensureAtMs = 0L;

	private void invalidateUiCache() {
		uiCache = null;
	}

	public LobbyBoardService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.raceService = plugin.getRaceService();
		this.trackLibrary = plugin.getTrackLibrary();
		this.profileManager = plugin.getProfileManager();
	}

	public void reloadFromConfig() {
		stop();
		loadConfig();
		start();
	}

	private void loadConfig() {
		placement = BoardPlacement.load(plugin.getConfig().getConfigurationSection("mapengine.lobby-board"));
		visibleRadiusChunks = clamp(plugin.getConfig().getInt("mapengine.lobby-board.visible-radius-chunks", 12), 1,
				64);
		updateTicks = clamp(plugin.getConfig().getInt("mapengine.lobby-board.update-ticks", 20), 1, 200);
		debug = plugin.getConfig().getBoolean("mapengine.lobby-board.debug", false);

		// UiComposer perf logging (global/static): gate behind the same debug toggle.
		try {
			dev.belikhun.boatracing.integrations.mapengine.ui.UiComposer.setPerfDebug(debug,
					debug ? (m) -> {
						try {
							plugin.getLogger().info("[LobbyBoard] " + m);
						} catch (Throwable ignored) {
						}
					} : null);
		} catch (Throwable ignored) {
		}

		// MapEngine pipeline options
		mapBuffering = plugin.getConfig().getBoolean("mapengine.lobby-board.pipeline.buffering", true);
		mapBundling = plugin.getConfig().getBoolean("mapengine.lobby-board.pipeline.bundling", false);

		String ff = null;
		try {
			ff = plugin.getConfig().getString("mapengine.lobby-board.font-file", null);
		} catch (Throwable ignored) {
			ff = null;
		}
		if (ff != null) {
			ff = ff.trim();
			if (ff.isEmpty())
				ff = null;
		}
		this.fontFile = ff;
		this.boardFontBase = null;
		this.boardFallbackFont = null;
		tryLoadBoardFont();

		// Config or font changes can affect layout; rebuild cached UI next render.
		invalidateUiCache();
	}

	public void start() {
		if (tickTask != null)
			return;

		if (!plugin.getConfig().getBoolean("mapengine.lobby-board.enabled", false)) {
			dbg("start(): disabled by config");
			return;
		}
		if (placement == null || !placement.isValid()) {
			dbg("start(): placement missing/invalid");
			return;
		}

		MapEngineApi api = MapEngineService.get();
		if (api == null) {
			dbg("start(): MapEngineApi not available (is MapEngine installed/enabled?)");
			return;
		}

		ensureDisplay(api);
		if (!boardDisplay.isReady()) {
			dbg("start(): failed to create display/drawing");
			return;
		}

		tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, updateTicks);
		dbg("start(): started tick task (updateTicks=" + updateTicks + ") maps="
				+ placement.mapsWide + "x" + placement.mapsHigh + " px=" + placement.pixelWidth() + "x"
				+ placement.pixelHeight());
	}

	public void stop() {
		if (tickTask != null) {
			try {
				tickTask.cancel();
			} catch (Throwable ignored) {
			}
			tickTask = null;
		}

		// despawn for everyone
		for (UUID id : new HashSet<>(spawnedTo)) {
			Player p = Bukkit.getPlayer(id);
			if (p != null && p.isOnline()) {
				try {
					despawnFor(p);
				} catch (Throwable ignored) {
				}
			}
		}
		spawnedTo.clear();
		eligibleViewers.clear();

		// best-effort destroy the display object (API varies between versions)
		boardDisplay.destroy();

		// Drop cached UI tree so it can be rebuilt with a clean state next start.
		invalidateUiCache();

		dbg("stop(): stopped");
	}

	/**
	 * Player-facing diagnostics for /boatracing board status.
	 */
	public java.util.List<String> statusLines() {
		java.util.List<String> out = new java.util.ArrayList<>();

		boolean enabled = false;
		try {
			enabled = plugin.getConfig().getBoolean("mapengine.lobby-board.enabled", false);
		} catch (Throwable ignored) {
			enabled = false;
		}
		boolean apiOk = MapEngineService.isAvailable();

		out.add("&eBảng thông tin sảnh (MapEngine):");
		out.add("&7● MapEngine: " + (apiOk ? "&a✔" : "&c❌") + "&7 (" + (apiOk ? "có" : "không") + ")");
		out.add("&7● Trạng thái: " + (enabled ? "&aBật" : "&cTắt"));
		out.add("&7● Bán kính: &f" + visibleRadiusChunks + "&7 chunks");
		out.add("&7● Cập nhật: &f" + updateTicks + "&7 ticks");

		if (placement == null || !placement.isValid()) {
			out.add("&7● Vị trí: &cChưa đặt hoặc không hợp lệ");
		} else {
			out.add("&7● Vị trí: &a" + placement.world + " &7(" + placement.a.getBlockX() + ","
					+ placement.a.getBlockY() + "," + placement.a.getBlockZ() + ")"
					+ " -> &7(" + placement.b.getBlockX() + "," + placement.b.getBlockY() + ","
					+ placement.b.getBlockZ() + ")"
					+ " &8● &7hướng &f" + placement.facing);
			out.add("&7● Kích thước: &f" + placement.mapsWide + "&7x&f" + placement.mapsHigh + "&7 maps (&f"
					+ placement.pixelWidth() + "&7x&f" + placement.pixelHeight() + "&7 px)");
		}

		out.add("&7● Người đang thấy: &f" + spawnedTo.size());
		out.add("&7● Debug log: " + (debug ? "&aBật" : "&cTắt") + " &8(&7mapengine.lobby-board.debug&8)");
		try {
			Font f = boardFontBase;
			out.add("&7● Font: &f" + (f != null ? (f.getFontName() + " (" + f.getFamily() + ")") : "Mặc định"));
			if (fontFile != null)
				out.add("&7● Font file: &f" + fontFile);
			else
				out.add("&7● Font file: &8(chưa cấu hình)");
		} catch (Throwable ignored) {
		}
		return out;
	}

	private void tryLoadBoardFont() {
		if (boardFontBase != null)
			return;
		Font f = BoardFontLoader.tryLoadBoardFont(plugin, fontFile, debug ? this::dbg : null);
		if (f != null)
			boardFontBase = f;
	}

	private Font boardPlain(int size) {
		int s = Math.max(8, size);
		Font base = boardFontBase;
		if (base == null)
			return new Font(Font.MONOSPACED, Font.PLAIN, s);
		try {
			return base.deriveFont(Font.PLAIN, (float) s);
		} catch (Throwable ignored) {
			return new Font(Font.MONOSPACED, Font.PLAIN, s);
		}
	}

	private Font boardBold(int size) {
		int s = Math.max(8, size);
		Font base = boardFontBase;
		if (base == null)
			return new Font(Font.MONOSPACED, Font.BOLD, s);
		try {
			return base.deriveFont(Font.BOLD, (float) s);
		} catch (Throwable ignored) {
			return base.deriveFont(Font.PLAIN, (float) s);
		}
	}

	public boolean setPlacementFromSelection(org.bukkit.entity.Player p, org.bukkit.util.BoundingBox box,
			BlockFace facing) {
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

		// persist
		pl.save(plugin.getConfig().createSection("mapengine.lobby-board.placement"));
		plugin.getConfig().set("mapengine.lobby-board.enabled", true);
		plugin.saveConfig();

		// reload running service
		reloadFromConfig();

		// Preview: spawn/render immediately for the setter so they can confirm
		// placement instantly.
		try {
			previewTo(p);
		} catch (Throwable ignored) {
		}
		return true;
	}

	private void previewTo(org.bukkit.entity.Player p) {
		if (p == null || !p.isOnline())
			return;
		if (!plugin.getConfig().getBoolean("mapengine.lobby-board.enabled", false))
			return;
		if (placement == null || !placement.isValid())
			return;

		MapEngineApi api = MapEngineService.get();
		if (api == null)
			return;

		ensureDisplay(api);
		if (!boardDisplay.isReady())
			return;

		// Always show preview to the admin who just placed it, regardless of
		// lobby/radius filters.
		try {
			spawnFor(p);
		} catch (Throwable ignored) {
		}
		try {
			spawnedTo.add(p.getUniqueId());
		} catch (Throwable ignored) {
		}

		try {
			BufferedImage img = renderImage(placement.pixelWidth(), placement.pixelHeight());
			boardDisplay.renderAndFlush(img);
		} catch (Throwable ignored) {
		}
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

		// If selection is a thin vertical plane, determine facing from which side the
		// player is on.
		double cx = (minX + maxX) * 0.5;
		double cz = (minZ + maxZ) * 0.5;
		double px = loc.getX();
		double pz = loc.getZ();

		if (dx == 0 && dz > 0) {
			// Plane is constant X => facing must be EAST/WEST.
			return (px >= cx) ? BlockFace.EAST : BlockFace.WEST;
		}
		if (dz == 0 && dx > 0) {
			// Plane is constant Z => facing must be NORTH/SOUTH.
			return (pz >= cz) ? BlockFace.SOUTH : BlockFace.NORTH;
		}

		// Otherwise, pick based on where the player is looking: board should face the
		// player.
		float yaw = loc.getYaw();
		BlockFace looking = yawToCardinal(yaw);
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

	public void clearPlacement() {
		stop();
		plugin.getConfig().set("mapengine.lobby-board.enabled", false);
		plugin.getConfig().set("mapengine.lobby-board.placement", null);
		plugin.saveConfig();
		loadConfig();
	}

	public String placementSummary() {
		if (placement == null || !placement.isValid())
			return "&cChưa đặt bảng.";
		return "&aĐã đặt bảng tại &f" + placement.world
				+ " &7(" + placement.a.getBlockX() + "," + placement.a.getBlockY() + "," + placement.a.getBlockZ() + ")"
				+ " -> &7(" + placement.b.getBlockX() + "," + placement.b.getBlockY() + "," + placement.b.getBlockZ()
				+ ")"
				+ " &8● &7hướng &f" + placement.facing;
	}

	private void tick() {
		if (placement == null || !placement.isValid())
			return;
		MapEngineApi api = MapEngineService.get();
		if (api == null) {
			dbg("tick(): MapEngineApi became unavailable; stopping");
			stop();
			return;
		}

		ensureDisplay(api);
		if (!boardDisplay.isReady())
			return;

		// Determine eligible viewers.
		eligibleViewers.clear();
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p == null || !p.isOnline() || p.getWorld() == null)
				continue;

			// Only lobby players (not in any race).
			if (raceService != null && raceService.findRaceFor(p.getUniqueId()) != null)
				continue;

			if (!BoardViewers.isWithinRadiusChunks(p, placement, visibleRadiusChunks))
				continue;
			eligibleViewers.add(p.getUniqueId());
		}

		dbgTick("tick(): eligible=" + eligibleViewers.size() + " spawnedTo=" + spawnedTo.size());

		// Despawn players that are no longer eligible.
		for (java.util.Iterator<UUID> it = spawnedTo.iterator(); it.hasNext();) {
			UUID id = it.next();
			if (eligibleViewers.contains(id))
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p != null && p.isOnline()) {
				try {
					despawnFor(p);
				} catch (Throwable ignored) {
				}
			}
			it.remove();
		}

		long now = System.currentTimeMillis();
		boolean reensure = (now - lastReensureAtMs) >= REENSURE_EXISTING_VIEWERS_MS;
		if (reensure)
			lastReensureAtMs = now;

		// Spawn to new eligible viewers.
		for (UUID id : eligibleViewers) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				// Ensure for newly eligible viewers; periodically re-ensure existing viewers to
				// stay robust against teleports/world loads without doing it every tick.
				if (reensure || !spawnedTo.contains(id))
					spawnFor(p);
			} catch (Throwable ignored) {
			}
			spawnedTo.add(id);
		}

		if (spawnedTo.isEmpty())
			return;

		// Render content and flush to receivers.
		try {
			final boolean doPerf = debug;
			final long t0 = doPerf ? System.nanoTime() : 0L;
			BufferedImage img = renderImage(placement.pixelWidth(), placement.pixelHeight());
			final long t1 = doPerf ? System.nanoTime() : 0L;
			boardDisplay.renderAndFlush(img);
			final long t2 = doPerf ? System.nanoTime() : 0L;
			final long t3 = t2;

			if (doPerf) {
				long tickTotalNs = t3 - t0;
				long renderNs = t1 - t0;
				long pushNs = t2 - t1;
				long flushNs = 0L;
				dbgPerf("perf: px=" + lastPerfW + "x" + lastPerfH
						+ " render=" + fmtMs(renderNs)
						+ " (collect=" + fmtMs(lastPerfCollectNs)
						+ " frame=" + fmtMs(lastPerfUpdateFrameNs)
						+ " layout=" + fmtMs(lastPerfLayoutNs)
						+ " ui=" + fmtMs(lastPerfRenderNs)
						+ " total=" + fmtMs(lastPerfTotalNs) + ")"
						+ " push=" + fmtMs(pushNs)
						+ " flush=" + fmtMs(flushNs)
						+ " tick=" + fmtMs(tickTotalNs)
						+ " viewers=" + spawnedTo.size());
			}
		} catch (Throwable t) {
			dbg("tick(): render/flush failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	private void ensureDisplay(MapEngineApi api) {
		boardDisplay.ensure(api, placement, mapBuffering, mapBundling);
	}

	private void spawnFor(Player p) {
		if (p == null)
			return;
		boardDisplay.ensureViewer(p);
	}

	private void despawnFor(Player p) {
		if (p == null)
			return;
		boardDisplay.removeViewer(p);
	}

	private void dbg(String msg) {
		if (!debug || plugin == null)
			return;
		try {
			plugin.getLogger().info("[LobbyBoard] " + msg);
		} catch (Throwable ignored) {
		}
	}

	private void dbgPerf(String msg) {
		if (!debug || plugin == null)
			return;
		long now = System.currentTimeMillis();
		// Rate limit noisy perf logs.
		if (lastPerfLogMillis != 0L && (now - lastPerfLogMillis) < 1000L)
			return;
		lastPerfLogMillis = now;
		try {
			plugin.getLogger().info("[LobbyBoard] " + msg);
		} catch (Throwable ignored) {
		}
	}

	private static String fmtMs(long ns) {
		double ms = (double) ns / 1_000_000.0;
		if (!Double.isFinite(ms))
			ms = 0.0;
		return String.format(java.util.Locale.ROOT, "%.2fms", ms);
	}

	private void dbgTick(String msg) {
		if (!debug || plugin == null)
			return;
		long now = System.currentTimeMillis();
		// Rate limit noisy tick logs.
		if (lastDebugTickLogMillis != 0L && (now - lastDebugTickLogMillis) < 1500L)
			return;
		lastDebugTickLogMillis = now;
		try {
			plugin.getLogger().info("[LobbyBoard] " + msg);
		} catch (Throwable ignored) {
		}
	}

	private enum TrackStatus {
		RUNNING,
		COUNTDOWN,
		REGISTERING,
		ENDING,
		READY,
		OFF
	}

	private record TrackInfo(
			String trackName,
			TrackStatus status,
			int registered,
			int maxRacers,
			long recordMillis,
			String recordHolderName,
			List<Location> centerline,
			int countdownSeconds,
			int endingSeconds) {
	}

	private static TrackStatus statusOf(RaceManager rm) {
		if (rm == null)
			return TrackStatus.OFF;
		try {
			if (isRaceFullyCompleted(rm) && rm.getPostFinishCleanupRemainingSeconds() > 0)
				return TrackStatus.ENDING;
		} catch (Throwable ignored) {
		}
		if (rm.isRunning())
			return TrackStatus.RUNNING;
		if (rm.isAnyCountdownActive())
			return TrackStatus.COUNTDOWN;
		if (rm.isRegistering())
			return TrackStatus.REGISTERING;
		return TrackStatus.READY;
	}

	private static Color accentForStatus(TrackStatus status) {
		if (status == null)
			status = TrackStatus.OFF;
		return switch (status) {
			case RUNNING -> new Color(0x56, 0xF2, 0x7A);
			case COUNTDOWN -> new Color(0xFF, 0xB8, 0x4D);
			case REGISTERING -> new Color(0xFF, 0xD7, 0x5E);
			case ENDING -> new Color(0xFF, 0xB8, 0x4D);
			case READY -> new Color(0x5E, 0xA8, 0xFF);
			case OFF -> new Color(0x8A, 0x8A, 0x8A);
		};
	}

	private static Color mix(Color a, Color b, double t) {
		if (a == null)
			return b;
		if (b == null)
			return a;
		double k = Math.max(0.0, Math.min(1.0, t));
		int r = (int) Math.round(a.getRed() * (1.0 - k) + b.getRed() * k);
		int g = (int) Math.round(a.getGreen() * (1.0 - k) + b.getGreen() * k);
		int bl = (int) Math.round(a.getBlue() * (1.0 - k) + b.getBlue() * k);
		return new Color(clamp(r, 0, 255), clamp(g, 0, 255), clamp(bl, 0, 255));
	}

	private List<TrackInfo> collectTrackInfos() {
		if (trackLibrary == null)
			return java.util.Collections.emptyList();

		List<String> tracks = new ArrayList<>();
		try {
			tracks.addAll(trackLibrary.list());
		} catch (Throwable ignored) {
		}
		tracks.sort(String.CASE_INSENSITIVE_ORDER);

		List<TrackInfo> out = new ArrayList<>();
		TrackRecordManager trm = null;
		try {
			trm = plugin != null ? plugin.getTrackRecordManager() : null;
		} catch (Throwable ignored) {
			trm = null;
		}

		for (String tn : tracks) {
			if (tn == null || tn.isBlank())
				continue;

			RaceManager rm = null;
			try {
				rm = raceService != null ? raceService.getOrCreate(tn) : null;
			} catch (Throwable ignored) {
				rm = null;
			}

			TrackStatus status = statusOf(rm);
			int regs = 0;
			int involved = 0;
			int max = 0;
			int countdownSec = 0;
			int endingSec = 0;
			try {
				if (rm != null) {
					regs = rm.getRegistered().size();
					involved = rm.getInvolved().size();
					max = rm.getTrackConfig() != null ? rm.getTrackConfig().getStarts().size() : 0;
					countdownSec = Math.max(0, rm.getCountdownRemainingSeconds());
					endingSec = Math.max(0, rm.getPostFinishCleanupRemainingSeconds());
				}
			} catch (Throwable ignored) {
			}

			// Reuse the 'registered' column for display counts:
			// - REGISTERING: registered count
			// - RUNNING/COUNTDOWN: racers involved in the race instance
			// - READY/OFF: 0
			int displayCount = switch (status) {
				case REGISTERING -> regs;
				case RUNNING, COUNTDOWN, ENDING -> involved;
				default -> 0;
			};

			long bestMs = 0L;
			String holder = "";
			try {
				TrackRecordManager.TrackRecord rec = (trm != null ? trm.get(tn) : null);
				if (rec != null) {
					bestMs = Math.max(0L, rec.bestTimeMillis);
					holder = rec.holderName == null ? "" : rec.holderName;
				}
			} catch (Throwable ignored) {
			}

			List<Location> cl = java.util.Collections.emptyList();
			try {
				if (rm != null && rm.getTrackConfig() != null) {
					List<Location> got = rm.getTrackConfig().getCenterline();
					if (got != null)
						cl = got;
				}
			} catch (Throwable ignored) {
			}

			out.add(new TrackInfo(tn, status, displayCount, max, bestMs, holder, cl, countdownSec, endingSec));
		}
		return out;
	}

	private static TrackInfo pickFocusedTrack(List<TrackInfo> infos) {
		if (infos == null || infos.isEmpty())
			return null;
		for (TrackInfo i : infos)
			if (i != null && i.status == TrackStatus.RUNNING)
				return i;
		for (TrackInfo i : infos)
			if (i != null && i.status == TrackStatus.COUNTDOWN)
				return i;
		for (TrackInfo i : infos)
			if (i != null && i.status == TrackStatus.ENDING)
				return i;
		for (TrackInfo i : infos)
			if (i != null && i.status == TrackStatus.REGISTERING)
				return i;
		for (TrackInfo i : infos)
			if (i != null)
				return i;
		return null;
	}

	private static String statusLabel(TrackStatus st, int regs, int max, int countdownSeconds) {
		if (st == null)
			st = TrackStatus.OFF;
		return switch (st) {
			case RUNNING -> "Đang chạy";
			// Countdown seconds are shown only as the large centered overlay on the focused
			// minimap.
			case COUNTDOWN -> "Đếm ngược";
			case REGISTERING -> "Đang đăng ký " + regs + "/" + max;
			case ENDING -> "Đang kết thúc";
			case READY -> "Sẵn sàng";
			case OFF -> "Tắt";
		};
	}

	private static String recordLabel(long ms, String holderName) {
		if (ms <= 0L)
			return ICON_CLOCK + " Kỷ lục: -";
		String t = Time.formatStopwatchMillis(ms);
		String hn = (holderName == null ? "" : holderName.trim());
		if (hn.isEmpty())
			return ICON_CLOCK + " Kỷ lục: " + t;
		return ICON_CLOCK + " Kỷ lục: " + t + " - " + hn;
	}

	private int getTrackPageSeconds() {
		try {
			if (plugin != null) {
				int v = plugin.getConfig().getInt("mapengine.lobby-board.page-seconds", 6);
				return Math.max(2, v);
			}
		} catch (Throwable ignored) {
		}
		return 6;
	}

	private static void drawMiniMap(Graphics2D g, List<Location> centerline, int x, int y, int w, int h, Color accent,
			Color borderC, Color textDim, Font smallFont, float strokePx) {
		if (g == null)
			return;
		if (w <= 2 || h <= 2)
			return;

		float stroke = Math.max(1.0f, strokePx);
		int shadowOff = Math.max(1, (int) Math.round(stroke));
		int margin = Math.max(4, (int) Math.round(stroke * 2.0));

		// Background + border
		g.setColor(new Color(0x10, 0x11, 0x13));
		g.fillRect(x, y, w, h);
		g.setColor(borderC);
		g.setStroke(new BasicStroke(stroke));
		g.drawRect(x, y, w - 1, h - 1);

		if (centerline == null || centerline.size() < 2) {
			g.setFont(smallFont);
			g.setColor(textDim);
			drawTrimmed(g, "(Không có bản đồ)", x + 4, y + g.getFontMetrics(smallFont).getAscent() + 2, w - 8);
			return;
		}

		// Compute bounds in XZ
		double minX = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (Location p : centerline) {
			if (p == null)
				continue;
			double px = p.getX();
			double pz = p.getZ();
			if (px < minX)
				minX = px;
			if (px > maxX)
				maxX = px;
			if (pz < minZ)
				minZ = pz;
			if (pz > maxZ)
				maxZ = pz;
		}
		if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ) || !Double.isFinite(maxZ)) {
			return;
		}

		double dx = Math.max(1.0e-6, maxX - minX);
		double dz = Math.max(1.0e-6, maxZ - minZ);

		int innerW = Math.max(1, w - margin * 2);
		int innerH = Math.max(1, h - margin * 2);
		double s = Math.min(innerW / dx, innerH / dz);

		double usedW = dx * s;
		double usedH = dz * s;
		double ox = x + margin + (innerW - usedW) * 0.5;
		double oz = y + margin + (innerH - usedH) * 0.5;

		// Convert points to integer pixel coords (dedupe consecutive duplicates)
		java.util.ArrayList<java.awt.Point> pts = new java.util.ArrayList<>(centerline.size());
		int lastPx = Integer.MIN_VALUE;
		int lastPy = Integer.MIN_VALUE;
		for (Location p : centerline) {
			if (p == null)
				continue;
			int px = (int) Math.round(ox + (p.getX() - minX) * s);
			int py = (int) Math.round(oz + (p.getZ() - minZ) * s);
			if (px == lastPx && py == lastPy)
				continue;
			pts.add(new java.awt.Point(px, py));
			lastPx = px;
			lastPy = py;
		}
		if (pts.size() < 2)
			return;

		// Draw: shadow then main line (integer aligned)
		g.setStroke(new BasicStroke(stroke));
		g.setColor(new Color(0, 0, 0, 140));
		for (int i = 1; i < pts.size(); i++) {
			java.awt.Point a = pts.get(i - 1);
			java.awt.Point b = pts.get(i);
			g.drawLine(a.x + shadowOff, a.y + shadowOff, b.x + shadowOff, b.y + shadowOff);
		}

		g.setColor(accent);
		for (int i = 1; i < pts.size(); i++) {
			java.awt.Point a = pts.get(i - 1);
			java.awt.Point b = pts.get(i);
			g.drawLine(a.x, a.y, b.x, b.y);
		}

		// Start/end markers (small squares for pixel crispness)
		java.awt.Point start = pts.get(0);
		java.awt.Point end = pts.get(pts.size() - 1);
		int r = Math.max(2, (int) Math.round(stroke * 1.25));
		g.setColor(new Color(0xEE, 0xEE, 0xEE));
		g.fillRect(start.x - r, start.y - r, r * 2 + 1, r * 2 + 1);
		g.setColor(accent);
		g.fillRect(end.x - r, end.y - r, r * 2 + 1, r * 2 + 1);
	}

	private record MiniDot(double x, double z, Color color) {
	}

	private List<MiniDot> collectRacerDots(RaceManager rm) {
		if (rm == null || !rm.isRunning())
			return java.util.Collections.emptyList();

		List<MiniDot> dots = new java.util.ArrayList<>();
		List<UUID> order;
		try {
			order = rm.getLiveOrder();
		} catch (Throwable t) {
			order = java.util.Collections.emptyList();
		}

		for (UUID id : order) {
			if (id == null)
				continue;

			try {
				var st = rm.getParticipantState(id);
				if (st != null && st.finished)
					continue;
			} catch (Throwable ignored) {
			}

			Player p;
			try {
				p = Bukkit.getPlayer(id);
			} catch (Throwable t) {
				p = null;
			}
			if (p == null || !p.isOnline())
				continue;

			Location loc = null;
			try {
				var v = p.getVehicle();
				if (v != null)
					loc = v.getLocation();
			} catch (Throwable ignored) {
				loc = null;
			}
			if (loc == null) {
				try {
					loc = p.getLocation();
				} catch (Throwable ignored) {
					loc = null;
				}
			}
			if (loc == null)
				continue;

			Color c = new Color(0xEE, 0xEE, 0xEE);
			try {
				if (profileManager != null) {
					var prof = profileManager.get(id);
					if (prof != null && prof.color != null) {
						c = ColorTranslator.awtColor(prof.color);
					}
				}
			} catch (Throwable ignored) {
			}

			dots.add(new MiniDot(loc.getX(), loc.getZ(), c));
		}

		return dots;
	}

	private static void drawMiniMapWithDots(Graphics2D g, List<Location> centerline, List<MiniDot> dots,
			int x, int y, int w, int h,
			Color accent, Color borderC, Color textDim, Font smallFont, float strokePx) {
		if (g == null)
			return;
		if (w <= 2 || h <= 2)
			return;

		float stroke = Math.max(1.0f, strokePx);
		int shadowOff = Math.max(1, (int) Math.round(stroke));
		int margin = Math.max(4, (int) Math.round(stroke * 2.0));

		// Background + border
		g.setColor(new Color(0x10, 0x11, 0x13));
		g.fillRect(x, y, w, h);
		g.setColor(borderC);
		g.setStroke(new BasicStroke(stroke));
		g.drawRect(x, y, w - 1, h - 1);

		if (centerline == null || centerline.size() < 2) {
			g.setFont(smallFont);
			g.setColor(textDim);
			drawTrimmed(g, "(Không có bản đồ)", x + 4, y + g.getFontMetrics(smallFont).getAscent() + 2, w - 8);
			return;
		}

		// Compute bounds in XZ
		double minX = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (Location p : centerline) {
			if (p == null)
				continue;
			double px = p.getX();
			double pz = p.getZ();
			if (px < minX)
				minX = px;
			if (px > maxX)
				maxX = px;
			if (pz < minZ)
				minZ = pz;
			if (pz > maxZ)
				maxZ = pz;
		}
		if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ) || !Double.isFinite(maxZ)) {
			return;
		}

		double dx = Math.max(1.0e-6, maxX - minX);
		double dz = Math.max(1.0e-6, maxZ - minZ);

		int innerW = Math.max(1, w - margin * 2);
		int innerH = Math.max(1, h - margin * 2);

		// Optional rotation (90deg) to better match the available panel aspect.
		// If the track is taller but the panel is wider (or vice versa), rotate the map
		// so it fills more.
		boolean mapWide = dx >= dz;
		boolean panelWide = innerW >= innerH;
		final boolean rotate = (mapWide != panelWide);

		double srcW = rotate ? dz : dx;
		double srcH = rotate ? dx : dz;

		// Maintain aspect ratio
		double sx = innerW / srcW;
		double sz = innerH / srcH;
		double s = Math.min(sx, sz);

		int drawW = Math.max(1, (int) Math.round(srcW * s));
		int drawH = Math.max(1, (int) Math.round(srcH * s));
		int ox = x + margin + (innerW - drawW) / 2;
		int oy = y + margin + (innerH - drawH) / 2;

		// Project centerline points into pixel grid
		java.util.List<java.awt.Point> pts = new java.util.ArrayList<>(centerline.size());
		int lastPx = Integer.MIN_VALUE;
		int lastPy = Integer.MIN_VALUE;
		for (Location p : centerline) {
			if (p == null)
				continue;
			double px = p.getX();
			double pz = p.getZ();

			double u;
			double v;
			if (!rotate) {
				u = (px - minX);
				v = (pz - minZ);
			} else {
				// 90° clockwise rotation around the map bounding box.
				u = (pz - minZ);
				v = (maxX - px);
			}

			int ix = (int) Math.round(u * s);
			int iz = (int) Math.round(v * s);
			int rx = ox + clamp(ix, 0, drawW);
			int ry = oy + clamp(iz, 0, drawH);
			if (rx == lastPx && ry == lastPy)
				continue;
			pts.add(new java.awt.Point(rx, ry));
			lastPx = rx;
			lastPy = ry;
		}
		if (pts.size() >= 2) {
			// Draw: shadow then main line (integer aligned)
			g.setStroke(new BasicStroke(stroke));
			g.setColor(new Color(0, 0, 0, 140));
			for (int i = 1; i < pts.size(); i++) {
				java.awt.Point a = pts.get(i - 1);
				java.awt.Point b = pts.get(i);
				g.drawLine(a.x + shadowOff, a.y + shadowOff, b.x + shadowOff, b.y + shadowOff);
			}

			g.setColor(accent);
			for (int i = 1; i < pts.size(); i++) {
				java.awt.Point a = pts.get(i - 1);
				java.awt.Point b = pts.get(i);
				g.drawLine(a.x, a.y, b.x, b.y);
			}

			// Start/end markers (small squares for pixel crispness)
			java.awt.Point start = pts.get(0);
			java.awt.Point end = pts.get(pts.size() - 1);
			int r = Math.max(2, (int) Math.round(stroke * 1.25));
			g.setColor(new Color(0xEE, 0xEE, 0xEE));
			g.fillRect(start.x - r, start.y - r, r * 2 + 1, r * 2 + 1);
			g.setColor(accent);
			g.fillRect(end.x - r, end.y - r, r * 2 + 1, r * 2 + 1);
		}

		// Overlay racer dots (project actual XZ into the same bounds).
		if (dots != null && !dots.isEmpty()) {
			int r = Math.max(2, (int) Math.round(stroke * 1.15));
			int minPx = x + 1;
			int minPy = y + 1;
			int maxPx = x + w - 2;
			int maxPy = y + h - 2;

			for (MiniDot d : dots) {
				if (d == null)
					continue;
				double px = d.x;
				double pz = d.z;
				if (!Double.isFinite(px) || !Double.isFinite(pz))
					continue;

				double u;
				double v;
				if (!rotate) {
					u = (px - minX);
					v = (pz - minZ);
				} else {
					u = (pz - minZ);
					v = (maxX - px);
				}

				int ix = (int) Math.round(u * s);
				int iz = (int) Math.round(v * s);
				int rx = ox + clamp(ix, 0, drawW);
				int ry = oy + clamp(iz, 0, drawH);
				rx = clamp(rx, minPx, maxPx);
				ry = clamp(ry, minPy, maxPy);

				// Shadow + dot for visibility
				g.setColor(new Color(0, 0, 0, 170));
				g.fillRect(rx - r + shadowOff, ry - r + shadowOff, r * 2 + 1, r * 2 + 1);
				g.setColor(d.color != null ? d.color : new Color(0xEE, 0xEE, 0xEE));
				g.fillRect(rx - r, ry - r, r * 2 + 1, r * 2 + 1);
			}
		}
	}

	private static boolean isRankingLine(String s) {
		if (s == null)
			return false;
		int close = s.indexOf(')');
		if (close <= 0)
			return false;
		String head = s.substring(0, close).trim();
		if (head.isEmpty())
			return false;
		for (int i = 0; i < head.length(); i++) {
			char c = head.charAt(i);
			if (c < '0' || c > '9')
				return false;
		}
		return true;
	}

	private BufferedImage renderImage(int widthPx, int heightPx) {
		final boolean doPerf = debug;
		final long tStart = doPerf ? System.nanoTime() : 0L;
		long tAfterCollect = 0L;
		long tAfterUpdateFrame = 0L;
		long tAfterLayout = 0L;
		long tAfterRender = 0L;
		long tEnd = 0L;

		int w = Math.max(128, widthPx);
		int h = Math.max(128, heightPx);
		if (doPerf) {
			lastPerfW = w;
			lastPerfH = h;
		}

		// Ensure we tried loading the board font before we select default fonts for the
		// context.
		try {
			tryLoadBoardFont();
		} catch (Throwable ignored) {
		}

		// Drive sizes from pixel height (same as legacy) to keep spacing consistent.
		final double uiScale = 1.25;
		int bodySize = clamp((int) Math.round((h / 34.0) * uiScale), 14, 72);
		int headerSize = clamp((int) Math.round(bodySize * 1.10), 16, 84);
		int titleSize = clamp((int) Math.round(bodySize * 1.70), 20, 110);
		int footerSize = clamp((int) Math.round(bodySize * 0.85), 12, 60);

		int pad = Math.max(18, (int) Math.round(bodySize * 0.95));
		int border = Math.max(2, (int) Math.round(bodySize * 0.12));
		int inset = Math.max(6, border * 3);

		// Data (used for theming + minimap)
		List<TrackInfo> tracks = collectTrackInfos();
		if (doPerf)
			tAfterCollect = System.nanoTime();
		TrackInfo focused = pickFocusedTrack(tracks);
		TrackStatus focusedStatus = focused != null ? focused.status : TrackStatus.OFF;
		Color statusAccent = accentForStatus(focusedStatus);

		// Theme (racing broadcast style): dark panels + status-based accent.
		final Color bg0 = mix(new Color(0x0E, 0x10, 0x12), statusAccent, 0.10);
		final Color panel = mix(new Color(0x14, 0x16, 0x1A), statusAccent, 0.07);
		final Color panel2 = mix(new Color(0x12, 0x14, 0x17), statusAccent, 0.08);
		final Color borderC = new Color(0x3A, 0x3A, 0x3A);
		final Color accent = statusAccent;
		final Color text = new Color(0xEE, 0xEE, 0xEE);
		final Color textDim = new Color(0xA6, 0xA6, 0xA6);

		Font titleFont = boardBold(titleSize);
		Font headerFont = boardBold(headerSize);
		Font bodyFont = boardPlain(bodySize);
		Font smallFont = boardPlain(footerSize);
		Font fallbackFont = monoMatch(bodyFont);

		// Render with crisp-ish settings (legacy used AA OFF). Keep scoped to the lobby
		// board.
		BufferedImage img = acquireRenderBuffer(w, h);
		Graphics2D g = img.createGraphics();
		try {
			// Clear previous frame (required when reusing a backbuffer).
			try {
				g.setComposite(java.awt.AlphaComposite.Src);
				g.setColor(new Color(0, 0, 0, 0));
				g.fillRect(0, 0, w, h);
				g.setComposite(java.awt.AlphaComposite.SrcOver);
			} catch (Throwable ignored) {
			}

			UiRenderContext ctx = new UiRenderContext(g, bodyFont, fallbackFont, text);
			ctx.applyDefaultHints();

			// Override hints to match the old lobby board look.
			try {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			} catch (Throwable ignored) {
			}

			// Compute font metrics using the REAL render Graphics2D.
			// This avoids the per-render scratch BufferedImage(1x1) allocation.
			java.awt.FontMetrics fmTitle;
			java.awt.FontMetrics fmHeader;
			java.awt.FontMetrics fmBody;
			java.awt.FontMetrics fmSmall;
			try {
				fmTitle = g.getFontMetrics(titleFont != null ? titleFont : g.getFont());
				fmHeader = g.getFontMetrics(headerFont != null ? headerFont : g.getFont());
				fmBody = g.getFontMetrics(bodyFont != null ? bodyFont : g.getFont());
				fmSmall = g.getFontMetrics(smallFont != null ? smallFont : g.getFont());
			} catch (Throwable t) {
				fmTitle = g.getFontMetrics();
				fmHeader = g.getFontMetrics();
				fmBody = g.getFontMetrics();
				fmSmall = g.getFontMetrics();
			}

			// Cached UI tree: rebuild only when geometry/fonts change.
			UiCache cache = uiCache;
			boolean needsRebuild = cache == null
					|| cache.w != w
					|| cache.h != h
					|| cache.bodySize != bodySize
					|| cache.pad != pad
					|| cache.border != border
					|| cache.inset != inset
					|| cache.baseFontRef != boardFontBase;

			if (needsRebuild) {
				cache = new UiCache(
						w,
						h,
						uiScale,
						bodySize,
						pad,
						border,
						inset,
						titleFont,
						headerFont,
						bodyFont,
						smallFont,
						fallbackFont,
						fmTitle,
						fmHeader,
						fmBody,
						fmSmall,
						boardFontBase);
				uiCache = cache;
			}

			if (cache == null) {
				cache = new UiCache(
						w,
						h,
						uiScale,
						bodySize,
						pad,
						border,
						inset,
						titleFont,
						headerFont,
						bodyFont,
						smallFont,
						fallbackFont,
						fmTitle,
						fmHeader,
						fmBody,
						fmSmall,
						boardFontBase);
				uiCache = cache;
			}

			cache.updateFrame(
					tracks,
					focused,
					bg0,
					panel,
					panel2,
					borderC,
					accent,
					text,
					textDim,
					fmBody,
					fmSmall);
			if (doPerf)
				tAfterUpdateFrame = System.nanoTime();

			if (bodyFont != null)
				g.setFont(bodyFont);
			if (cache.root != null) {
				cache.root.layout(ctx, 0, 0, w, h);
				if (doPerf)
					tAfterLayout = System.nanoTime();
				cache.root.render(ctx);
				if (doPerf)
					tAfterRender = System.nanoTime();
			}
		} finally {
			try {
				g.dispose();
			} catch (Throwable ignored) {
			}
		}
		if (doPerf) {
			tEnd = System.nanoTime();
			lastPerfCollectNs = Math.max(0L, tAfterCollect - tStart);
			lastPerfUpdateFrameNs = Math.max(0L, tAfterUpdateFrame - tAfterCollect);
			lastPerfLayoutNs = Math.max(0L, tAfterLayout - tAfterUpdateFrame);
			lastPerfRenderNs = Math.max(0L, tAfterRender - tAfterLayout);
			lastPerfTotalNs = Math.max(0L, tEnd - tStart);
		}
		return img;
	}

	/**
	 * Cached UI tree + dynamic element bindings.
	 * The tree is built once and then only updated with changing values per tick.
	 */
	@SuppressWarnings("unused")
	private final class UiCache {
		final int w;
		final int h;
		final double uiScale;
		final int bodySize;
		final int pad;
		final int border;
		final int inset;

		final Font titleFont;
		final Font headerFont;
		final Font bodyFont;
		final Font smallFont;
		final Font fallbackFont;

		final java.awt.FontMetrics fmTitle;
		final java.awt.FontMetrics fmHeader;
		final java.awt.FontMetrics fmBody;
		final java.awt.FontMetrics fmSmall;

		final Font baseFontRef;

		final ColumnContainer root;

		final ColumnContainer titleBar;
		final GraphicsElement titleStripe;
		final TextElement titleText;

		final ColumnContainer headerBox;
		final RowContainer headerRow;
		final TextElement leftHeader;
		final TextElement rightHeader;
		final GraphicsElement headerStripe;

		final ColumnContainer mainPanel;
		final RowContainer mainRow;

		final ColumnContainer leftCol;
		final TextElement leftEmptyA;
		final TextElement leftEmptyB;
		final TrackSlot[] trackSlots;
		final TextElement leftHint1;
		final TextElement leftHint2;

		final ColumnContainer rightCol;
		final GraphicsElement rightMap;
		final RightLineSlot[] rightLineSlots;

		final GraphicsElement footer;

		final java.util.concurrent.atomic.AtomicReference<Color> accentRef = new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.atomic.AtomicReference<Color> textRef = new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.atomic.AtomicReference<Color> textDimRef = new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.atomic.AtomicReference<Color> borderRef = new java.util.concurrent.atomic.AtomicReference<>();

		UiCache(
				int w,
				int h,
				double uiScale,
				int bodySize,
				int pad,
				int border,
				int inset,
				Font titleFont,
				Font headerFont,
				Font bodyFont,
				Font smallFont,
				Font fallbackFont,
				java.awt.FontMetrics fmTitle,
				java.awt.FontMetrics fmHeader,
				java.awt.FontMetrics fmBody,
				java.awt.FontMetrics fmSmall,
				Font baseFontRef) {
			this.w = w;
			this.h = h;
			this.uiScale = uiScale;
			this.bodySize = bodySize;
			this.pad = pad;
			this.border = border;
			this.inset = inset;
			this.titleFont = titleFont;
			this.headerFont = headerFont;
			this.bodyFont = bodyFont;
			this.smallFont = smallFont;
			this.fallbackFont = fallbackFont;
			this.fmTitle = fmTitle;
			this.fmHeader = fmHeader;
			this.fmBody = fmBody;
			this.fmSmall = fmSmall;
			this.baseFontRef = baseFontRef;

			// Precompute shared sizing.
			int rowH = fmBody.getHeight() + Math.max(2, (int) Math.round(bodySize * 0.12));
			int trackInnerPad = trackRowInnerPad(bodyFont);

			int titleBarH = Math.max(fmTitle.getHeight() + Math.max(8, border * 3), (int) Math.round(bodySize * 2.2));
			int headerRowH = Math.max(rowH, fmHeader.getHeight() + Math.max(6, border * 2));
			int headerStripeH = Math.min(headerRowH, Math.max(3, border + 1));
			int titleStripeH = Math.max(3, border + 1);

			int footerPadV = Math.max(6, (int) Math.round(bodySize * 0.35));
			int footerBoxH = fmSmall.getHeight() + footerPadV;

			int gapAfterTitle = Math.max(10, (int) Math.round(bodySize * 0.9));
			int gapAfterHeader = Math.max(10, (int) Math.round(bodySize * 0.6));
			int gapBeforeFooter = Math.max(8, (int) Math.round(bodySize * 0.50));

			int innerH = Math.max(0, h - (inset * 2));
			int used = titleBarH + gapAfterTitle + headerRowH + gapAfterHeader + gapBeforeFooter + footerBoxH;
			int panelH = Math.max(0, innerH - used);

			int colW = (w / 2) - (pad * 2);

			// Root column.
			ColumnContainer root = new ColumnContainer()
					.alignItems(UiAlign.STRETCH)
					.justifyContent(UiJustify.START);
			root.style().padding(UiInsets.all(inset));
			this.root = root;

			// Title bar.
			ColumnContainer titleBar = new ColumnContainer().alignItems(UiAlign.STRETCH);
			titleBar.style().heightPx(titleBarH);
			this.titleBar = titleBar;

			GraphicsElement titleStripe = new GraphicsElement((ctx, rect) -> {
				if (ctx == null || ctx.g == null)
					return;
				Color a = accentRef.get();
				if (a == null)
					return;
				ctx.g.setColor(a);
				ctx.g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
			});
			titleStripe.style().heightPx(titleStripeH);
			this.titleStripe = titleStripe;
			titleBar.add(titleStripe);

			TextElement titleText = new TextElement("BẢNG THÔNG TIN SẢNH")
					.font(titleFont)
					.align(TextElement.Align.CENTER)
					.ellipsis(false);
			titleText.style().padding(UiInsets.symmetric(0, pad));
			this.titleText = titleText;
			titleBar.add(titleText);
			root.add(titleBar);
			root.add(spacer(gapAfterTitle));

			// Header.
			ColumnContainer headerBox = new ColumnContainer().alignItems(UiAlign.STRETCH);
			headerBox.style().heightPx(headerRowH);
			this.headerBox = headerBox;

			RowContainer headerRow = new RowContainer()
					.alignItems(UiAlign.CENTER)
					.justifyContent(UiJustify.START)
					.gap(2 * pad);
			headerRow.style().padding(UiInsets.symmetric(0, Math.max(0, pad - inset)));
			this.headerRow = headerRow;

			TextElement leftHeader = new TextElement("ĐƯỜNG ĐUA")
					.font(headerFont)
					.align(TextElement.Align.LEFT)
					.ellipsis(false);
			leftHeader.style().widthPx(colW);
			this.leftHeader = leftHeader;

			TextElement rightHeader = new TextElement("BẢNG XẾP HẠNG")
					.font(headerFont)
					.align(TextElement.Align.LEFT)
					.ellipsis(false);
			rightHeader.style().widthPx(colW);
			this.rightHeader = rightHeader;

			headerRow.add(leftHeader);
			headerRow.add(rightHeader);
			headerBox.add(headerRow);

			GraphicsElement headerStripe = new GraphicsElement((ctx, rect) -> {
				if (ctx == null || ctx.g == null)
					return;
				Color a = accentRef.get();
				if (a == null)
					return;
				ctx.g.setColor(a);
				ctx.g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
			});
			headerStripe.style().heightPx(headerStripeH);
			this.headerStripe = headerStripe;
			headerBox.add(headerStripe);
			root.add(headerBox);
			root.add(spacer(gapAfterHeader));

			// Main panel.
			ColumnContainer mainPanel = new ColumnContainer().alignItems(UiAlign.STRETCH);
			mainPanel.style().heightPx(panelH).padding(UiInsets.symmetric(6, 0));
			this.mainPanel = mainPanel;

			RowContainer mainRow = new RowContainer()
					.alignItems(UiAlign.STRETCH)
					.justifyContent(UiJustify.START)
					.gap(2 * pad);
			mainRow.style().padding(UiInsets.symmetric(0, Math.max(0, pad - inset)));
			// Fill the remaining mainPanel height (minimal flex-grow model).
			mainRow.style().flexGrow(1);
			this.mainRow = mainRow;

			// Left column: cached track slots.
			ColumnContainer leftCol = new ColumnContainer().alignItems(UiAlign.STRETCH).justifyContent(UiJustify.START);
			int blockGap = Math.max(6, rowH / 3);
			leftCol.gap(blockGap);
			leftCol.style().widthPx(colW);
			this.leftCol = leftCol;

			TextElement emptyA = new TextElement("(Chưa có đường đua)")
					.font(bodyFont)
					.align(TextElement.Align.LEFT)
					.ellipsis(true);
			TextElement emptyB = new TextElement("Dùng /boatracing setup để tạo.")
					.font(smallFont)
					.align(TextElement.Align.LEFT)
					.ellipsis(true);
			emptyA.style().display(false);
			emptyB.style().display(false);
			this.leftEmptyA = emptyA;
			this.leftEmptyB = emptyB;
			leftCol.add(emptyA);
			leftCol.add(emptyB);

			// Compute a safe maximum number of track slots for this panel size.
			int blockPadV = Math.max(4, (int) Math.round(bodyFont.getSize() * 0.22));
			int minSmallLines = 1;
			int minBlockH = rowH + (fmSmall.getHeight() * minSmallLines) + blockPadV;
			int hintLines = 2;
			int hintH = hintLines * fmSmall.getHeight() + 10;
			int contentH = Math.max(0, panelH - 12);
			int availableH = Math.max(0, contentH - hintH);
			int maxSlots = Math.max(1, availableH / Math.max(1, minBlockH + blockGap));
			maxSlots = clamp(maxSlots + 1, 1, 32);

			TrackSlot[] slots = new TrackSlot[maxSlots];
			for (int i = 0; i < maxSlots; i++) {
				slots[i] = new TrackSlot(trackInnerPad);
				slots[i].block.style().display(false);
				leftCol.add(slots[i].block);
			}
			this.trackSlots = slots;

			TextElement hint1 = new TextElement("Dùng: /boatracing race join <tên>")
					.font(smallFont)
					.align(TextElement.Align.LEFT)
					.ellipsis(true);
			TextElement hint2 = new TextElement("")
					.font(smallFont)
					.align(TextElement.Align.LEFT)
					.ellipsis(true);
			hint1.style().display(false);
			hint2.style().display(false);
			this.leftHint1 = hint1;
			this.leftHint2 = hint2;
			leftCol.add(hint1);
			leftCol.add(hint2);

			// Right column: cached map + cached line slots.
			ColumnContainer rightCol = new ColumnContainer().alignItems(UiAlign.STRETCH)
					.justifyContent(UiJustify.START);
			rightCol.style().widthPx(colW);
			rightCol.style().padding(UiInsets.all(Math.max(6, border))).border(null, Math.max(1, border - 1));
			rightCol.gap(Math.max(4, fmSmall.getHeight() / 3));
			this.rightCol = rightCol;

			int mapH = clamp((int) Math.round(colW * 0.72), 72, Math.max(72, panelH / 2));

			// Backing state for right-side map painter. Must be initialized BEFORE the
			// lambda below.
			// Otherwise Java treats it as reading an uninitialized final from inside a
			// captured lambda.
			RightMapState rms = new RightMapState();
			this.rightMapState = rms;

			GraphicsElement rightMap = new GraphicsElement((ctx, rect) -> {
				if (ctx == null || ctx.g == null)
					return;
				rms.paint(ctx, rect);
			});
			rightMap.style().heightPx(mapH).display(false);
			this.rightMap = rightMap;
			rightCol.add(rightMap);

			// Preallocate right-side lines (limit to avoid overflow).
			int maxRightLines = 24;
			RightLineSlot[] rslots = new RightLineSlot[maxRightLines];
			for (int i = 0; i < maxRightLines; i++) {
				rslots[i] = new RightLineSlot(rowH, trackInnerPad);
				rslots[i].row.style().display(false);
				rightCol.add(rslots[i].row);
			}
			this.rightLineSlots = rslots;

			// Main row wiring.
			mainRow.add(leftCol);
			mainRow.add(rightCol);
			mainPanel.add(mainRow);
			root.add(mainPanel);
			root.add(spacer(gapBeforeFooter));

			// Footer (kept painter-based like legacy).
			GraphicsElement footer = new GraphicsElement((ctx, rect) -> {
				if (ctx == null || ctx.g == null)
					return;
				Graphics2D gg = ctx.g;

				gg.setFont(smallFont);
				java.awt.FontMetrics fm = gg.getFontMetrics();
				Color dim = textDimRef.get();
				gg.setColor(dim != null ? dim : new Color(0xA6, 0xA6, 0xA6));

				String left = ICON_INFO + " Chỉ hiển thị khi bạn ở sảnh và gần bảng.";
				String right;
				try {
					java.time.LocalTime now = java.time.LocalTime.now();
					right = ICON_CLOCK + " " + now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
				} catch (Throwable t) {
					right = ICON_CLOCK;
				}

				int xLeft = rect.x() + pad;
				int y = rect.y() + (rect.h() + fm.getAscent() - fm.getDescent()) / 2;
				drawTrimmed(gg, left, xLeft, y, Math.max(0, rect.w() - (pad * 2)));

				Font rightFallback = monoMatch(smallFont);
				int rightW = stringWidthWithFallback(gg, right, smallFont, rightFallback);
				int xRight = rect.x() + rect.w() - pad - rightW;
				if (xRight > xLeft + 20) {
					drawStringWithFallback(gg, right, xRight, y, smallFont, rightFallback);
				}
			});
			footer.style().heightPx(footerBoxH);
			this.footer = footer;
			root.add(footer);
		}

		// Backing state is held as a field only so the map painter can access it.
		final RightMapState rightMapState;

		void updateFrame(
				List<TrackInfo> tracks,
				TrackInfo focused,
				Color bg0,
				Color panel,
				Color panel2,
				Color borderC,
				Color accent,
				Color text,
				Color textDim,
				java.awt.FontMetrics fmBody,
				java.awt.FontMetrics fmSmall) {
			accentRef.set(accent);
			textRef.set(text);
			textDimRef.set(textDim);
			borderRef.set(borderC);

			// Theme updates.
			root.style().background(bg0);
			titleBar.style().background(panel);
			headerBox.style().background(panel);
			mainPanel.style().background(panel);
			footer.style().background(panel);

			leftHeader.color(accent);
			rightHeader.color(accent);
			titleText.color(text);

			// Right panel styling depends on panel2.
			rightCol.style().background(panel2).border(borderC, Math.max(1, border - 1));

			// Left: tracks.
			updateLeftTracks(tracks, focused, fmBody, fmSmall, text, textDim, borderC);

			// Right: map + lines.
			updateRightPanel(tracks, focused, fmBody, fmSmall, text, textDim, borderC);
		}

		private void updateLeftTracks(
				List<TrackInfo> tracks,
				TrackInfo focused,
				java.awt.FontMetrics fmBody,
				java.awt.FontMetrics fmSmall,
				Color text,
				Color textDim,
				Color borderC) {
			if (tracks == null)
				tracks = java.util.Collections.emptyList();
			boolean hasTracks = !tracks.isEmpty();

			leftEmptyA.style().display(!hasTracks);
			leftEmptyB.style().display(!hasTracks);
			leftEmptyA.color(text);
			leftEmptyB.color(textDim);

			if (!hasTracks) {
				for (TrackSlot s : trackSlots)
					s.hide();
				leftHint1.style().display(false);
				leftHint2.style().display(false);
				return;
			}

			// Airport-board paging (same math as legacy buildLeftTrackColumn)
			int rowH = fmBody.getHeight() + Math.max(2, (int) Math.round(bodySize * 0.12));
			int blockGap = Math.max(6, rowH / 3);
			int blockPadV = Math.max(4, (int) Math.round(bodyFont.getSize() * 0.22));
			int estSmallLines = 2;
			int blockHEst = rowH + (fmSmall.getHeight() * estSmallLines) + blockPadV;
			int hintLines = 2;
			int hintH = hintLines * fmSmall.getHeight() + 10;
			int contentH = Math.max(0, mainPanel.style().heightPx() == null ? 0 : mainPanel.style().heightPx()) - 12;
			if (contentH < 0)
				contentH = 0;
			int availableH = Math.max(0, contentH - hintH);
			int perPage = Math.max(1, availableH / Math.max(1, blockHEst + blockGap));
			int total = tracks.size();
			int totalPages = Math.max(1, (int) Math.ceil(total / (double) perPage));
			long now = System.currentTimeMillis();
			long pageMs = getTrackPageSeconds() * 1000L;
			int pageIndex = (totalPages <= 1) ? 0 : (int) ((now / Math.max(1L, pageMs)) % totalPages);
			int startIndex = pageIndex * perPage;
			int endIndex = Math.min(total, startIndex + perPage);

			int usedH = 0;
			int slotIdx = 0;
			for (int i = startIndex; i < endIndex && slotIdx < trackSlots.length; i++) {
				TrackInfo ti = tracks.get(i);
				if (ti == null)
					continue;

				int smallLines = (ti.status == TrackStatus.RUNNING || ti.status == TrackStatus.COUNTDOWN) ? 1 : 2;
				int thisBlockH = rowH + (fmSmall.getHeight() * smallLines) + blockPadV;
				if (usedH + thisBlockH > contentH)
					break;

				trackSlots[slotIdx].show(ti, thisBlockH, text, textDim, borderC);
				slotIdx++;
				usedH += thisBlockH + blockGap;
			}
			for (int i = slotIdx; i < trackSlots.length; i++)
				trackSlots[i].hide();

			// Hints (only if enough remaining height) - match legacy wording + paging math
			if (contentH - usedH >= hintH) {
				leftHint1.text("Dùng: /boatracing race join <tên>").color(textDim);

				// Legacy recomputes paging using a fixed 2-small-line block estimate.
				int blockH2 = rowH + (fmSmall.getHeight() * 2) + blockPadV;
				int hintH2 = 2 * fmSmall.getHeight() + 10;
				int availableH2 = Math.max(0, contentH - hintH2);
				int perPage2 = Math.max(1, availableH2 / Math.max(1, blockH2 + blockGap));
				int totalPages2 = Math.max(1, (int) Math.ceil(total / (double) perPage2));
				long now2 = System.currentTimeMillis();
				long pageMs2 = getTrackPageSeconds() * 1000L;
				int pageIndex2 = (totalPages2 <= 1) ? 0 : (int) ((now2 / Math.max(1L, pageMs2)) % totalPages2);
				String pageLabel = "Trang " + (pageIndex2 + 1) + "/" + totalPages2;

				leftHint2.text(pageLabel).color(textDim);
				leftHint1.style().display(true);
				leftHint2.style().display(true);
			} else {
				leftHint1.style().display(false);
				leftHint2.style().display(false);
			}
		}

		private void updateRightPanel(
				List<TrackInfo> tracks,
				TrackInfo focused,
				java.awt.FontMetrics fmBody,
				java.awt.FontMetrics fmSmall,
				Color text,
				Color textDim,
				Color borderC) {
			// If a race is fully completed, the right panel switches into a results view:
			// - hide the minimap
			// - show full standings until the track resets/stops
			RaceManager completedRm = null;
			try {
				if (raceService != null) {
					if (focused != null && focused.trackName != null) {
						RaceManager rm = raceService.get(focused.trackName);
						if (isRaceFullyCompleted(rm))
							completedRm = rm;
					}
					if (completedRm == null) {
						for (RaceManager rm : raceService.allRaces()) {
							if (isRaceFullyCompleted(rm)) {
								completedRm = rm;
								break;
							}
						}
					}
				}
			} catch (Throwable ignored) {
			}
			final boolean showCompletedResults = completedRm != null;

			// Map selection logic matches legacy buildRightPanel.
			TrackInfo mapTrack = focused;
			RaceManager mapRm = null;
			if (mapTrack != null
					&& (mapTrack.status == TrackStatus.RUNNING || mapTrack.status == TrackStatus.COUNTDOWN)) {
				try {
					if (plugin != null && plugin.getRaceService() != null) {
						mapRm = plugin.getRaceService().get(mapTrack.trackName);
					}
				} catch (Throwable ignored) {
				}
			}
			boolean mapActive = rightMap != null
					&& mapTrack != null
					&& (mapTrack.status == TrackStatus.RUNNING || mapTrack.status == TrackStatus.COUNTDOWN)
					&& mapTrack.centerline != null && !mapTrack.centerline.isEmpty();

			rightMap.style().display(mapActive);
			rightMapState.set(mapActive ? mapTrack : null, mapRm, border, bodyFont, fallbackFont);

			// Right lines list.
			java.util.List<String> lines;
			try {
				lines = showCompletedResults
						? buildCompletedRightLines(completedRm)
						: buildRightLines();
			} catch (Throwable t) {
				lines = java.util.List.of("(Không thể tải dữ liệu)");
			}

			int rowH = fmBody.getHeight() + Math.max(2, (int) Math.round(bodySize * 0.12));
			int smallH = fmSmall.getAscent() + fmSmall.getDescent();
			int spacerH = Math.max(6, fmSmall.getHeight() / 2);

			int idx = 0;
			for (String s : lines) {
				if (idx >= rightLineSlots.length)
					break;
				RightLineSlot slot = rightLineSlots[idx];
				if (s == null)
					continue;

				if (s.isBlank()) {
					slot.setSpacer(spacerH);
					idx++;
					continue;
				}
				if (s.startsWith("●")) {
					slot.setTrackRow(s, rowH);
					idx++;
					continue;
				}
				if (isRankingLine(s)) {
					slot.setRankingRow(s, rowH);
					idx++;
					continue;
				}

				slot.setSmallText(s, smallH);
				idx++;
			}
			for (int i = idx; i < rightLineSlots.length; i++)
				rightLineSlots[i].hide();
		}
	}

	private final class TrackSlot {
		final GraphicsElement block;
		final TrackSlotState state;
		final int trackInnerPad;

		TrackSlot(int trackInnerPad) {
			this.trackInnerPad = trackInnerPad;
			this.state = new TrackSlotState();
			this.block = new GraphicsElement((ctx, rect) -> state.paint(ctx, rect));
		}

		void hide() {
			block.style().display(false);
			state.track = null;
		}

		void show(TrackInfo ti) {
			block.style().display(true);
			state.track = ti;
		}

		void show(TrackInfo ti, int heightPx, Color text, Color textDim, Color borderC) {
			block.style().heightPx(Math.max(0, heightPx)).display(true);
			state.track = ti;
		}

		private final class TrackSlotState {
			private TrackInfo track;

			void paint(UiRenderContext ctx, UiRect rect) {
				if (ctx == null || ctx.g == null)
					return;
				TrackInfo ti = this.track;
				if (ti == null)
					return;
				if (uiCache == null)
					return;

				Graphics2D g = ctx.g;

				Font bodyFont = (uiCache != null ? uiCache.bodyFont : null);
				if (bodyFont == null)
					bodyFont = ctx.defaultFont;
				Font smallFont = (uiCache != null ? uiCache.smallFont : null);
				if (smallFont == null)
					smallFont = ctx.defaultFont;
				if (bodyFont == null || smallFont == null)
					return;

				Color globalAccent = uiCache != null ? uiCache.accentRef.get() : null;
				Color text = uiCache != null ? uiCache.textRef.get() : null;
				Color textDim = uiCache != null ? uiCache.textDimRef.get() : null;
				Color borderC = uiCache != null ? uiCache.borderRef.get() : null;
				if (text == null)
					text = new Color(0xEE, 0xEE, 0xEE);
				if (textDim == null)
					textDim = new Color(0xA6, 0xA6, 0xA6);
				if (borderC == null)
					borderC = new Color(0x3A, 0x3A, 0x3A);
				if (globalAccent == null)
					globalAccent = accentForStatus(TrackStatus.READY);

				Color rowAccent = accentForStatus(ti.status);
				int stripeW = Math.max(4, uiCache.border + 2);
				int gap = 10;

				// Left accent stripe.
				g.setColor(rowAccent);
				g.fillRect(rect.x(), rect.y(), stripeW, rect.h());

				int contentX = rect.x() + stripeW + gap;
				int contentY = rect.y();
				int contentW = Math.max(0, rect.w() - stripeW - gap);
				int contentH = rect.h();

				int contentPadVHalf = Math.max(4, (int) Math.round(bodyFont.getSize() * 0.22)) / 2;
				int contentPadH = 6;
				int innerX = contentX + contentPadH;
				int innerY = contentY + contentPadVHalf;
				int innerW = Math.max(0, contentW - (contentPadH * 2));

				boolean minimalRunningRow = (ti.status == TrackStatus.RUNNING);

				int miniW = 0;
				int miniH = 0;
				int mapPad = Math.max(3, uiCache.border + 1);
				if (!minimalRunningRow) {
					int availH = contentH - (mapPad * 2);
					if (availH < 24) {
						availH = Math.max(24, contentH - 2);
						mapPad = Math.max(1, (contentH - availH) / 2);
					}
					miniH = Math.max(24, availH);
					int desiredW = (int) Math.round(miniH * 4.0 / 3.0);
					// Compute based on actual available inner width to avoid overflow.
					int maxMiniW = Math.max(24, Math.max(0, innerW - 10));
					miniW = Math.min(maxMiniW, Math.max(24, desiredW));
				}
				int textW = Math.max(0, innerW - (minimalRunningRow ? 0 : miniW) - 10);

				// Line 1
				String line1 = "● " + ti.trackName + "  ["
						+ statusLabel(ti.status, ti.registered, ti.maxRacers, ti.countdownSeconds) + "]";
				g.setFont(bodyFont);
				java.awt.FontMetrics fmB = g.getFontMetrics(bodyFont);
				int y1 = innerY + fmB.getAscent();
				drawTrackRow(g, line1, innerX, y1, Math.min(textW, innerW), trackInnerPad, bodyFont, globalAccent, text,
						textDim);

				// Small lines
				g.setFont(smallFont);
				java.awt.FontMetrics fmS = g.getFontMetrics(smallFont);
				int bodyH = (uiCache != null && uiCache.fmBody != null) ? uiCache.fmBody.getHeight() : fmB.getHeight();
				int y = innerY + bodyH;
				int smallX = innerX + (18 + trackInnerPad);
				int smallMaxW = Math.max(0, Math.min(textW, innerW) - (18 + trackInnerPad));

				if (ti.status == TrackStatus.RUNNING) {
					String ln = "Đang đua: " + Math.max(0, ti.registered) + " người";
					g.setColor(textDim);
					drawTrimmed(g, ln, smallX, y + fmS.getAscent(), smallMaxW);
				} else if (ti.status == TrackStatus.COUNTDOWN) {
					String ln = "Người chơi: " + Math.max(0, ti.registered) + " người";
					g.setColor(textDim);
					drawTrimmed(g, ln, smallX, y + fmS.getAscent(), smallMaxW);
				} else {
					String ln2;
					if (ti.status == TrackStatus.REGISTERING) {
						ln2 = (ti.countdownSeconds > 0)
								? ("⌛ Còn lại: "
										+ dev.belikhun.boatracing.util.Time.formatCountdownSeconds(ti.countdownSeconds))
								: "⌛ Chờ người chơi...";
					} else if (ti.status == TrackStatus.ENDING) {
						ln2 = (ti.endingSeconds > 0)
								? ("⌛ Kết thúc sau: "
										+ dev.belikhun.boatracing.util.Time.formatCountdownSeconds(ti.endingSeconds))
								: "⌛ Kết thúc...";
					} else {
						ln2 = "Tối đa: " + Math.max(0, ti.maxRacers) + " người";
					}
					g.setColor(textDim);
					drawTrimmed(g, ln2, smallX, y + fmS.getAscent(), smallMaxW);

					String ln3 = recordLabel(ti.recordMillis, ti.recordHolderName);
					int y3 = y + fmS.getHeight();
					drawTrimmed(g, ln3, smallX, y3 + fmS.getAscent(), smallMaxW);
				}

				// Optional minimap thumb.
				if (!minimalRunningRow && miniW > 0 && miniH > 0) {
					int miniX = innerX + textW + 10;
					int miniY = contentY + mapPad;
					drawMiniMap(g, ti.centerline, miniX, miniY, miniW, miniH, rowAccent, borderC, textDim, smallFont,
							minimapStrokeFromBorder(uiCache.border));
				}
			}
		}
	}

	private enum RightLineType {
		NONE, TRACK, RANK, SMALL, SPACER
	}

	private final class RightLineSlot {
		final GraphicsElement row;
		final RightLineState state = new RightLineState();

		RightLineSlot(int rowH, int trackInnerPad) {
			this.row = new GraphicsElement((ctx, rect) -> state.paint(ctx, rect, trackInnerPad));
			this.row.style().heightPx(rowH);
		}

		void hide() {
			row.style().display(false);
			state.type = RightLineType.NONE;
			state.line = null;
		}

		void setSpacer(int h) {
			state.type = RightLineType.SPACER;
			state.line = null;
			row.style().heightPx(Math.max(0, h)).display(true);
		}

		void setTrackRow(String s, int h) {
			state.type = RightLineType.TRACK;
			state.line = s;
			row.style().heightPx(Math.max(0, h)).display(true);
		}

		void setRankingRow(String s, int h) {
			state.type = RightLineType.RANK;
			state.line = s;
			row.style().heightPx(Math.max(0, h)).display(true);
		}

		void setSmallText(String s, int h) {
			state.type = RightLineType.SMALL;
			state.line = s;
			row.style().heightPx(Math.max(0, h)).display(true);
		}

		private final class RightLineState {
			RightLineType type = RightLineType.NONE;
			String line;

			void paint(UiRenderContext ctx, UiRect rect, int trackInnerPad) {
				if (ctx == null || ctx.g == null)
					return;
				if (type == RightLineType.NONE || type == RightLineType.SPACER)
					return;
				Graphics2D g = ctx.g;

				Font bodyFont = (uiCache != null ? uiCache.bodyFont : null);
				if (bodyFont == null)
					bodyFont = ctx.defaultFont;
				Font smallFont = (uiCache != null ? uiCache.smallFont : null);
				if (smallFont == null)
					smallFont = ctx.defaultFont;
				if (bodyFont == null || smallFont == null)
					return;

				Color accent = uiCache != null ? uiCache.accentRef.get() : null;
				Color text = uiCache != null ? uiCache.textRef.get() : null;
				Color textDim = uiCache != null ? uiCache.textDimRef.get() : null;
				if (accent == null)
					accent = new Color(0xFF, 0xD7, 0x00);
				if (text == null)
					text = new Color(0xEE, 0xEE, 0xEE);
				if (textDim == null)
					textDim = new Color(0xA6, 0xA6, 0xA6);

				String s = line == null ? "" : line;
				if (type == RightLineType.TRACK) {
					g.setFont(bodyFont);
					java.awt.FontMetrics fm = g.getFontMetrics(bodyFont);
					drawTrackRow(g, s, rect.x(), rect.y() + fm.getAscent(), rect.w(), trackInnerPad, bodyFont, accent,
							text, textDim);
				} else if (type == RightLineType.RANK) {
					g.setFont(bodyFont);
					java.awt.FontMetrics fm = g.getFontMetrics(bodyFont);
					int pad = Math.max(0, trackInnerPad);
					int baseline = rect.y() + (rect.h() + fm.getAscent() - fm.getDescent()) / 2;
					drawRankingRow(g, s, rect.x() + pad, baseline, Math.max(0, rect.w() - pad), bodyFont, accent, text,
							textDim);
				} else {
					g.setFont(smallFont);
					java.awt.FontMetrics fm = g.getFontMetrics(smallFont);
					g.setColor(textDim);
					drawTrimmed(g, s, rect.x(), rect.y() + fm.getAscent(), rect.w());
				}
			}
		}
	}

	private final class RightMapState {
		private TrackInfo track;
		private RaceManager rm;
		private int border;
		private Font bodyFont;
		private Font fallbackFont;

		void set(TrackInfo track, RaceManager rm, int border, Font bodyFont, Font fallbackFont) {
			this.track = track;
			this.rm = rm;
			this.border = border;
			this.bodyFont = bodyFont;
			this.fallbackFont = fallbackFont;
		}

		void paint(UiRenderContext ctx, UiRect rect) {
			if (ctx == null || ctx.g == null)
				return;
			if (track == null || track.centerline == null || track.centerline.isEmpty())
				return;

			Font smallFont = (uiCache != null ? uiCache.smallFont : null);
			if (smallFont == null)
				smallFont = ctx.defaultFont;
			if (smallFont == null)
				return;

			java.util.List<MiniDot> dots = java.util.Collections.emptyList();
			try {
				dots = collectRacerDots(rm);
			} catch (Throwable ignored) {
				dots = java.util.Collections.emptyList();
			}

			Color borderC = uiCache != null ? uiCache.borderRef.get() : null;
			Color textDim = uiCache != null ? uiCache.textDimRef.get() : null;
			if (borderC == null)
				borderC = new Color(0x2A, 0x2D, 0x33);
			if (textDim == null)
				textDim = new Color(0xA6, 0xA6, 0xA6);

			float liveStroke = minimapStrokeFromBorder(border) * 1.28f;
			if (liveStroke < 2.0f)
				liveStroke = 2.0f;
			if (liveStroke > 7.5f)
				liveStroke = 7.5f;

			drawMiniMapWithDots(
					ctx.g,
					track.centerline,
					dots,
					rect.x(), rect.y(), rect.w(), rect.h(),
					accentForStatus(track.status),
					borderC,
					textDim,
					smallFont,
					liveStroke);

			if (track.status == TrackStatus.COUNTDOWN && track.countdownSeconds > 0) {
				try {
					drawCountdownOverlay(ctx.g, rect.x(), rect.y(), rect.w(), rect.h(), track.countdownSeconds, border,
							bodyFont, fallbackFont);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static UiElement spacer(int heightPx) {
		GraphicsElement e = new GraphicsElement((ctx, rect) -> {
		});
		e.style().heightPx(Math.max(0, heightPx));
		return e;
	}

	private static void drawCountdownOverlay(Graphics2D g, int x, int y, int w, int h, int seconds, int border,
			Font bodyFont, Font fallbackFont) {
		if (g == null)
			return;
		String s = String.valueOf(Math.max(0, seconds));
		int shadowOff = Math.max(1, border);

		// Start from a large font and shrink-to-fit.
		Font base = (bodyFont != null ? bodyFont : g.getFont());
		int start = Math.max(24, (int) Math.round(Math.min(w, h) * 0.55));
		Font f = base.deriveFont(Font.BOLD, (float) start);

		java.awt.FontMetrics fm = g.getFontMetrics(f);
		int maxW = Math.max(0, w - 12);
		int maxH = Math.max(0, h - 12);
		while ((fm.stringWidth(s) > maxW || fm.getHeight() > maxH) && f.getSize() > 10) {
			f = f.deriveFont((float) (f.getSize() - 2));
			fm = g.getFontMetrics(f);
		}

		int tx = x + Math.max(0, (w - fm.stringWidth(s)) / 2);
		int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;

		g.setFont(f);
		g.setColor(new Color(0, 0, 0, 180));
		drawStringWithFallback(g, s, tx + shadowOff, ty + shadowOff, f,
				fallbackFont != null ? fallbackFont : monoMatch(f));
		g.setColor(new Color(255, 255, 255, 240));
		drawStringWithFallback(g, s, tx, ty, f, fallbackFont != null ? fallbackFont : monoMatch(f));
	}

	private static void drawTrackRow(Graphics2D g, String line, int x, int y, int maxWidth, int innerPad, Font bodyFont,
			Color accent, Color text, Color textDim) {
		if (g == null)
			return;
		if (line == null)
			line = "";

		// Add a bit more breathing room from the left edge of the row background.
		int pad = Math.max(0, innerPad);
		x += pad;
		maxWidth = Math.max(0, maxWidth - pad);

		// Expected: "● <name> [<state...>]"
		String s = line;
		if (s.startsWith("●"))
			s = s.substring(1).trim();

		String name = s;
		String bracket = null;
		int bi = s.indexOf("[");
		int bj = s.lastIndexOf("]");
		if (bi >= 0 && bj > bi) {
			name = s.substring(0, bi).trim();
			bracket = s.substring(bi, bj + 1).trim();
		}

		g.setFont(bodyFont);
		Font fallbackFont = monoMatch(bodyFont);

		// Bullet
		g.setColor(accent);
		String bullet = "●";
		drawStringWithFallback(g, bullet, x, y, bodyFont, fallbackFont);
		// drawStringWithFallback() leaves the Graphics font as the last used runFont.
		// If the bullet can't be rendered by the board font, it will switch to the
		// fallback font;
		// restore bodyFont so the track name keeps the Minecraft-like font.
		g.setFont(bodyFont);
		int bx = x + stringWidthWithFallback(g, bullet + " ", bodyFont, fallbackFont);

		// Name
		g.setColor(text);
		int used = bx - x;
		int available = Math.max(0, maxWidth - used);
		if (bracket == null) {
			g.setFont(bodyFont);
			drawTrimmed(g, name, bx, y, available);
			return;
		}

		// Reserve some space for bracket (trim name first if needed)
		int bracketW = stringWidthWithFallback(g, " " + bracket, bodyFont, fallbackFont);
		int nameW = Math.max(0, available - bracketW);
		String trimmedName = trimToWidthWithFallback(g, name, nameW, bodyFont, fallbackFont);
		g.setFont(bodyFont);
		drawTrimmed(g, trimmedName, bx, y, nameW);

		// Always place bracket after a single space
		int brX = bx + Math.min(stringWidthWithFallback(g, trimmedName, bodyFont, fallbackFont), nameW)
				+ stringWidthWithFallback(g, " ", bodyFont, fallbackFont);

		// State color hint
		Color stC = textDim;
		String bl = bracket.toLowerCase(Locale.ROOT);
		if (bl.contains("đang chạy"))
			stC = new Color(0x56, 0xF2, 0x7A); // green-ish
		else if (bl.contains("đang kết thúc"))
			stC = new Color(0xFF, 0xB8, 0x4D);
		else if (bl.contains("đang đăng ký"))
			stC = accent;

		// Draw bracket: '[' + state + ']'
		g.setColor(textDim);
		// Keep bracket within maxWidth
		int maxBr = Math.max(0, maxWidth - (brX - x));
		if (maxBr <= 0)
			return;

		// Try to color the inside of bracket
		if (bracket.startsWith("[") && bracket.endsWith("]") && bracket.length() >= 2) {
			drawStringWithFallback(g, "[", brX, y, bodyFont, fallbackFont);
			g.setFont(bodyFont);
			int insideX = brX + stringWidthWithFallback(g, "[", bodyFont, fallbackFont);
			String inside = bracket.substring(1, bracket.length() - 1);
			g.setColor(stC);
			int insideMax = Math.max(0, maxBr - stringWidthWithFallback(g, "[]", bodyFont, fallbackFont));
			String insideTrim = trimToWidthWithFallback(g, inside, insideMax, bodyFont, fallbackFont);
			g.setFont(bodyFont);
			drawTrimmed(g, insideTrim, insideX, y, insideMax);
			int insideW = stringWidthWithFallback(g, insideTrim, bodyFont, fallbackFont);
			g.setColor(textDim);
			drawStringWithFallback(g, "]", insideX + insideW, y, bodyFont, fallbackFont);
		} else {
			g.setColor(stC);
			g.setFont(bodyFont);
			drawTrimmed(g, bracket, brX, y, maxBr);
		}
	}

	private static void drawRankingRow(Graphics2D g, String line, int x, int y, int maxWidth, Font bodyFont,
			Color accent, Color text, Color textDim) {
		if (g == null)
			return;
		if (line == null)
			line = "";
		g.setFont(bodyFont);
		Font fallbackFont = monoMatch(bodyFont);

		int close = line.indexOf(')');
		if (close <= 0) {
			g.setColor(text);
			drawTrimmed(g, line, x, y, maxWidth);
			return;
		}

		String posStr = line.substring(0, close).trim();
		String full = line.substring(close + 1).trim();

		// Optional metadata suffix separated by a double-space.
		// Example: "1) Racer V1/3 CP2/5 47%"
		String name = full;
		String meta = null;
		int sep = full.indexOf("  ");
		if (sep > 0) {
			name = full.substring(0, sep).trim();
			meta = full.substring(sep).trim();
			if (meta != null && meta.isBlank())
				meta = null;
		}

		int pos;
		try {
			pos = Integer.parseInt(posStr);
		} catch (Throwable ignored) {
			pos = 0;
		}

		Color posC = textDim;
		if (pos == 1)
			posC = accent;
		else if (pos == 2)
			posC = new Color(0xD8, 0xD8, 0xD8);
		else if (pos == 3)
			posC = new Color(0xD0, 0x95, 0x5A);

		String posDraw = (pos > 0 ? ("#" + pos) : "#?");
		g.setColor(posC);
		drawStringWithFallback(g, posDraw, x, y, bodyFont, fallbackFont);
		int nx = x + stringWidthWithFallback(g, posDraw + "  ", bodyFont, fallbackFont);

		int available = Math.max(0, maxWidth - (nx - x));
		if (meta == null || meta.isEmpty()) {
			g.setColor(text);
			// Name may contain legacy color codes (&/§). Render it with colors.
			String nameTrim = trimLegacyToWidthWithFallback(g, name, available, bodyFont, fallbackFont);
			drawLegacyStringWithFallback(g, nameTrim, nx, y, bodyFont, text);
			return;
		}

		// Reserve a little room for the racer name so meta doesn't consume the whole
		// row.
		final int reserveNamePx = 40;
		int metaMax = Math.max(0, available - reserveNamePx);
		// Meta may contain legacy color codes to color icons (e.g. &e✔, &a🗘).
		String metaTrim = trimLegacyToWidthWithFallback(g, meta, metaMax, bodyFont, fallbackFont);
		int metaW = legacyRenderedWidthWithFallback(g, metaTrim, bodyFont, fallbackFont);

		int gapW = stringWidthWithFallback(g, "  ", bodyFont, fallbackFont);
		int nameMax = Math.max(0, available - gapW - metaW);
		String nameTrim = trimLegacyToWidthWithFallback(g, name, nameMax, bodyFont, fallbackFont);

		g.setColor(text);
		drawLegacyStringWithFallback(g, nameTrim, nx, y, bodyFont, text);

		int rightEdge = nx + available;
		int metaX = Math.max(nx, rightEdge - metaW);
		// Render meta with legacy colors; defaultColor controls the non-colored parts.
		drawLegacyStringWithFallback(g, metaTrim, metaX, y, bodyFont, textDim);
	}

	// ===================== Legacy color rendering (for lobby board ranking)
	// =====================
	// Supports Minecraft legacy formatting codes using either '&' or '§'.
	// We implement color + bold/italic and ignore other formatting codes.

	private static int legacyRenderedWidthWithFallback(Graphics2D g, String s, Font baseFont, Font fallbackFont) {
		if (g == null)
			return 0;
		if (s == null || s.isEmpty())
			return 0;
		Font base = (baseFont != null ? baseFont : g.getFont());
		int baseStyle = base.getStyle();

		boolean bold = false;
		boolean italic = false;

		int w = 0;
		for (int i = 0; i < s.length();) {
			char ch = s.charAt(i);
			if ((ch == '&' || ch == '§') && (i + 1) < s.length()) {
				char code = s.charAt(i + 1);
				char lc = Character.toLowerCase(code);
				Color c = ColorTranslator.legacyChatColorToAwt(lc);
				if (c != null) {
					// Color codes reset formats in vanilla.
					bold = false;
					italic = false;
				} else if (lc == 'r') {
					bold = false;
					italic = false;
				} else if (lc == 'l') {
					bold = true;
				} else if (lc == 'o') {
					italic = true;
				}
				i += 2;
				continue;
			}

			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);
			int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
			Font useBase;
			try {
				useBase = base.deriveFont(style);
			} catch (Throwable ignored) {
				useBase = base;
			}
			Font useFallback = (fallbackFont != null ? fallbackFont : monoMatch(useBase));
			// Ensure fallback matches style/size.
			try {
				useFallback = useFallback.deriveFont(style, (float) useBase.getSize());
			} catch (Throwable ignored) {
			}

			boolean canPrimary;
			try {
				canPrimary = useBase.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}
			Font use = canPrimary ? useBase : useFallback;
			try {
				w += g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp)));
			} catch (Throwable ignored) {
			}
			i += len;
		}
		return w;
	}

	private static String trimLegacyToWidthWithFallback(Graphics2D g, String s, int maxWidth, Font baseFont,
			Font fallbackFont) {
		if (g == null)
			return "";
		if (s == null || s.isEmpty())
			return "";
		if (maxWidth <= 0)
			return "";

		Font base = (baseFont != null ? baseFont : g.getFont());
		int baseStyle = base.getStyle();

		boolean bold = false;
		boolean italic = false;
		int w = 0;
		int cutIndex = 0;

		for (int i = 0; i < s.length();) {
			char ch = s.charAt(i);
			if ((ch == '&' || ch == '§') && (i + 1) < s.length()) {
				char code = s.charAt(i + 1);
				char lc = Character.toLowerCase(code);
				Color c = ColorTranslator.legacyChatColorToAwt(lc);
				if (c != null) {
					bold = false;
					italic = false;
				} else if (lc == 'r') {
					bold = false;
					italic = false;
				} else if (lc == 'l') {
					bold = true;
				} else if (lc == 'o') {
					italic = true;
				}
				i += 2;
				// Keep codes that occur before the trimmed text (does not add width).
				cutIndex = i;
				continue;
			}

			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);
			int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
			Font useBase;
			try {
				useBase = base.deriveFont(style);
			} catch (Throwable ignored) {
				useBase = base;
			}
			Font useFallback = (fallbackFont != null ? fallbackFont : monoMatch(useBase));
			try {
				useFallback = useFallback.deriveFont(style, (float) useBase.getSize());
			} catch (Throwable ignored) {
			}

			boolean canPrimary;
			try {
				canPrimary = useBase.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}
			Font use = canPrimary ? useBase : useFallback;
			int cw = 0;
			try {
				cw = g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp)));
			} catch (Throwable ignored) {
				cw = 0;
			}

			if (w + cw > maxWidth)
				break;
			w += cw;
			i += len;
			cutIndex = i;
		}

		if (cutIndex <= 0)
			return "";
		return s.substring(0, Math.min(cutIndex, s.length()));
	}

	private static int drawLegacyRunWithFallback(Graphics2D g, String text, int x, int y, Font baseFont,
			Font fallbackBase, Color color, int style) {
		if (g == null)
			return 0;
		if (text == null || text.isEmpty())
			return 0;

		Font base = (baseFont != null ? baseFont : g.getFont());
		Font fallback = (fallbackBase != null ? fallbackBase : monoMatch(base));

		Font runFont;
		try {
			runFont = base.deriveFont(style);
		} catch (Throwable ignored) {
			runFont = base;
		}

		Font runFallback = fallback;
		try {
			runFallback = runFallback.deriveFont(style, (float) runFont.getSize());
		} catch (Throwable ignored) {
		}

		try {
			g.setColor(color != null ? color : Color.WHITE);
		} catch (Throwable ignored) {
		}

		try {
			drawStringWithFallback(g, text, x, y, runFont, runFallback);
		} catch (Throwable ignored) {
		}

		try {
			return stringWidthWithFallback(g, text, runFont, runFallback);
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private static int flushLegacyRunWithFallback(Graphics2D g, StringBuilder run, int x, int y, Font baseFont,
			Font fallbackBase, Color color, int style) {
		if (run == null || run.isEmpty())
			return 0;
		int w = drawLegacyRunWithFallback(g, run.toString(), x, y, baseFont, fallbackBase, color, style);
		try {
			run.setLength(0);
		} catch (Throwable ignored) {
		}
		return w;
	}

	private static void drawLegacyStringWithFallback(Graphics2D g, String s, int x, int y, Font baseFont,
			Color defaultColor) {
		if (g == null)
			return;
		if (s == null || s.isEmpty())
			return;

		Font base = (baseFont != null ? baseFont : g.getFont());
		int baseStyle = base.getStyle();
		Font baseFallback = monoMatch(base);

		boolean bold = false;
		boolean italic = false;
		Color curColor = (defaultColor != null ? defaultColor : Color.WHITE);

		int cx = x;
		StringBuilder run = new StringBuilder();
		Color runColor = curColor;
		int runStyle = baseStyle;

		for (int i = 0; i < s.length();) {
			char ch = s.charAt(i);
			if ((ch == '&' || ch == '§') && (i + 1) < s.length()) {
				char code = s.charAt(i + 1);
				char lc = Character.toLowerCase(code);

				Color nextColor = ColorTranslator.legacyChatColorToAwt(lc);
				boolean styleChanged = false;

				if (nextColor != null) {
					// Color codes reset formats.
					bold = false;
					italic = false;
					curColor = nextColor;
					styleChanged = true;
				} else if (lc == 'r') {
					bold = false;
					italic = false;
					curColor = (defaultColor != null ? defaultColor : Color.WHITE);
					styleChanged = true;
				} else if (lc == 'l') {
					if (!bold)
						styleChanged = true;
					bold = true;
				} else if (lc == 'o') {
					if (!italic)
						styleChanged = true;
					italic = true;
				} else {
					// Ignore other formats (k, n, m, etc.)
				}

				int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);

				if (styleChanged) {
					if (!run.isEmpty()) {
						cx += flushLegacyRunWithFallback(g, run, cx, y, base, baseFallback, runColor, runStyle);
					}
					runColor = curColor;
					runStyle = style;
				}

				i += 2;
				continue;
			}

			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);

			// If style/color drifted since last run, flush and start a new run.
			int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
			if (runColor != curColor || runStyle != style) {
				if (!run.isEmpty()) {
					cx += flushLegacyRunWithFallback(g, run, cx, y, base, baseFallback, runColor, runStyle);
				}
				runColor = curColor;
				runStyle = style;
			}

			run.appendCodePoint(cp);
			i += len;
		}

		if (!run.isEmpty()) {
			flushLegacyRunWithFallback(g, run, cx, y, base, baseFallback, runColor, runStyle);
		}
	}

	private static Font monoMatch(Font f) {
		if (f == null)
			return new Font(Font.MONOSPACED, Font.PLAIN, 12);
		return new Font(Font.MONOSPACED, f.getStyle(), f.getSize());
	}

	private static int stringWidthWithFallback(Graphics2D g, String s, Font primary, Font fallback) {
		if (g == null)
			return 0;
		if (s == null || s.isEmpty())
			return 0;
		Font p = (primary != null ? primary : g.getFont());
		Font f = (fallback != null ? fallback : monoMatch(p));

		int w = 0;
		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);
			boolean canPrimary;
			try {
				canPrimary = p != null && p.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}
			Font use = canPrimary ? p : f;
			try {
				w += g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp)));
			} catch (Throwable ignored) {
				// Best-effort fallback: count as 0 width if metrics fails.
			}
			i += len;
		}
		return w;
	}

	private static void drawStringWithFallback(Graphics2D g, String s, int x, int y, Font primary, Font fallback) {
		if (g == null)
			return;
		if (s == null || s.isEmpty())
			return;
		Font p = (primary != null ? primary : g.getFont());
		Font f = (fallback != null ? fallback : monoMatch(p));

		int cx = x;
		StringBuilder run = new StringBuilder();
		Font runFont = null;

		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);

			boolean canPrimary;
			try {
				canPrimary = p != null && p.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}

			Font use = canPrimary ? p : f;
			if (runFont == null)
				runFont = use;

			if (use != runFont) {
				if (!run.isEmpty()) {
					g.setFont(runFont);
					g.drawString(run.toString(), cx, y);
					cx += g.getFontMetrics(runFont).stringWidth(run.toString());
					run.setLength(0);
				}
				runFont = use;
			}

			run.appendCodePoint(cp);
			i += len;
		}

		if (!run.isEmpty()) {
			g.setFont(runFont);
			g.drawString(run.toString(), cx, y);
		}
	}

	private static String trimToWidthWithFallback(Graphics2D g, String s, int maxWidth, Font primary, Font fallback) {
		if (g == null)
			return "";
		if (s == null || s.isEmpty())
			return "";
		if (maxWidth <= 0)
			return "";

		String out = s;
		while (!out.isEmpty() && stringWidthWithFallback(g, out, primary, fallback) > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	private List<String> buildRightLines() {
		List<String> out = new ArrayList<>();
		if (raceService == null) {
			out.add("❌ Không có dữ liệu cuộc đua.");
			return out;
		}

		// Countdown tracks are shown as summaries (no live ranking yet).
		List<String> countdownLines = new ArrayList<>();

		record Entry(String track, int pos, UUID id, String name, String meta) {
		}
		List<Entry> entries = new ArrayList<>();

		try {
			for (RaceManager rm : raceService.allRaces()) {
				if (rm == null)
					continue;
				String track = "(không rõ)";
				try {
					String n = rm.getTrackConfig() != null ? rm.getTrackConfig().getCurrentName() : null;
					if (n != null && !n.isBlank())
						track = n;
				} catch (Throwable ignored) {
				}

				// Countdown (non-running) summary
				try {
					if (!rm.isRunning() && rm.isAnyCountdownActive()) {
						int racers = 0;
						try {
							racers = rm.getInvolved().size();
						} catch (Throwable ignored2) {
							racers = 0;
						}

						countdownLines.add("● " + track + "  [Đếm ngược]");
						countdownLines.add("Người chơi: " + racers);
						countdownLines.add("");
					}
				} catch (Throwable ignored) {
				}

				if (!rm.isRunning())
					continue;

				List<UUID> order = rm.getLiveOrder();
				int limit = Math.min(5, order.size());
				for (int i = 0; i < limit; i++) {
					UUID id = order.get(i);
					if (id == null)
						continue;
					var st = rm.getParticipantState(id);
					if (st != null && st.finished)
						continue;
					String name = nameOf(id);

					// Live telemetry: lap / checkpoint / total progress.
					String meta = "";
					try {
						int lapTotal = Math.max(1, rm.getTotalLaps());
						int lapCurrent = 1;
						int passedCp = 0;
						int totalCp = 0;
						if (st != null) {
							lapCurrent = Math.min(lapTotal, Math.max(1, st.currentLap + 1));
							passedCp = Math.max(0, st.nextCheckpointIndex);
						}

						try {
							totalCp = (rm.getTrackConfig() != null && rm.getTrackConfig().getCheckpoints() != null)
									? rm.getTrackConfig().getCheckpoints().size()
									: 0;
						} catch (Throwable ignored2) {
							totalCp = 0;
						}

						// Requested UX: use ✔ for checkpoint (yellow) and 🗘 for lap (green).
						// Use &r to reset back to the default meta color (textDim) after each colored
						// segment.
						String lapPart = "&a🗘 " + lapCurrent + "/" + lapTotal + "&r";
						String cpPart = (totalCp > 0)
								? ("&e✔ " + Math.min(passedCp, totalCp) + "/" + totalCp + "&r")
								: ("&e✔ -&r");

						double lapRatio = 0.0;
						try {
							lapRatio = rm.getLapProgressRatio(id);
						} catch (Throwable ignored2) {
							lapRatio = 0.0;
						}
						if (!Double.isFinite(lapRatio))
							lapRatio = 0.0;
						lapRatio = Math.max(0.0, Math.min(1.0, lapRatio));

						double overall = ((st == null ? 0.0 : (double) Math.max(0, st.currentLap)) + lapRatio)
								/ (double) lapTotal;
						overall = Math.max(0.0, Math.min(1.0, overall));
						int pct = (int) Math.round(overall * 100.0);

						meta = lapPart + "  " + cpPart + "  " + pct + "%";
					} catch (Throwable ignored2) {
						meta = "";
					}

					entries.add(new Entry(track, i + 1, id, name, meta));
				}
			}
		} catch (Throwable ignored) {
		}

		if (!countdownLines.isEmpty()) {
			// Trim possible trailing blank
			while (!countdownLines.isEmpty() && countdownLines.get(countdownLines.size() - 1).isBlank())
				countdownLines.remove(countdownLines.size() - 1);
			out.addAll(countdownLines);
			// If there are also running races, we'll append them below.
			if (!entries.isEmpty())
				out.add("");
		}

		if (entries.isEmpty()) {
			if (out.isEmpty())
				out.add("(Chưa có cuộc đua nào đang chạy)");
			return out;
		}

		// Sort by track then position.
		entries.sort(Comparator.comparing(Entry::track, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Entry::pos));

		String currentTrack = null;
		int lines = 0;
		for (Entry e : entries) {
			if (lines >= 18)
				break;

			if (currentTrack == null || !currentTrack.equalsIgnoreCase(e.track)) {
				currentTrack = e.track;
				if (!out.isEmpty()) {
					out.add("");
					lines++;
					if (lines >= 18)
						break;
				}
				out.add("● " + currentTrack.toUpperCase(Locale.ROOT));
				lines++;
				if (lines >= 18)
					break;
			}

			String racer = e.name;
			try {
				if (profileManager != null) {
					// Keep legacy color codes so the ranking renderer can show colored racer
					// display.
					racer = profileManager.formatRacerLegacy(e.id, e.name);
				}
			} catch (Throwable ignored) {
			}
			String meta = e.meta;
			if (meta != null && !meta.isBlank())
				out.add(e.pos + ") " + racer + "  " + meta);
			else
				out.add(e.pos + ") " + racer);
			lines++;
		}

		return out;
	}

	private static boolean isRaceFullyCompleted(RaceManager rm) {
		if (rm == null)
			return false;
		// Only treat as completed when it's in an idle (post-race) state.
		try {
			if (rm.isRunning())
				return false;
			if (rm.isAnyCountdownActive())
				return false;
			if (rm.isRegistering())
				return false;
		} catch (Throwable ignored) {
		}

		java.util.List<RaceManager.ParticipantState> standings;
		try {
			standings = rm.getStandings();
		} catch (Throwable t) {
			standings = java.util.Collections.emptyList();
		}
		if (standings == null || standings.isEmpty())
			return false;
		for (RaceManager.ParticipantState s : standings) {
			if (s == null || !s.finished)
				return false;
		}
		return true;
	}

	private List<String> buildCompletedRightLines(RaceManager rm) {
		List<String> out = new java.util.ArrayList<>();
		if (rm == null) {
			out.add("(Không có dữ liệu)");
			return out;
		}

		String track = "(không rõ)";
		try {
			String n = rm.getTrackConfig() != null ? rm.getTrackConfig().getCurrentName() : null;
			if (n != null && !n.isBlank())
				track = n;
		} catch (Throwable ignored) {
		}

		// Use a track header row style, then emit full standings (one row per racer).
		out.add("● " + track.toUpperCase(java.util.Locale.ROOT));

		java.util.List<RaceManager.ParticipantState> standings;
		try {
			standings = rm.getStandings();
		} catch (Throwable t) {
			standings = java.util.Collections.emptyList();
		}

		if (standings == null || standings.isEmpty()) {
			out.add("(Chưa có kết quả)");
			return out;
		}

		for (int i = 0; i < standings.size(); i++) {
			RaceManager.ParticipantState s = standings.get(i);
			if (s == null)
				continue;
			UUID id = s.id;
			if (id == null)
				continue;

			String name = nameOf(id);
			String racer = name;
			try {
				if (profileManager != null) {
					racer = profileManager.formatRacerLegacy(id, name);
				}
			} catch (Throwable ignored) {
			}

			long finishMs = 0L;
			try {
				finishMs = Math.max(0L, s.finishTimeMillis - rm.getRaceStartMillis())
						+ (long) Math.max(0, s.penaltySeconds) * 1000L;
			} catch (Throwable ignored) {
				finishMs = 0L;
			}

			String meta = "";
			try {
				meta = "&e⌚ " + dev.belikhun.boatracing.util.Time.formatStopwatchMillis(finishMs) + "&r";
				if (s.penaltySeconds > 0) {
					meta += "  &c(+"
							+ dev.belikhun.boatracing.util.Time.formatStopwatchMillis((long) s.penaltySeconds * 1000L)
							+ ")&r";
				}
			} catch (Throwable ignored) {
				meta = "";
			}

			int pos = i + 1;
			if (meta != null && !meta.isBlank())
				out.add(pos + ") " + racer + "  " + meta);
			else
				out.add(pos + ") " + racer);
		}

		return out;
	}

	private static String nameOf(UUID id) {
		try {
			var op = Bukkit.getOfflinePlayer(id);
			if (op != null && op.getName() != null)
				return op.getName();
		} catch (Throwable ignored) {
		}
		return id.toString().substring(0, 8);
	}

	private static void drawTrimmed(Graphics2D g, String line, int x, int y, int maxWidth) {
		if (g == null)
			return;
		if (line == null)
			line = "";
		String s = line;
		Font primary = g.getFont();
		Font fallback = monoMatch(primary);
		if (maxWidth <= 0)
			return;

		// Fast path
		if (stringWidthWithFallback(g, s, primary, fallback) <= maxWidth) {
			drawStringWithFallback(g, s, x, y, primary, fallback);
			return;
		}

		// Ellipsize using "..." (safe for Minecraft fonts).
		final String ell = "...";
		int ellW = stringWidthWithFallback(g, ell, primary, fallback);
		int target = Math.max(0, maxWidth - ellW);
		String cut = trimToWidthWithFallback(g, s, target, primary, fallback);
		drawStringWithFallback(g, cut + ell, x, y, primary, fallback);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static BlockFace normalizeCardinal(BlockFace face) {
		if (face == null)
			return null;
		return switch (face) {
			case NORTH, SOUTH, EAST, WEST -> face;
			default -> null;
		};
	}

}
