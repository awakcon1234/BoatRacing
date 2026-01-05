package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.event.EventParticipant;
import dev.belikhun.boatracing.event.EventParticipantStatus;
import dev.belikhun.boatracing.event.EventService;
import dev.belikhun.boatracing.event.EventState;
import dev.belikhun.boatracing.event.RaceEvent;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardFontLoader;
import dev.belikhun.boatracing.integrations.geyser.GeyserCompat;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardPlacement;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardViewers;
import dev.belikhun.boatracing.integrations.mapengine.board.MapEngineBoardDisplay;
import dev.belikhun.boatracing.integrations.mapengine.board.RenderBuffers;
import dev.belikhun.boatracing.integrations.mapengine.ui.ColumnContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.LegacyTextElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.GraphicsElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.ImageElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.RowContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.TextElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiAlign;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiComposer;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiInsets;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiJustify;
import dev.belikhun.boatracing.integrations.mapengine.ui.BroadcastTheme;
import dev.belikhun.boatracing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
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
	private Font iconFont;

	// Logo
	// Bundled logo resource path (inside plugin JAR)
	private static final String LOGO_RESOURCE_PATH = "imgs/logo.png";
	private BufferedImage logoImage;
	private long logoLastLoadMs;

	// Runtime
	private final Set<UUID> spawnedTo = new HashSet<>();
	private final Set<UUID> eligibleViewers = new HashSet<>();
	private static final long REENSURE_EXISTING_VIEWERS_MS = 5000L;
	private long lastReensureAtMs = 0L;

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

		double fontScale = plugin.getConfig().getDouble("mapengine.event-board.font.scale", 1.25);
		if (fontScale < 0.5)
			fontScale = 0.5;
		if (fontScale > 3.0)
			fontScale = 3.0;

		int titleBaseSize = plugin.getConfig().getInt("mapengine.event-board.font.title-size", 18);
		int bodyBaseSize = plugin.getConfig().getInt("mapengine.event-board.font.body-size", 14);
		int titleSize = clamp((int) Math.round(titleBaseSize * fontScale), 10, 96);
		int bodySize = clamp((int) Math.round(bodyBaseSize * fontScale), 8, 72);

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

		// Optional icon font (Font Awesome)
		iconFont = null;
		try (InputStream is = plugin.getResource("fonts/fa-solid-900.ttf")) {
			if (is != null) {
				Font fa = Font.createFont(Font.TRUETYPE_FONT, is);
				iconFont = fa;
			}
		} catch (Throwable ignored) {
			iconFont = null;
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
		eligibleViewers.clear();

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
		// - everyone in lobby (not in any race)
		eligibleViewers.clear();
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p == null || !p.isOnline() || p.getWorld() == null)
				continue;
			if (GeyserCompat.isBedrockPlayer(p.getUniqueId()))
				continue;
			try {
				if (plugin != null && plugin.getRaceService() != null
						&& plugin.getRaceService().findRaceFor(p.getUniqueId()) != null)
					continue;
			} catch (Throwable ignored) {
			}
			if (!BoardViewers.isWithinRadiusChunks(p, placement, visibleRadiusChunks))
				continue;
			eligibleViewers.add(p.getUniqueId());
		}

		// Spawn/despawn
		for (java.util.Iterator<UUID> it = spawnedTo.iterator(); it.hasNext();) {
			UUID id = it.next();
			if (eligibleViewers.contains(id))
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p != null && p.isOnline()) {
				try {
					boardDisplay.removeViewer(p);
				} catch (Throwable ignored) {
				}
			}
			it.remove();
		}

		long now = System.currentTimeMillis();
		boolean reensure = (now - lastReensureAtMs) >= REENSURE_EXISTING_VIEWERS_MS;
		if (reensure)
			lastReensureAtMs = now;

		for (UUID id : eligibleViewers) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				if (reensure || !spawnedTo.contains(id))
					boardDisplay.ensureViewer(p);
				spawnedTo.add(id);
			} catch (Throwable ignored) {
			}
		}

		if (spawnedTo.isEmpty())
			return;

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
		if (st == EventState.DISABLED)
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
		int max = 0;
		try {
			max = (e != null) ? Math.max(0, e.maxParticipants) : 0;
		} catch (Throwable ignored) {
			max = 0;
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

		String racersText = String.valueOf(racers);
		if (max > 0)
			racersText = racersText + "/" + max;

		return "Tay đua: " + racersText
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
		int pad = Math.max(18, (int) Math.round(Math.min(w, h) * 0.04));
		int gap = Math.max(10, (int) Math.round(pad * 0.55));

		BroadcastTheme.Palette pal = BroadcastTheme.palette(accentFor(Screen.EVENT_START_COUNTDOWN));

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));
		Font header = title.deriveFont(Font.BOLD, Math.max(26f, title.getSize2D() * 1.55f));
		Font meta = body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D() * 1.0f));
		Font section = title.deriveFont(Font.BOLD, Math.max(24f, title.getSize2D() * 1.40f));
		Font countdownLabel = title.deriveFont(Font.BOLD, Math.max(22f, title.getSize2D() * 1.25f));
		Font countdown = title.deriveFont(Font.BOLD, Math.max(72f, title.getSize2D() * 3.9f));
		// Timer icon (size will be corrected even if it uses fallback font).
		Font timerIcon = countdown.deriveFont(Font.PLAIN, Math.max(22f, countdown.getSize2D() * 0.92f));

		Color bg = pal.bg0();
		// Keep a natural base but add controlled accent tinting for a more colorful HUD.
		Color panel = pal.panelTint(0.05);
		Color panel2 = pal.panelTint(0.10);
		Color fg = pal.text();
		Color muted = pal.textDim();
		Color accent = pal.accent();
		Color accentSoft = pal.accentSoft(160);
		Color mutedSoft = pal.textDimSoft(110);
		Color borderSoft = pal.accentSoft(110);

		ColumnContainer root = new ColumnContainer()
				.gap(gap)
				.alignItems(UiAlign.STRETCH)
				.justifyContent(UiJustify.START);
		root.style().background(bg).padding(UiInsets.all(pad));

		root.add(buildTopStripe(accent, pad));
		root.add(buildHeaderBar(e, w, h, pad, gap, panel, fg, muted, header, meta, true));

		// Main layout: modern HUD (two-column cards)
		RowContainer main = new RowContainer()
				.gap(Math.max(gap, pad))
				.alignItems(UiAlign.STRETCH)
				.justifyContent(UiJustify.START);
		main.style().flexGrow(1);

		int cardPad = Math.max(12, pad / 2);
		int stripeH = Math.max(3, pad / 5);
		int mainGap = Math.max(gap, pad);
		int contentW = Math.max(0, w - (pad * 2));
		int colW = Math.max(0, (contentW - mainGap) / 2);
		int colMax = Math.max(0, colW);

		// Left card: participants
		ColumnContainer left = new ColumnContainer()
				.gap(Math.max(8, gap / 2))
				.alignItems(UiAlign.STRETCH)
				.justifyContent(UiJustify.START);
		left.style().flexGrow(1).widthPx(colW).background(pal.panelTint(0.08)).padding(UiInsets.all(cardPad)).border(borderSoft, 2);
		left.add(buildTopStripe(mutedSoft, stripeH * 5));

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

		RowContainer leftHead = new RowContainer().gap(Math.max(10, gap / 2)).alignItems(UiAlign.END).justifyContent(UiJustify.START);
		Font participantsFont = body.deriveFont(Font.BOLD, Math.max(17f, body.getSize2D() * 1.15f));
		leftHead.add(text("NGƯỜI THAM GIA", section, fg, TextElement.Align.LEFT));
		Spacer leftPush = new Spacer();
		leftPush.style().flexGrow(1);
		leftHead.add(leftPush);
		int max = 0;
		try {
			max = e != null ? Math.max(0, e.maxParticipants) : 0;
		} catch (Throwable ignored) {
			max = 0;
		}
		String racersText = String.valueOf(racers);
		if (max > 0)
			racersText = racersText + "/" + max;
		leftHead.add(text(racersText + " TAY ĐUA", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), muted, TextElement.Align.RIGHT));
		left.add(leftHead);
		left.add(buildParticipantsRows(e, colMax, participantsFont, fg));
		left.add(text("ⓘ Ở gần bảng để theo dõi cập nhật.", body.deriveFont(Font.PLAIN, Math.max(13f, body.getSize2D() * 0.95f)), muted, TextElement.Align.LEFT));
		main.add(left);

		// Right card: hero countdown
		ColumnContainer right = new ColumnContainer()
				.gap(Math.max(10, gap))
				.alignItems(UiAlign.STRETCH)
				.justifyContent(UiJustify.START);
		right.style().flexGrow(1).widthPx(colW).background(pal.panelTint(0.14)).padding(UiInsets.all(cardPad)).border(borderSoft, 2);
		right.add(buildTopStripe(accentSoft, stripeH * 5));

		RowContainer chipRow = new RowContainer().gap(Math.max(10, gap / 2)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.START);
		TextElement chip = text("SẮP BẮT ĐẦU", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), bg, TextElement.Align.LEFT);
		chip.style().padding(UiInsets.symmetric(Math.max(4, cardPad / 3), Math.max(10, cardPad))).background(accent);
		chipRow.add(chip);
		Spacer chipPush = new Spacer();
		chipPush.style().flexGrow(1);
		chipRow.add(chipPush);
		right.add(chipRow);

		right.add(text("BẮT ĐẦU TRONG", countdownLabel, fg, TextElement.Align.CENTER));

		long sec = secondsUntilEventStart(e);
		String timer = formatHms(sec);
		RowContainer timerRow = new RowContainer().gap(Math.max(14, gap)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		timerRow.add(text("⏳", timerIcon, fg, TextElement.Align.LEFT));
		timerRow.add(text(timer, countdown, fg, TextElement.Align.LEFT));
		right.add(timerRow);

		UiElement trackStrip = buildTrackProgressStrip(
				e,
				body.deriveFont(Font.BOLD, Math.max(16f, body.getSize2D() * 1.10f)),
				body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D() * 1.0f)),
				fg,
				bg,
				Math.max(10, cardPad / 2),
				Math.max(10, gap)
		);
		if (trackStrip != null)
			right.add(trackStrip);

		right.add(text("Hãy chuẩn bị thuyền và sẵn sàng xuất phát!", body.deriveFont(Font.PLAIN, Math.max(14f, body.getSize2D())), muted, TextElement.Align.CENTER));
		main.add(right);

		root.add(main);

		// Footer (bottom-right time)
		root.add(buildFooterClock(panel, muted, body, pad, gap));

		return root;
	}

	private UiElement buildTrackProgressStrip(
			RaceEvent e,
			Font nameFont,
			Font chipFont,
			Color fg,
			Color chipText,
			int pad,
			int gap
	) {
		List<String> pool = (e != null ? e.trackPool : null);
		if (pool == null || pool.isEmpty())
			return null;

		int idx = 0;
		try {
			idx = Math.max(0, e != null ? e.currentTrackIndex : 0);
		} catch (Throwable ignored) {
			idx = 0;
		}
		if (idx >= pool.size())
			idx = pool.size() - 1;

		int start = Math.max(0, idx - 1);
		if (pool.size() >= 3)
			start = Math.min(start, Math.max(0, pool.size() - 3));
		int end = Math.min(pool.size(), start + 3);

		RowContainer strip = new RowContainer()
				.gap(Math.max(10, gap))
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.CENTER);
		strip.style().padding(UiInsets.all(pad));

		Color done = BroadcastTheme.ACCENT_RUNNING;
		Color next = BroadcastTheme.ACCENT_READY;
		Color wait = BroadcastTheme.ACCENT_OFF;

		int shown = 0;
		for (int i = start; i < end; i++) {
			String raw = pool.get(i);
			String name = (raw == null || raw.isBlank()) ? String.format(java.util.Locale.ROOT, "TRACK%d", (i + 1)) : raw.trim();
			name = name.toUpperCase(java.util.Locale.ROOT);

			String status;
			Color statusBg;
			if (i < idx) {
				status = "ĐÃ HOÀN THÀNH";
				statusBg = done;
			} else if (i == idx) {
				status = "TIẾP THEO";
				statusBg = next;
			} else {
				status = "ĐANG CHỜ";
				statusBg = wait;
			}

			ColumnContainer track = new ColumnContainer().gap(Math.max(4, gap / 3)).alignItems(UiAlign.START);
			track.style().widthPx(0).flexGrow(1);
			track.add(text(name, nameFont, fg, TextElement.Align.LEFT));

			TextElement chip = text(status, chipFont, chipText, TextElement.Align.LEFT);
			chip.style().padding(UiInsets.symmetric(Math.max(4, pad / 3), Math.max(10, pad))).background(statusBg);
			track.add(chip);

			strip.add(track);
			shown++;

			if (i < (end - 1)) {
				int arrowSize = Math.max(20, (int) Math.round(nameFont.getSize2D() * 1.45));
				int arrowW = Math.max(34, (int) Math.round(arrowSize * 1.7));
				int arrowH = Math.max(18, (int) Math.round(arrowSize * 1.1));

				UiElement arrowEl;
				if (iconFont != null) {
					TextElement arrow = new TextElement("\uf178")
							.font(iconFont.deriveFont(Font.PLAIN, (float) arrowSize))
							.color(fg)
							.align(TextElement.Align.CENTER)
							.ellipsis(false);
					arrow.style().widthPx(arrowW).heightPx(arrowH).margin(UiInsets.symmetric(0, Math.max(4, gap / 5)));
					arrowEl = arrow;
				} else {
					GraphicsElement arrow = new GraphicsElement((ctx, rect) -> {
						if (ctx == null || ctx.g == null)
							return;
						ctx.applyDefaultHints();
						java.awt.Graphics2D g2 = ctx.g;
						int x = rect.x();
						int y = rect.y();
						int ww = rect.w();
						int hh = rect.h();
						if (ww <= 0 || hh <= 0)
							return;

						int padX = Math.max(3, ww / 10);
						int midY = y + hh / 2;
						int headW = Math.max(8, ww / 4);
						int headH = Math.max(8, hh / 2);
						int x1 = x + padX;
						int x2 = x + ww - padX - headW;
						int xTip = x + ww - padX;

						java.awt.Stroke old = g2.getStroke();
						try {
							g2.setColor(fg);
							g2.setStroke(new java.awt.BasicStroke(Math.max(2f, hh / 8f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
							g2.drawLine(x1, midY, x2, midY);

							int hy = Math.max(3, headH / 2);
							int[] px = new int[] { xTip, x2, x2 };
							int[] py = new int[] { midY, midY - hy, midY + hy };
							g2.fillPolygon(px, py, 3);
						} catch (Throwable ignored) {
						} finally {
							try {
								g2.setStroke(old);
							} catch (Throwable ignored) {
							}
						}
					});
					arrow.style().widthPx(arrowW).heightPx(arrowH).margin(UiInsets.symmetric(0, Math.max(4, gap / 5)));
					arrowEl = arrow;
				}
				strip.add(arrowEl);
			}
		}

		if (shown <= 0)
			return null;
		return strip;
	}

	private UiElement buildTopStripe(Color accent, int pad) {
		GraphicsElement topStripe = new GraphicsElement((ctx, rect) -> {
			if (ctx == null || ctx.g == null)
				return;
			if (accent == null)
				return;
			ctx.g.setColor(accent);
			ctx.g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
		});
		topStripe.style().heightPx(Math.max(3, pad / 5));
		return topStripe;
	}

	private UiElement buildHeaderBar(
			RaceEvent e,
			int w,
			int h,
			int pad,
			int gap,
			Color panel,
			Color fg,
			Color muted,
			Font header,
			Font meta,
			boolean showMeta
	) {
		RowContainer top = new RowContainer()
				.gap(gap)
				.alignItems(UiAlign.START)
				.justifyContent(UiJustify.START);
		top.style().background(panel).padding(UiInsets.all(Math.max(10, pad / 2)));

		int logoH = Math.max(54, (int) Math.round(h * 0.115));
		logoH = Math.min(logoH, Math.max(54, (int) Math.round(h * 0.15)));
		BufferedImage logo = loadLogoIfNeeded();
		UiElement logoBox;
		if (logo != null) {
			ImageElement logoEl = new ImageElement(logo)
					.fit(ImageElement.Fit.CONTAIN)
					.smoothing(true);
			logoEl.style().heightPx(logoH).padding(UiInsets.all(0)).background(panel);
			logoBox = logoEl;
		} else {
			ColumnContainer placeholder = new ColumnContainer().alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
			placeholder.style().widthPx(Math.max(140, logoH * 2)).heightPx(logoH).background(panel);
			placeholder.add(text("LOGO", meta, fg, TextElement.Align.CENTER));
			logoBox = placeholder;
		}
		top.add(logoBox);

		Spacer push = new Spacer();
		push.style().flexGrow(1);
		top.add(push);

		ColumnContainer headRight = new ColumnContainer().gap(Math.max(3, gap / 3)).alignItems(UiAlign.END);
		String eventTitle = safeEventName(e);
		try {
			eventTitle = eventTitle.toUpperCase(java.util.Locale.ROOT);
		} catch (Throwable ignored) {
		}
		headRight.add(text(eventTitle, header, fg, TextElement.Align.RIGHT));
		if (showMeta) {
			headRight.add(text(fmtEventMetaHeaderLine(e), meta, muted, TextElement.Align.RIGHT));
		}
		top.add(headRight);
		return top;
	}

	private UiElement buildFooterClock(Color panel, Color muted, Font body, int pad, int gap) {
		RowContainer footer = new RowContainer().gap(Math.max(6, gap / 3)).alignItems(UiAlign.END).justifyContent(UiJustify.END);
		footer.style().flexGrow(0);
		footer.style().background(panel).padding(UiInsets.symmetric(Math.max(6, pad / 4), Math.max(10, pad / 2)));
		String nowText = nowClockString();
		footer.add(text("⏰ " + nowText, body.deriveFont(Font.PLAIN, Math.max(14f, body.getSize2D())), muted, TextElement.Align.RIGHT));
		return footer;
	}

	private static Color accentFor(Screen screen) {
		if (screen == null)
			return BroadcastTheme.ACCENT_OFF;
		return switch (screen) {
			case EVENT_START_COUNTDOWN -> BroadcastTheme.ACCENT_COUNTDOWN;
			case NEXT_TRACK -> BroadcastTheme.ACCENT_REGISTERING;
			case CURRENT_RACE -> BroadcastTheme.ACCENT_RUNNING;
			case EVENT_FINISHED -> BroadcastTheme.ACCENT_READY;
		};
	}

	private static String fmtEventMetaHeaderLine(RaceEvent e) {
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

		String when = "--/--/----";
		try {
			if (e != null && e.startTimeMillis > 0L) {
				java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(e.startTimeMillis)
						.atZone(java.time.ZoneId.systemDefault());
				// Compact date only to avoid truncation on smaller boards.
				when = String.format(java.util.Locale.ROOT, "%02d/%02d/%04d",
						z.getDayOfMonth(), z.getMonthValue(), z.getYear());
			}
		} catch (Throwable ignored) {
			when = "--/--/----";
		}

		return racers + " TAY ĐUA" + " ● " + tracks + " ĐƯỜNG ĐUA" + " ● " + when;
	}

	private UiElement buildParticipantsRows(RaceEvent e, int maxWidth, Font font, Color color) {
		java.util.List<java.util.Map.Entry<java.util.UUID, String>> list = new java.util.ArrayList<>();
		try {
			if (e != null && e.participants != null) {
				for (var en : e.participants.entrySet()) {
					java.util.UUID id = en.getKey();
					EventParticipant ep = en.getValue();
					if (id == null || ep == null)
						continue;
					if (ep.status == EventParticipantStatus.LEFT)
						continue;
					String name = (ep.nameSnapshot == null || ep.nameSnapshot.isBlank()) ? "(không rõ)" : ep.nameSnapshot;
					list.add(new java.util.AbstractMap.SimpleEntry<>(id, name));
				}
			}
		} catch (Throwable ignored) {
			list.clear();
		}

		list.sort((a, b) -> {
			String an = a.getValue();
			String bn = b.getValue();
			if (an == null)
				an = "";
			if (bn == null)
				bn = "";
			return an.compareToIgnoreCase(bn);
		});

		java.util.List<String> displays = new java.util.ArrayList<>();
		for (var it : list) {
			displays.add(formatRacerBoard(it.getKey(), it.getValue()));
		}

		ColumnContainer rows = new ColumnContainer().gap(Math.max(6, font.getSize() / 2)).alignItems(UiAlign.STRETCH);
		if (displays.isEmpty()) {
			rows.add(text("(Chưa có)", font, color, TextElement.Align.CENTER));
			return rows;
		}

		java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
		double maxRowWidth = Math.max(140.0, maxWidth > 0 ? maxWidth * 0.96 : 320.0);
		double sepWidth = font.getStringBounds("●", frc).getWidth();
		int gapPx = Math.max(10, font.getSize() / 2);

		RowContainer row = new RowContainer().gap(gapPx).alignItems(UiAlign.CENTER).justifyContent(UiJustify.START);
		boolean rowHasEntries = false;
		double used = 0.0;
		int shown = 0;
		int maxShown = Math.max(4, Math.min(64, displays.size()));

		for (String disp : displays) {
			if (shown >= maxShown) break;
			double width = font.getStringBounds(stripLegacy(disp), frc).getWidth();
			double nextWidth = width + (rowHasEntries ? (sepWidth + gapPx) : 0.0);
			boolean needsBreak = rowHasEntries && (used + nextWidth > maxRowWidth);
			if (needsBreak) {
				rows.add(row);
				row = new RowContainer().gap(gapPx).alignItems(UiAlign.CENTER).justifyContent(UiJustify.START);
				rowHasEntries = false;
				used = 0.0;
			}

			if (rowHasEntries) {
				row.add(text("●", font, color, TextElement.Align.CENTER));
				used += sepWidth + gapPx;
			}
			LegacyTextElement racer = new LegacyTextElement(disp)
					.font(font)
					.defaultColor(color)
					.align(LegacyTextElement.Align.LEFT)
					.trimToFit(true);
			row.add(racer);
			used += width;
			rowHasEntries = true;
			shown++;
		}

		if (rowHasEntries) rows.add(row);
		if (displays.size() > shown) {
			rows.add(text("…", font, color, TextElement.Align.CENTER));
		}
		return rows;
	}

	private static String stripLegacy(String s) {
		if (s == null) return "";
		// Remove legacy color codes (& or §) to approximate render width.
		return s.replaceAll("[&§][0-9A-FK-ORa-fk-or]", "");
	}

	private String formatRacerBoard(java.util.UUID id, String name) {
		String n = (name == null || name.isBlank()) ? "(không rõ)" : name;
		try {
			if (plugin != null && plugin.getProfileManager() != null) {
				return plugin.getProfileManager().formatRacerLegacy(id, n);
			}
		} catch (Throwable ignored) {
		}
		return "&f[● -] " + n;
	}

	private static final class Spacer extends UiElement {
		@Override
		protected dev.belikhun.boatracing.integrations.mapengine.ui.UiMeasure onMeasure(
				dev.belikhun.boatracing.integrations.mapengine.ui.UiRenderContext ctx,
				int maxWidth,
				int maxHeight
		) {
			return dev.belikhun.boatracing.integrations.mapengine.ui.UiMeasure.of(0, 0);
		}
	}

	private UiElement buildNextTrackUi(RaceEvent e, int w, int h) {
		int pad = Math.max(16, (int) Math.round(Math.min(w, h) * 0.03));
		int gap = Math.max(6, (int) Math.round(pad * 0.35));

		BroadcastTheme.Palette pal = BroadcastTheme.palette(accentFor(Screen.NEXT_TRACK));
		Color bg = pal.bg0();
		Color panel = pal.panelTint(0.05);
		Color panel2 = pal.panelTint(0.10);
		Color fg = pal.text();
		Color muted = pal.textDim();
		Color accent = pal.accent();
		Color borderSoft = pal.accentSoft(110);

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));
		Font header = title.deriveFont(Font.BOLD, Math.max(26f, title.getSize2D() * 1.55f));
		Font meta = body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D() * 1.0f));
		Font section = title.deriveFont(Font.BOLD, Math.max(26f, title.getSize2D() * 1.55f));
		Font heroLabel = body.deriveFont(Font.BOLD, Math.max(13f, body.getSize2D() * 0.95f));
		Font heroTitle = title.deriveFont(Font.BOLD, Math.max(42f, title.getSize2D() * 2.35f));
		Font heroWinner = title.deriveFont(Font.BOLD, Math.max(54f, title.getSize2D() * 3.10f));
		Font heroMeta = body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D() * 1.0f));
		Font heroYear = title.deriveFont(Font.BOLD, Math.max(96f, title.getSize2D() * 5.6f));
		Font big = title.deriveFont(Font.BOLD, Math.max(44f, title.getSize2D() * 2.55f));
		Font huge = title.deriveFont(Font.BOLD, Math.max(60f, title.getSize2D() * 3.4f));

		ColumnContainer root = new ColumnContainer().gap(gap).alignItems(UiAlign.STRETCH);
		root.style().background(bg).padding(UiInsets.all(pad));
		root.add(buildTopStripe(accent, pad));
		root.add(buildHeaderBar(e, w, h, pad, gap, panel, fg, muted, header, meta, true));

		ColumnContainer bodyCol = new ColumnContainer().gap(Math.max(12, gap)).alignItems(UiAlign.STRETCH).justifyContent(UiJustify.START);
		bodyCol.style().flexGrow(1);
		bodyCol.style().background(panel2).padding(UiInsets.all(Math.max(14, pad / 2))).border(borderSoft, 2);

		// Centered hero block
		ColumnContainer hero = new ColumnContainer().gap(Math.max(8, gap)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		hero.style().flexGrow(1);

		String track = (e != null ? e.currentTrackName() : null);
		if (track == null || track.isBlank())
			track = "-";
		TextElement nextChip = text("CHẶNG TIẾP THEO", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), bg, TextElement.Align.CENTER);
		nextChip.style().padding(UiInsets.symmetric(Math.max(4, pad / 6), Math.max(12, pad / 2))).background(accent);
		hero.add(nextChip);
		hero.add(text(track.toUpperCase(java.util.Locale.ROOT), big, fg, TextElement.Align.CENTER));

		long sec = secondsUntilNextPhase(e);
		hero.add(text("BẮT ĐẦU SAU", section.deriveFont(Font.BOLD, Math.max(20f, section.getSize2D() * 0.80f)), muted, TextElement.Align.CENTER));
		hero.add(text(formatHms(sec), huge, fg, TextElement.Align.CENTER));
		bodyCol.add(hero);

		UiElement trackStrip = buildTrackProgressStrip(
				e,
				body.deriveFont(Font.BOLD, Math.max(16f, body.getSize2D() * 1.10f)),
				body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D() * 1.0f)),
				fg,
				bg,
				Math.max(10, pad / 2),
				Math.max(10, gap)
		);
		if (trackStrip != null)
			bodyCol.add(trackStrip);

		// Ranking preview (bottom)
		List<EventRankEntry> ranking = buildRanking(e);
		ColumnContainer rankBox = new ColumnContainer().gap(Math.max(4, gap / 2)).alignItems(UiAlign.CENTER);
		rankBox.add(text("BẢNG ĐIỂM (TẠM THỜI)", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), muted, TextElement.Align.CENTER));
		int maxRows = 6;
		ColumnContainer list = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.CENTER);
		int shown = 0;
		for (EventRankEntry it : ranking) {
			if (shown >= maxRows)
				break;
			String line = String.format(java.util.Locale.ROOT, "#%d  %s  ●  %d điểm", it.position, it.name, it.points);
			list.add(text(line, body, muted, TextElement.Align.CENTER));
			shown++;
		}
		rankBox.add(list);
		bodyCol.add(rankBox);

		root.add(bodyCol);
		root.add(buildFooterClock(panel, muted, body, pad, gap));
		return root;
	}

	private UiElement buildCurrentRaceUi(RaceEvent e, int w, int h) {
		int pad = Math.max(16, (int) Math.round(Math.min(w, h) * 0.03));
		int gap = Math.max(6, (int) Math.round(pad * 0.35));

		BroadcastTheme.Palette pal = BroadcastTheme.palette(accentFor(Screen.CURRENT_RACE));
		Color bg = pal.bg0();
		Color panel = pal.panelTint(0.05);
		Color panel2 = pal.panelTint(0.10);
		Color fg = pal.text();
		Color muted = pal.textDim();
		Color accent = pal.accent();
		Color borderSoft = pal.accentSoft(110);

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));
		Font header = title.deriveFont(Font.BOLD, Math.max(26f, title.getSize2D() * 1.55f));
		Font meta = body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D() * 1.0f));
		Font section = title.deriveFont(Font.BOLD, Math.max(26f, title.getSize2D() * 1.55f));
		Font big = title.deriveFont(Font.BOLD, Math.max(44f, title.getSize2D() * 2.55f));
		Font huge = title.deriveFont(Font.BOLD, Math.max(62f, title.getSize2D() * 3.5f));

		ColumnContainer root = new ColumnContainer().gap(gap).alignItems(UiAlign.STRETCH);
		root.style().background(bg).padding(UiInsets.all(pad));
		root.add(buildTopStripe(accent, pad));
		root.add(buildHeaderBar(e, w, h, pad, gap, panel, fg, muted, header, meta, true));

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

		ColumnContainer bodyCol = new ColumnContainer().gap(Math.max(12, gap)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		bodyCol.style().flexGrow(1);
		bodyCol.style().background(panel2).padding(UiInsets.all(Math.max(14, pad / 2))).border(borderSoft, 2);

		TextElement runningChip = text("ĐANG THI ĐẤU", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), bg, TextElement.Align.CENTER);
		runningChip.style().padding(UiInsets.symmetric(Math.max(4, pad / 6), Math.max(12, pad / 2))).background(accent);
		bodyCol.add(runningChip);
		bodyCol.add(text(track.toUpperCase(java.util.Locale.ROOT), big, fg, TextElement.Align.CENTER));
		bodyCol.add(text("THỜI GIAN", section.deriveFont(Font.BOLD, Math.max(20f, section.getSize2D() * 0.80f)), muted, TextElement.Align.CENTER));
		bodyCol.add(text(timer, huge, fg, TextElement.Align.CENTER));
		root.add(bodyCol);
		root.add(buildFooterClock(panel, muted, body, pad, gap));
		return root;
	}

	private UiElement buildEventFinishedUi(RaceEvent e, int w, int h) {
		double scaleD = Math.min(w / 860.0, h / 470.0);
		boolean compact = scaleD < 0.92 || h <= 400;
		boolean ultraCompact = scaleD < 0.78 || h <= 320;
		if (scaleD < (ultraCompact ? 0.62 : 0.70))
			scaleD = (ultraCompact ? 0.62 : 0.70);
		if (scaleD > 1.0)
			scaleD = 1.0;
		float scale = (float) scaleD;

		int pad = Math.max(ultraCompact ? 10 : (compact ? 12 : 16), (int) Math.round(Math.min(w, h) * (ultraCompact ? 0.018 : (compact ? 0.022 : 0.03))));
		int gap = Math.max(ultraCompact ? 3 : (compact ? 4 : 6), (int) Math.round(pad * (ultraCompact ? 0.20 : (compact ? 0.24 : 0.35))));

		BroadcastTheme.Palette pal = BroadcastTheme.palette(accentFor(Screen.EVENT_FINISHED));
		Color bg = pal.bg0();
		Color panel = pal.panelTint(0.05);
		Color panel2 = pal.panelTint(0.10);
		Color fg = pal.text();
		Color muted = pal.textDim();
		Color accent = pal.accent();
		Color borderSoft = pal.accentSoft(110);

		Font title = (titleFont != null ? titleFont : new Font("Monospaced", Font.BOLD, 18));
		Font body = (bodyFont != null ? bodyFont : new Font("Monospaced", Font.PLAIN, 14));
		Font header = title.deriveFont(Font.BOLD, Math.max(24f, title.getSize2D() * (1.45f * scale)));
		Font meta = body.deriveFont(Font.BOLD, Math.max(12f, body.getSize2D() * (1.0f * scale)));
		Font section = title.deriveFont(Font.BOLD, Math.max(22f, title.getSize2D() * (1.40f * scale)));
		Font heroLabel = body.deriveFont(Font.BOLD, Math.max(11f, body.getSize2D() * (0.92f * scale)));
		Font heroTitle = title.deriveFont(Font.BOLD, Math.max(24f, title.getSize2D() * (1.45f * scale)));
		Font heroWinner = title.deriveFont(Font.BOLD, Math.max(30f, title.getSize2D() * (1.85f * scale)));
		Font heroMeta = body.deriveFont(Font.BOLD, Math.max(12f, body.getSize2D() * (1.0f * scale)));
		Font heroYear = title.deriveFont(Font.BOLD, Math.max(44f, title.getSize2D() * (3.00f * scale)));

		ColumnContainer root = new ColumnContainer().gap(gap).alignItems(UiAlign.STRETCH);
		root.style().background(bg).padding(UiInsets.all(compact ? Math.max(8, pad - 2) : pad));
		root.add(buildTopStripe(accent, pad));
		root.add(buildHeaderBar(e, w, h, pad, gap, panel, fg, muted, header, meta, true));

		List<EventRankEntry> ranking = buildRanking(e);

		ColumnContainer bodyCol = new ColumnContainer().gap(Math.max(compact ? 8 : 12, gap)).alignItems(UiAlign.STRETCH)
				.justifyContent((ranking.size() <= 3) ? UiJustify.CENTER : UiJustify.START);
		bodyCol.style().flexGrow(1);
		bodyCol.style().background(panel2).padding(UiInsets.all(Math.max(compact ? 8 : 14, pad / 2))).border(borderSoft, 2);

		EventRankEntry first = ranking.size() > 0 ? ranking.get(0) : null;
		EventRankEntry second = ranking.size() > 1 ? ranking.get(1) : null;
		EventRankEntry third = ranking.size() > 2 ? ranking.get(2) : null;

		// Standard podium colors: gold/silver/bronze
		Color gold = new Color(0xD4, 0xAF, 0x37);
		Color silver = new Color(0xC0, 0xC0, 0xC0);
		Color bronze = new Color(0xCD, 0x7F, 0x32);

		// Hero section (broadcast-like, typography-first)
		RowContainer hero = new RowContainer().gap(Math.max(ultraCompact ? 8 : (compact ? 10 : 14), gap)).alignItems(UiAlign.STRETCH).justifyContent(UiJustify.START);
		hero.style().background(panel).padding(UiInsets.all(Math.max(ultraCompact ? 8 : (compact ? 9 : 10), pad / 2))).border(borderSoft, 2);

		ColumnContainer heroLeft = new ColumnContainer().gap(Math.max(4, gap / 3)).alignItems(UiAlign.START).justifyContent(UiJustify.START);
		heroLeft.style().widthPx(0).flexGrow(2);

		TextElement heroChip = text("KẾT QUẢ CHUNG CUỘC", heroLabel, bg, TextElement.Align.LEFT);
		heroChip.style().padding(UiInsets.symmetric(Math.max(3, pad / 7), Math.max(12, pad / 2))).background(gold);
		heroLeft.add(heroChip);

		String eventTitle = (e != null && e.title != null && !e.title.isBlank()) ? e.title.trim() : "SỰ KIỆN";
		heroLeft.add(text(eventTitle.toUpperCase(java.util.Locale.ROOT), heroTitle, fg, TextElement.Align.LEFT));

		String winnerName = first != null ? first.name : "—";
		heroLeft.add(text(winnerName.toUpperCase(java.util.Locale.ROOT), heroWinner, fg, TextElement.Align.LEFT));
		String winnerMeta = first != null
				? String.format(java.util.Locale.ROOT, "VÔ ĐỊCH  ●  %d điểm", first.points)
				: "CHƯA CÓ KẾT QUẢ";
		heroLeft.add(text(winnerMeta, heroMeta, new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 255), TextElement.Align.LEFT));
		hero.add(heroLeft);

		ColumnContainer heroRight = new ColumnContainer().gap(Math.max(3, gap / 3)).alignItems(UiAlign.END).justifyContent(UiJustify.START);
		heroRight.style().widthPx(0).flexGrow(1);

		int year = java.time.LocalDate.now().getYear();
		try {
			if (e != null && e.startTimeMillis > 0L) {
				year = java.time.Instant.ofEpochMilli(e.startTimeMillis).atZone(java.time.ZoneId.systemDefault()).getYear();
			}
		} catch (Throwable ignored) {
		}

		if (iconFont != null && !compact && !ultraCompact) {
			TextElement mark = new TextElement("\uf091")
					.font(iconFont.deriveFont(Font.PLAIN, Math.max(46f, title.getSize2D() * (2.60f * scale))))
					.color(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 40))
					.align(TextElement.Align.RIGHT)
					.ellipsis(false);
			mark.style().widthPx(Math.max(100, (int) Math.round(w * 0.22)));
			heroRight.add(mark);
		}

		if (!ultraCompact) {
			TextElement yearEl = text(String.valueOf(year), heroYear, new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 70), TextElement.Align.RIGHT);
			yearEl.ellipsis(false);
			yearEl.style().widthPx(Math.max(150, (int) Math.round(w * 0.30)));
			heroRight.add(yearEl);
			TextElement stamp = text("BẢNG XẾP HẠNG CUỐI", heroMeta, muted, TextElement.Align.RIGHT);
			stamp.style().widthPx(Math.max(150, (int) Math.round(w * 0.30)));
			heroRight.add(stamp);
			hero.add(heroRight);
		}
		bodyCol.add(hero);

		// Podium section (visual)
		int podiumH = (int) Math.round(h * (ultraCompact ? 0.11 : (compact ? 0.13 : 0.22)));
		podiumH = Math.max(ultraCompact ? 76 : (compact ? 88 : 110), podiumH);
		podiumH = Math.min(podiumH, Math.max(ultraCompact ? 80 : (compact ? 92 : 116), (int) Math.round(h * (ultraCompact ? 0.14 : (compact ? 0.18 : 0.28)))));

		Font podiumTitle = section.deriveFont(Font.BOLD, Math.max(18f, section.getSize2D() * (0.78f * scale)));
		Font cardBadge = body.deriveFont(Font.BOLD, Math.max(13f, body.getSize2D() * 0.95f));
		Font cardName = title.deriveFont(Font.BOLD, Math.max(ultraCompact ? 16f : (compact ? 18f : 22f), title.getSize2D() * ((ultraCompact ? 0.95f : (compact ? 1.05f : 1.25f)) * scale)));
		Font cardPoints = body.deriveFont(Font.BOLD, Math.max(12f, body.getSize2D() * (1.0f * scale)));

		ColumnContainer podium = new ColumnContainer().gap(Math.max(4, gap / 3)).alignItems(UiAlign.STRETCH);

		ColumnContainer podiumHeader = new ColumnContainer().gap(Math.max(2, gap / 4)).alignItems(UiAlign.CENTER);
		podiumHeader.add(text("TOP 3", podiumTitle, fg, TextElement.Align.CENTER));
		podium.add(podiumHeader);

		// Winner cards (aligned with the podium widths: 2nd | 1st | 3rd)
		RowContainer cards = new RowContainer().gap(Math.max(ultraCompact ? 6 : 10, gap)).alignItems(UiAlign.STRETCH).justifyContent(UiJustify.CENTER);
		cards.style().padding(UiInsets.all(0));

		Color place1 = BroadcastTheme.mix(panel2, gold, 0.80);
		Color place2 = BroadcastTheme.mix(panel2, silver, 0.78);
		Color place3 = BroadcastTheme.mix(panel2, bronze, 0.78);
		Color cardBg = panel;
		Color cardBorder = borderSoft;

		java.util.function.BiFunction<Color, Integer, Color> a = (c, al) -> {
			if (c == null)
				return null;
			int aa = Math.max(0, Math.min(255, al == null ? 255 : al));
			return new Color(c.getRed(), c.getGreen(), c.getBlue(), aa);
		};

		ColumnContainer c2 = new ColumnContainer().gap(Math.max(2, gap / 4)).alignItems(UiAlign.CENTER);
		c2.style().widthPx(0).flexGrow(1).background(cardBg).padding(UiInsets.all(Math.max(ultraCompact ? 6 : 10, pad / 3))).border(cardBorder, 2);
		TextElement c2Badge = text("HẠNG 2", cardBadge, bg, TextElement.Align.CENTER);
		c2Badge.style().padding(UiInsets.symmetric(Math.max(3, pad / 7), Math.max(10, pad / 3))).background(place2);
		c2.add(c2Badge);
		c2.add(text(second != null ? second.name : "—", cardName, fg, TextElement.Align.CENTER));
		c2.add(text(second != null ? String.format(java.util.Locale.ROOT, "%d điểm", second.points) : "", cardPoints, muted, TextElement.Align.CENTER));
		cards.add(c2);

		ColumnContainer c1 = new ColumnContainer().gap(Math.max(2, gap / 4)).alignItems(UiAlign.CENTER);
		c1.style().widthPx(0).flexGrow(2).background(cardBg).padding(UiInsets.all(Math.max(ultraCompact ? 6 : 10, pad / 3))).border(a.apply(accent, 180), 2);
		TextElement c1Badge = text("VÔ ĐỊCH", cardBadge, bg, TextElement.Align.CENTER);
		c1Badge.style().padding(UiInsets.symmetric(Math.max(3, pad / 7), Math.max(10, pad / 3))).background(place1);
		c1.add(c1Badge);
		c1.add(text(first != null ? first.name : "—", cardName.deriveFont(Font.BOLD, Math.max(ultraCompact ? 18f : 26f, cardName.getSize2D() * 1.05f)), fg, TextElement.Align.CENTER));
		c1.add(text(first != null ? String.format(java.util.Locale.ROOT, "%d điểm", first.points) : "", cardPoints, muted, TextElement.Align.CENTER));
		cards.add(c1);

		ColumnContainer c3 = new ColumnContainer().gap(Math.max(2, gap / 4)).alignItems(UiAlign.CENTER);
		c3.style().widthPx(0).flexGrow(1).background(cardBg).padding(UiInsets.all(Math.max(ultraCompact ? 6 : 10, pad / 3))).border(cardBorder, 2);
		TextElement c3Badge = text("HẠNG 3", cardBadge, bg, TextElement.Align.CENTER);
		c3Badge.style().padding(UiInsets.symmetric(Math.max(3, pad / 7), Math.max(10, pad / 3))).background(place3);
		c3.add(c3Badge);
		c3.add(text(third != null ? third.name : "—", cardName, fg, TextElement.Align.CENTER));
		c3.add(text(third != null ? String.format(java.util.Locale.ROOT, "%d điểm", third.points) : "", cardPoints, muted, TextElement.Align.CENTER));
		cards.add(c3);

		podium.add(cards);

		GraphicsElement podiumGraphic = new GraphicsElement((ctx, rect) -> {
			if (ctx == null || ctx.g == null)
				return;
			ctx.applyDefaultHints();
			java.awt.Graphics2D g2 = ctx.g;
			int x = rect.x();
			int y = rect.y();
			int ww = rect.w();
			int hh = rect.h();
			if (ww <= 0 || hh <= 0)
				return;

			int inner = Math.max(10, (int) Math.round(ww * 0.035));
			int floorH = 0;
			int baseX = x + inner;
			int baseW = Math.max(1, ww - inner * 2);
			int baseY = y + hh - floorH;
			int baseH = floorH;

			// Podium steps (weights: 1 | 2 | 1)
			int wUnit = Math.max(1, baseW / 4);
			int w2 = wUnit;
			int w1 = Math.max(1, baseW - wUnit * 2);
			int w3 = wUnit;
			int x2 = baseX;
			int x1 = baseX + w2;
			int x3 = baseX + w2 + w1;

			int stepBottom = y + hh;
			// Make steps taller to avoid a big empty band above the podium.
			int h1 = Math.max(18, (int) Math.round(hh * 0.82));
			int h2 = Math.max(16, (int) Math.round(hh * 0.66));
			int h3 = Math.max(14, (int) Math.round(hh * 0.58));
			h1 = Math.min(h1, Math.max(18, stepBottom - y - 6));
			h2 = Math.min(h2, Math.max(18, stepBottom - y - 6));
			h3 = Math.min(h3, Math.max(18, stepBottom - y - 6));

			int arc = Math.max(10, (int) Math.round(Math.min(baseW, hh) * 0.05));
			Color step1 = place1;
			Color step2 = place2;
			Color step3 = place3;
			Color outline = a.apply(fg, 34);
			Color shine = a.apply(fg, 55);

			// Left (2)
			int y2 = stepBottom - h2;
			g2.setColor(step2);
			g2.fillRoundRect(x2 + 2, y2, Math.max(0, w2 - 4), h2, arc, arc);
			if (outline != null) {
				g2.setColor(outline);
				g2.drawRoundRect(x2 + 2, y2, Math.max(0, w2 - 4), h2, arc, arc);
			}
			if (shine != null) {
				g2.setColor(shine);
				g2.drawLine(x2 + 6, y2 + 6, x2 + w2 - 10, y2 + 6);
			}

			// Center (1)
			int y1 = stepBottom - h1;
			g2.setColor(step1);
			g2.fillRoundRect(x1 + 2, y1, Math.max(0, w1 - 4), h1, arc, arc);
			if (outline != null) {
				g2.setColor(outline);
				g2.drawRoundRect(x1 + 2, y1, Math.max(0, w1 - 4), h1, arc, arc);
			}
			if (shine != null) {
				g2.setColor(shine);
				g2.drawLine(x1 + 10, y1 + 8, x1 + w1 - 14, y1 + 8);
			}

			// Right (3)
			int y3 = stepBottom - h3;
			g2.setColor(step3);
			g2.fillRoundRect(x3 + 2, y3, Math.max(0, w3 - 4), h3, arc, arc);
			if (outline != null) {
				g2.setColor(outline);
				g2.drawRoundRect(x3 + 2, y3, Math.max(0, w3 - 4), h3, arc, arc);
			}
			if (shine != null) {
				g2.setColor(shine);
				g2.drawLine(x3 + 6, y3 + 6, x3 + w3 - 10, y3 + 6);
			}

			// Front numbers
			try {
				Font numFont = title.deriveFont(Font.BOLD, Math.max(34f, title.getSize2D() * 2.0f));
				g2.setFont(numFont);
				java.awt.FontMetrics fm = g2.getFontMetrics();
				Color numColor = a.apply(bg, 160);
				if (numColor == null)
					numColor = a.apply(bg, 200);
				g2.setColor(numColor);
				String s1 = "1";
				int sw1 = fm.stringWidth(s1);
				g2.drawString(s1, x1 + (w1 - sw1) / 2, y1 + (int) Math.round(h1 * 0.70));
				String s2 = "2";
				int sw2 = fm.stringWidth(s2);
				g2.drawString(s2, x2 + (w2 - sw2) / 2, y2 + (int) Math.round(h2 * 0.70));
				String s3 = "3";
				int sw3 = fm.stringWidth(s3);
				g2.drawString(s3, x3 + (w3 - sw3) / 2, y3 + (int) Math.round(h3 * 0.70));
			} catch (Throwable ignored) {
			}
		});
		if (!ultraCompact) {
			podiumGraphic.style().heightPx(podiumH).border(borderSoft, 2);
			podium.add(podiumGraphic);
		}

		bodyCol.add(podium);

		boolean showExtras = !compact && h >= 1600;

		// Thank-you message (only show when there is plenty of vertical space)
		if (showExtras) {
			RowContainer thanksRow = new RowContainer().alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
			TextElement thanks = text("Cảm ơn bạn đã tham gia sự kiện", body.deriveFont(Font.BOLD, Math.max(15f, body.getSize2D() * 1.05f)), bg, TextElement.Align.CENTER);
			thanks.style().padding(UiInsets.symmetric(Math.max(5, pad / 4), Math.max(14, pad / 2))).background(accent);
			thanksRow.add(thanks);
			bodyCol.add(thanksRow);
		}

		// Only show the final ranking section if it adds new information beyond TOP 3.
		int restStart = Math.min(3, ranking.size());
		if (showExtras && restStart < ranking.size()) {
			Spacer grow = new Spacer();
			grow.style().flexGrow(1);
			bodyCol.add(grow);

			ColumnContainer full = new ColumnContainer().gap(Math.max(4, gap / 2)).alignItems(UiAlign.CENTER);
			full.add(text("XẾP HẠNG CUỐI", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), muted, TextElement.Align.CENTER));
			int maxRows = Math.max(8, (int) Math.round((double) h / (double) Math.max(1, body.getSize()) / 2.1));
			ColumnContainer list = new ColumnContainer().gap(Math.max(2, gap / 3)).alignItems(UiAlign.CENTER);
			int shown = 0;
			for (int i = restStart; i < ranking.size(); i++) {
				if (shown >= maxRows)
					break;
				EventRankEntry it = ranking.get(i);
				String line = String.format(java.util.Locale.ROOT, "#%d  %s  ●  %d điểm", it.position, it.name, it.points);
				list.add(text(line, body, muted, TextElement.Align.CENTER));
				shown++;
			}
			full.add(list);
			bodyCol.add(full);
		}

		root.add(bodyCol);
		root.add(buildFooterClock(panel, muted, body, pad, gap));
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
			case DISABLED -> "Đã tắt";
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
