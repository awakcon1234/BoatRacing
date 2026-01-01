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
		leftHead.add(text("NGƯỜI THAM GIA", section, fg, TextElement.Align.LEFT));
		Spacer leftPush = new Spacer();
		leftPush.style().flexGrow(1);
		leftHead.add(leftPush);
		leftHead.add(text(racers + " TAY ĐUA", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), muted, TextElement.Align.RIGHT));
		left.add(leftHead);
		left.add(buildParticipantsRows(e, colMax, body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), fg));
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

		right.add(text("Hãy chuẩn bị thuyền và sẵn sàng xuất phát!", body.deriveFont(Font.PLAIN, Math.max(14f, body.getSize2D())), muted, TextElement.Align.CENTER));
		main.add(right);

		root.add(main);

		// Footer (bottom-right time)
		root.add(buildFooterClock(panel, muted, body, pad, gap));

		return root;
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

		int est = Math.max(140, font.getSize() * 11);
		int perRow = Math.max(4, Math.min(8, maxWidth / est));
		int maxShown = Math.max(1, perRow * 2);

		java.util.List<String> displays = new java.util.ArrayList<>();
		for (int i = 0; i < list.size() && i < maxShown; i++) {
			var it = list.get(i);
			displays.add(formatRacerBoard(it.getKey(), it.getValue()));
		}

		ColumnContainer rows = new ColumnContainer().gap(Math.max(4, font.getSize() / 2)).alignItems(UiAlign.CENTER);
		if (displays.isEmpty()) {
			rows.add(text("(Chưa có)", font, color, TextElement.Align.CENTER));
			return rows;
		}

		int idx = 0;
		for (int r = 0; r < 2 && idx < displays.size(); r++) {
			RowContainer row = new RowContainer().gap(Math.max(10, font.getSize() / 2)).alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
			int count = 0;
			while (idx < displays.size() && count < perRow) {
				if (count > 0) {
					row.add(text("●", font, color, TextElement.Align.CENTER));
				}
				LegacyTextElement racer = new LegacyTextElement(displays.get(idx))
						.font(font)
						.defaultColor(color)
						.align(LegacyTextElement.Align.LEFT)
						.trimToFit(false);
				row.add(racer);
				idx++;
				count++;
			}
			rows.add(row);
		}
		if (list.size() > maxShown) {
			rows.add(text("…", font, color, TextElement.Align.CENTER));
		}
		return rows;
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
		int pad = Math.max(16, (int) Math.round(Math.min(w, h) * 0.03));
		int gap = Math.max(6, (int) Math.round(pad * 0.35));

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
		Font header = title.deriveFont(Font.BOLD, Math.max(26f, title.getSize2D() * 1.55f));
		Font meta = body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D() * 1.0f));
		Font section = title.deriveFont(Font.BOLD, Math.max(26f, title.getSize2D() * 1.55f));

		ColumnContainer root = new ColumnContainer().gap(gap).alignItems(UiAlign.STRETCH);
		root.style().background(bg).padding(UiInsets.all(pad));
		root.add(buildTopStripe(accent, pad));
		root.add(buildHeaderBar(e, w, h, pad, gap, panel, fg, muted, header, meta, true));

		List<EventRankEntry> ranking = buildRanking(e);

		ColumnContainer bodyCol = new ColumnContainer().gap(Math.max(12, gap)).alignItems(UiAlign.STRETCH)
				.justifyContent((ranking.size() <= 3) ? UiJustify.CENTER : UiJustify.START);
		bodyCol.style().flexGrow(1);
		bodyCol.style().background(panel2).padding(UiInsets.all(Math.max(14, pad / 2))).border(borderSoft, 2);

		ColumnContainer top3 = new ColumnContainer().gap(Math.max(6, gap / 2)).alignItems(UiAlign.CENTER);
		TextElement resultChip = text("KẾT QUẢ", body.deriveFont(Font.BOLD, Math.max(14f, body.getSize2D())), bg, TextElement.Align.CENTER);
		resultChip.style().padding(UiInsets.symmetric(Math.max(4, pad / 6), Math.max(12, pad / 2))).background(accent);
		top3.add(resultChip);
		top3.add(text("TOP 3", section.deriveFont(Font.BOLD, Math.max(22f, section.getSize2D() * 0.85f)), fg, TextElement.Align.CENTER));
		for (int i = 0; i < Math.min(3, ranking.size()); i++) {
			EventRankEntry it = ranking.get(i);
			String line = String.format(java.util.Locale.ROOT, "#%d  %s  ●  %d điểm", it.position, it.name, it.points);
			top3.add(text(line, body.deriveFont(Font.BOLD, Math.max(16f, body.getSize2D() * 1.05f)), fg, TextElement.Align.CENTER));
		}
		bodyCol.add(top3);

		// Thank-you message
		RowContainer thanksRow = new RowContainer().alignItems(UiAlign.CENTER).justifyContent(UiJustify.CENTER);
		TextElement thanks = text("Cảm ơn bạn đã tham gia sự kiện", body.deriveFont(Font.BOLD, Math.max(15f, body.getSize2D() * 1.05f)), bg, TextElement.Align.CENTER);
		thanks.style().padding(UiInsets.symmetric(Math.max(5, pad / 4), Math.max(14, pad / 2))).background(accent);
		thanksRow.add(thanks);
		bodyCol.add(thanksRow);

		// Only show the final ranking section if it adds new information beyond TOP 3.
		int restStart = Math.min(3, ranking.size());
		if (restStart < ranking.size()) {
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
