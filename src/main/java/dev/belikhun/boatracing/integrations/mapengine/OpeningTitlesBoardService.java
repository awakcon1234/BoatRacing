package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardFontLoader;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardPlacement;
import dev.belikhun.boatracing.integrations.mapengine.board.MapEngineBoardDisplay;
import dev.belikhun.boatracing.integrations.mapengine.board.RenderBuffers;
import dev.belikhun.boatracing.integrations.mapengine.ui.BroadcastTheme;
import dev.belikhun.boatracing.integrations.mapengine.ui.ColumnContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.FxContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.GraphicsElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.ImageElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.LayerContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.LegacyTextElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.RowContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiAlign;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiComposer;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiInsets;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiJustify;
import dev.belikhun.boatracing.integrations.mapengine.ui.UiElement;
import dev.belikhun.boatracing.profile.PlayerProfileManager;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.InputStream;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MapEngine board used during event opening titles.
 *
 * It is controlled by EventService (viewers + current screen).
 */
public final class OpeningTitlesBoardService {
	private enum Screen {
		FAVICON,
		RACER_CARD
	}

	private final BoatRacingPlugin plugin;

	private final MapEngineBoardDisplay boardDisplay = new MapEngineBoardDisplay();
	private final RenderBuffers renderBuffers = new RenderBuffers();

	// Reused tick buffers (avoid per-tick allocations)
	private final Set<UUID> eligibleViewers = new HashSet<>();

	// UI cache (avoid rebuilding the UI tree every tick)
	private UiElement cachedFaviconUi;
	private BufferedImage cachedFaviconImage;
	private int cachedFaviconW;
	private int cachedFaviconH;
	private BufferedImage bundledLogo;

	private static final long RACER_UI_CACHE_TTL_MS = 2000L;
	private static final int RACER_UI_CACHE_MAX = 48;
	private final java.util.LinkedHashMap<UUID, CachedRacerUi> racerUiCache = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<UUID, CachedRacerUi> eldest) {
			return size() > RACER_UI_CACHE_MAX;
		}
	};

	private long lastRenderedKey = Long.MIN_VALUE;
	private long lastRacerUiRefreshAtMs = 0L;
	private static final long RENDER_IDLE_THROTTLE_MS = 1000L;
	private long lastRenderAtMs = 0L;

	private BukkitTask tickTask;

	private BoardPlacement placement;
	private int updateTicks = 1;
	private boolean debug = false;
	private boolean mapBuffering = true;
	private boolean mapBundling = false;

	// Fonts
	private Font titleFont;
	private Font bodyFont;
	private Font fallbackFont;
	private Font iconFont;

	// Assets
	private BufferedImage faviconImage;
	private long faviconLastLoadMs;

	// Runtime state
	private volatile Screen screen = Screen.FAVICON;
	private volatile UUID racerId;
	private volatile String racerName;
	private volatile Set<UUID> desiredViewers = new HashSet<>();
	private volatile Set<UUID> previewViewers = new HashSet<>();

	// Transition state (show/hide + crossfade)
	private volatile boolean transitioning = false;
	private volatile long transitionStartMs = 0L;
	private volatile Screen fromScreen = Screen.FAVICON;
	private volatile UUID fromRacerId;
	private volatile String fromRacerName;
	private volatile Screen toScreen = Screen.FAVICON;
	private volatile UUID toRacerId;
	private volatile String toRacerName;
	private volatile long flashStartMs = 0L;
	private static final long HIDE_MS = 320L;
	private static final long SHOW_MS = 420L;
	private static final long TRANSITION_MS = HIDE_MS + SHOW_MS;
	private static final long FLASH_MS = 280L;

	private final Set<UUID> spawnedTo = new HashSet<>();

	private static final class CachedRacerUi {
		final UiElement ui;
		final long builtAtMs;
		final int w;
		final int h;
		final UUID id;
		final String name;

		CachedRacerUi(UiElement ui, long builtAtMs, int w, int h, UUID id, String name) {
			this.ui = ui;
			this.builtAtMs = builtAtMs;
			this.w = w;
			this.h = h;
			this.id = id;
			this.name = name;
		}
	}

	public OpeningTitlesBoardService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	public void reloadFromConfig() {
		stop();
		loadConfig();
	}

	private void loadConfig() {
		placement = BoardPlacement.load(plugin.getConfig().getConfigurationSection("mapengine.opening-titles.board"));
		updateTicks = clamp(plugin.getConfig().getInt("mapengine.opening-titles.update-ticks", 1), 1, 200);
		debug = plugin.getConfig().getBoolean("mapengine.opening-titles.debug", false);
		mapBuffering = plugin.getConfig().getBoolean("mapengine.opening-titles.pipeline.buffering", true);
		mapBundling = plugin.getConfig().getBoolean("mapengine.opening-titles.pipeline.bundling", false);

		String fontFile = plugin.getConfig().getString("mapengine.opening-titles.font-file", "");
		Font base = BoardFontLoader.tryLoadBoardFont(plugin, fontFile, debug ? (m) -> {
			try {
				plugin.getLogger().info("[OpeningTitlesBoard] " + m);
			} catch (Throwable ignored) {
			}
		} : null);

		int titleSize = clamp(plugin.getConfig().getInt("mapengine.opening-titles.font.title-size", 18), 10, 96);
		int bodySize = clamp(plugin.getConfig().getInt("mapengine.opening-titles.font.body-size", 14), 8, 72);

		if (base != null) {
			titleFont = base.deriveFont(Font.BOLD, (float) titleSize);
			bodyFont = base.deriveFont(Font.PLAIN, (float) bodySize);
		} else {
			titleFont = new Font("Monospaced", Font.BOLD, titleSize);
			bodyFont = new Font("Monospaced", Font.PLAIN, bodySize);
		}

		try {
			fallbackFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, bodySize));
		} catch (Throwable ignored) {
			fallbackFont = null;
		}

		// Optional icon font (Font Awesome)
		iconFont = null;
		try (InputStream is = plugin.getResource("fonts/fa-regular-400.ttf")) {
			if (is != null) {
				Font fa = Font.createFont(Font.TRUETYPE_FONT, is);
				iconFont = fa;
			}
		} catch (Throwable ignored) {
			iconFont = null;
		}

		faviconImage = null;
		faviconLastLoadMs = 0L;
		cachedFaviconUi = null;
		cachedFaviconImage = null;
		cachedFaviconW = 0;
		cachedFaviconH = 0;
		bundledLogo = null;
		racerUiCache.clear();
		lastRenderedKey = Long.MIN_VALUE;
		lastRacerUiRefreshAtMs = 0L;
		lastRenderAtMs = 0L;
	}

	public boolean start() {
		if (tickTask != null)
			return true;
		if (plugin == null)
			return false;
		if (!plugin.getConfig().getBoolean("mapengine.opening-titles.enabled", false))
			return false;
		if (placement == null || !placement.isValid())
			return false;

		MapEngineApi api = MapEngineService.get();
		if (api == null)
			return false;

		boardDisplay.ensure(api, placement, mapBuffering, mapBundling);
		if (!boardDisplay.isReady())
			return false;

		tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, updateTicks);
		return true;
	}

	/**
	 * Player-facing diagnostics for /boatracing event opening board status.
	 */
	public List<String> statusLines() {
		List<String> out = new ArrayList<>();

		boolean enabled = false;
		try {
			enabled = plugin.getConfig().getBoolean("mapengine.opening-titles.enabled", false);
		} catch (Throwable ignored) {
			enabled = false;
		}
		boolean apiOk = MapEngineService.isAvailable();

		out.add("&eBảng mở đầu (MapEngine):");
		out.add("&7● MapEngine: " + (apiOk ? "&a✔" : "&c❌") + "&7 (" + (apiOk ? "có" : "không") + ")");
		out.add("&7● Trạng thái: " + (enabled ? "&aBật" : "&cTắt"));
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
		out.add("&7● Debug log: " + (debug ? "&aBật" : "&cTắt") + " &8(&7mapengine.opening-titles.debug&8)");
		return out;
	}

	public boolean setPlacementFromSelection(Player p, BoundingBox box, BlockFace facing) {
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

		pl.save(plugin.getConfig().createSection("mapengine.opening-titles.board.placement"));
		plugin.getConfig().set("mapengine.opening-titles.enabled", true);
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
		plugin.getConfig().set("mapengine.opening-titles.enabled", false);
		plugin.getConfig().set("mapengine.opening-titles.board.placement", null);
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

	private static BlockFace autoFacingFromPlayer(Player p, BoundingBox box) {
		if (p == null || box == null)
			return null;
		org.bukkit.Location loc = p.getLocation();
		if (loc == null)
			return null;

		int minX = (int) Math.floor(Math.min(box.getMinX(), box.getMaxX()));
		int maxX = (int) Math.floor(Math.max(box.getMinX(), box.getMaxX()));
		int minZ = (int) Math.floor(Math.min(box.getMinZ(), box.getMaxZ()));
		int maxZ = (int) Math.floor(Math.max(box.getMinZ(), box.getMaxZ()));

		double px = loc.getX();
		double pz = loc.getZ();

		double dNorth = Math.abs(pz - minZ);
		double dSouth = Math.abs(pz - maxZ);
		double dWest = Math.abs(px - minX);
		double dEast = Math.abs(px - maxX);

		double best = dNorth;
		BlockFace face = BlockFace.NORTH;
		if (dSouth < best) {
			best = dSouth;
			face = BlockFace.SOUTH;
		}
		if (dWest < best) {
			best = dWest;
			face = BlockFace.WEST;
		}
		if (dEast < best) {
			face = BlockFace.EAST;
		}

		// If player stands inside the selection, fall back to yaw.
		if (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ) {
			float yaw = loc.getYaw();
			float y = (yaw % 360.0f + 360.0f) % 360.0f;
			if (y >= 315.0f || y < 45.0f)
				return BlockFace.SOUTH;
			if (y < 135.0f)
				return BlockFace.WEST;
			if (y < 225.0f)
				return BlockFace.NORTH;
			return BlockFace.EAST;
		}

		return face;
	}

	public void previewTo(Player p) {
		if (p == null || !p.isOnline())
			return;
		if (!plugin.getConfig().getBoolean("mapengine.opening-titles.enabled", false))
			return;
		if (placement == null || !placement.isValid())
			return;

		// Animated preview: add as preview viewer and ensure tick loop is running.
		try {
			UUID id = p.getUniqueId();
			if (id != null) {
				Set<UUID> pv = new HashSet<>(previewViewers);
				pv.add(id);
				previewViewers = pv;
			}
		} catch (Throwable ignored) {
		}

		try {
			requestScreen(Screen.FAVICON, null, null);
			start();
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Debug helper: preview the racer card UI for a specific racer, only to the provided viewer.
	 * This does not permanently change the runtime screen state.
	 */
	public void previewRacerCardTo(Player viewer, UUID racerId, String racerName) {
		if (viewer == null || !viewer.isOnline())
			return;
		if (!plugin.getConfig().getBoolean("mapengine.opening-titles.enabled", false))
			return;
		if (placement == null || !placement.isValid())
			return;

		// Animated preview: add as preview viewer and ensure tick loop is running.
		try {
			UUID id = viewer.getUniqueId();
			if (id != null) {
				Set<UUID> pv = new HashSet<>(previewViewers);
				pv.add(id);
				previewViewers = pv;
			}
		} catch (Throwable ignored) {
		}

		try {
			requestScreen(Screen.RACER_CARD, racerId, racerName);
			start();
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Debug helper: remove this board from a player's view and (if not running) free resources.
	 */
	public void resetViewer(Player viewer) {
		if (viewer == null)
			return;
		UUID id = viewer.getUniqueId();
		if (id != null) {
			spawnedTo.remove(id);
			try {
				Set<UUID> pv = new HashSet<>(previewViewers);
				pv.remove(id);
				previewViewers = pv;
			} catch (Throwable ignored) {
			}
		}
		try {
			boardDisplay.removeViewer(viewer);
		} catch (Throwable ignored) {
		}
		if (tickTask == null && spawnedTo.isEmpty()) {
			try {
				boardDisplay.destroy();
			} catch (Throwable ignored) {
			}
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

		boardDisplay.destroy();
	}

	public void setViewers(Set<UUID> viewers) {
		this.desiredViewers = (viewers == null) ? new HashSet<>() : new HashSet<>(viewers);
	}

	public void showFavicon() {
		requestScreen(Screen.FAVICON, null, null);
	}

	public void showRacer(UUID racerId, String racerName) {
		requestScreen(Screen.RACER_CARD, racerId, racerName);
	}

	private void requestScreen(Screen target, UUID rid, String rname) {
		Screen cur = screen;
		UUID curId = racerId;
		String curName = racerName;

		if (target == cur) {
			if (target == Screen.FAVICON)
				return;
			// Racer screen: ignore redundant requests.
			if ((rid == null && curId == null) || (rid != null && rid.equals(curId))) {
				if ((rname == null && curName == null) || (rname != null && rname.equals(curName)))
					return;
			}
		}

		long now = System.currentTimeMillis();
		fromScreen = cur;
		fromRacerId = curId;
		fromRacerName = curName;
		toScreen = target;
		toRacerId = rid;
		toRacerName = rname;
		transitionStartMs = now;
		flashStartMs = now;
		transitioning = true;
	}

	private void tick() {
		if (plugin == null)
			return;
		if (!plugin.getConfig().getBoolean("mapengine.opening-titles.enabled", false))
			return;
		if (placement == null || !placement.isValid())
			return;
		if (!boardDisplay.isReady())
			return;

		eligibleViewers.clear();
		for (UUID id : desiredViewers) {
			if (id == null)
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			eligibleViewers.add(id);
		}
		for (UUID id : previewViewers) {
			if (id == null)
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			eligibleViewers.add(id);
		}

		if (eligibleViewers.isEmpty()) {
			if (tickTask != null && (desiredViewers == null || desiredViewers.isEmpty())
					&& (previewViewers == null || previewViewers.isEmpty())) {
				// No viewers at all; stop to free resources.
				stop();
			}
			return;
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

		for (UUID id : eligibleViewers) {
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

		if (spawnedTo.isEmpty())
			return;

		long now = System.currentTimeMillis();
		if (!shouldRenderNow(now))
			return;
		BufferedImage img = renderUi(placement.pixelWidth(), placement.pixelHeight(), now);
		boardDisplay.renderAndFlush(img);
		lastRenderAtMs = now;
	}

	private BufferedImage renderUi(int w, int h, long now) {
		BufferedImage img = renderBuffers.acquire(w, h);
		UiElement root = buildRoot(w, h, now);
		UiComposer.renderInto(img, root, bodyFont, fallbackFont);
		return img;
	}

	private UiElement buildRoot(int w, int h, long now) {
		BroadcastTheme.Palette pal = BroadcastTheme.palette(BroadcastTheme.ACCENT_READY);

		// Resolve transition progress.
		boolean isTrans = transitioning;
		double t = 1.0;
		if (isTrans) {
			long dt = Math.max(0L, now - transitionStartMs);
			t = (TRANSITION_MS <= 0L) ? 1.0 : Math.max(0.0, Math.min(1.0, (double) dt / (double) TRANSITION_MS));
			if (t >= 1.0) {
				// Commit.
				screen = toScreen;
				racerId = toRacerId;
				racerName = toRacerName;
				transitioning = false;
				isTrans = false;
				t = 1.0;
			}
		}

		int slide = (int) Math.round(w * 0.07);
		long showDtMs;
		double hideT;
		double showT;
		if (!isTrans) {
			hideT = 1.0;
			showT = 1.0;
			showDtMs = SHOW_MS;
		} else {
			long dt = Math.max(0L, now - transitionStartMs);
			hideT = (HIDE_MS <= 0L) ? 1.0 : Math.max(0.0, Math.min(1.0, (double) dt / (double) HIDE_MS));
			showDtMs = Math.max(0L, dt - HIDE_MS);
			showT = (SHOW_MS <= 0L) ? 1.0 : Math.max(0.0, Math.min(1.0, (double) showDtMs / (double) SHOW_MS));
		}

		double hideEase = easeInCubic(hideT);
		double showEase = easeOutCubic(showT);

		LayerContainer root = new LayerContainer();
		root.style().background(pal.panel());

		// Background: clean dark layout + signature emblem (inspired by provided references).
		Color c1 = null;
		Color c2 = null;
		Color c3 = null;
		try {
			c1 = BroadcastTheme.palette(BroadcastTheme.ACCENT_READY).accentSoft(230);
			c2 = BroadcastTheme.palette(BroadcastTheme.ACCENT_RUNNING).accentSoft(230);
			c3 = BroadcastTheme.palette(BroadcastTheme.ACCENT_REGISTERING).accentSoft(230);
		} catch (Throwable ignored) {
			c1 = null;
			c2 = null;
			c3 = null;
		}
		final Color emblemC1 = c1;
		final Color emblemC2 = c2;
		final Color emblemC3 = c3;

		root.add(new GraphicsElement((ctx, rect) -> {
			if (ctx == null || ctx.g == null)
				return;
			Graphics2D g = ctx.g;
			int marginX = Math.max(18, (int) Math.round(rect.w() * 0.12));
			int marginY = Math.max(18, (int) Math.round(rect.h() * 0.12));
			int safeLeft = rect.x() + marginX;
			int safeRight = rect.x() + rect.w() - marginX;
			int safeTop = rect.y() + marginY;
			int safeBottom = rect.y() + rect.h() - marginY;
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			} catch (Throwable ignored) {
			}

			// Subtle vignette + top glow
			try {
				Color panel = pal.panel();
				if (panel != null) {
					g.setColor(panel);
					g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
				}
				g.setPaint(new GradientPaint(
						rect.x(), rect.y(), new Color(255, 255, 255, 18),
						rect.x(), rect.y() + (int) Math.round(rect.h() * 0.35), new Color(255, 255, 255, 0)
				));
				g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
				g.setPaint(new GradientPaint(
						rect.x(), rect.y() + rect.h(), new Color(0, 0, 0, 120),
						rect.x(), rect.y() + (int) Math.round(rect.h() * 0.55), new Color(0, 0, 0, 0)
				));
				g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
			} catch (Throwable ignored) {
			}

			// Signature emblem on the right (3 arms) - keep inside right band
			try {
				Color ec1 = emblemC1;
				Color ec2 = emblemC2;
				Color ec3 = emblemC3;
				if (ec1 != null && ec2 != null && ec3 != null) {
					int size = (int) Math.round(Math.min(rect.w(), rect.h()) * 0.30);
					int armW = Math.max(18, size);
					int armH = Math.max(10, (int) Math.round(size * 0.24));
					int r = Math.max(8, armH);
					int cx = rect.x() + rect.w() - Math.max(18, marginX / 2);
					int cy = rect.y() + rect.h() / 2;

					// Clamp to right band only.
					int minCx = safeRight + Math.max(10, marginX / 3);
					cx = Math.max(cx, minCx);
					cx = Math.min(cx, rect.x() + rect.w() - 10);

					AffineTransform old = g.getTransform();
					Color[] colors = new Color[] { ec1, ec2, ec3 };
					double[] angles = new double[] { 0.0, 120.0, 240.0 };
					for (int i = 0; i < 3; i++) {
						g.setTransform(old);
						g.rotate(Math.toRadians(angles[i]), cx, cy);
						Color c = colors[i];
						int x0 = cx - armW / 2;
						int y0 = cy - armH / 2;
						g.setPaint(new GradientPaint(
								x0, y0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 235),
								x0 + armW, y0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 150)
						));
						g.fillRoundRect(x0, y0, armW, armH, r, r);
					}
					g.setTransform(old);
				}
			} catch (Throwable ignored) {
			}

			// Big year watermark (bottom-right)
			// Decorative icons (bottom-left band)
			try {
				if (iconFont != null) {
					int size = clamp((int) Math.round(Math.min(rect.w(), rect.h()) * 0.055), 10, 22);
					Font f = iconFont.deriveFont(Font.PLAIN, (float) size);
					g.setFont(f);
					Color c = pal.textDimSoft(90);
					if (c != null)
						g.setColor(c);

					String[] glyphs = new String[] {
						"\uf005", // star
						"\uf004", // heart
						"\uf111", // circle
						"\uf0c8", // square
						"\uf024", // flag
						"\uf017", // clock
						"\uf0f3", // bell
						"\uf06e"  // eye
					};

					int y = rect.y() + rect.h() - Math.max(10, marginY / 2);
					int x = rect.x() + Math.max(12, marginX / 3);
					int step = Math.max(12, size + 7);
					int maxX = safeLeft - 12;

					for (String s : glyphs) {
						if (s == null || s.isBlank())
							continue;
						int cp = s.codePointAt(0);
						if (!f.canDisplay(cp))
							continue;
						if (x > maxX)
							break;
						g.drawString(s, x, y);
						x += step;
					}
				}
			} catch (Throwable ignored) {
			}

			// Minimal outer frame
			try {
				Color bc = pal.border();
				if (bc != null) {
					int bw = Math.max(1, (int) Math.round(Math.min(rect.w(), rect.h()) * 0.004));
					g.setColor(new Color(bc.getRed(), bc.getGreen(), bc.getBlue(), 90));
					g.setStroke(new BasicStroke(bw));
					int half = bw / 2;
					g.drawRect(rect.x() + half, rect.y() + half, Math.max(0, rect.w() - bw), Math.max(0, rect.h() - bw));
				}
			} catch (Throwable ignored) {
			}

			// Flash streak when a new screen comes in (short + punchy).
			try {
				long fdt = Math.max(0L, now - flashStartMs);
				double ft = (FLASH_MS <= 0L) ? 1.0 : Math.max(0.0, Math.min(1.0, (double) fdt / (double) FLASH_MS));
				double fe = 1.0 - easeOutCubic(ft);
				int alpha = (int) Math.round(180.0 * fe);
				Color fc = pal.accentSoft(alpha);
				if (fc != null && alpha > 2) {
					g.setColor(fc);
					int bandW = (int) Math.round(rect.w() * 0.62);
					int x = rect.x() + (int) Math.round((rect.w() + bandW) * ft) - bandW;
					int y0 = rect.y() + (int) Math.round(rect.h() * 0.12);
					int y1 = rect.y() + (int) Math.round(rect.h() * 0.15);
					g.fillRect(x, y0, bandW, Math.max(1, y1 - y0));
				}
			} catch (Throwable ignored) {
			}
		}));

		UiElement curUi;
		if (!isTrans) {
			Screen s = screen;
			curUi = getStableRootFor(s, racerId, racerName, w, h, pal, now);
			root.add(new FxContainer().alpha(1.0).offset(0, 0).child(curUi));
			return root;
		}

		UiElement fromUi = buildRootFor(fromScreen, fromRacerId, fromRacerName, w, h, pal, SHOW_MS, false);
		UiElement toUi = buildRootFor(toScreen, toRacerId, toRacerName, w, h, pal, showDtMs, true);

		// Phase 1: hide outgoing only.
		double aOut = 1.0 - hideEase;
		root.add(new FxContainer().alpha(aOut).offset((int) Math.round(-slide * hideEase), 0).child(fromUi));

		// Phase 2: show incoming after hide completes.
		if (showT > 0.0) {
			double aIn = showEase;
			root.add(new FxContainer().alpha(aIn).offset((int) Math.round(slide * (1.0 - showEase)), 0).child(toUi));
		}
		return root;
	}

	private UiElement getStableRootFor(Screen s, UUID id, String name, int w, int h, BroadcastTheme.Palette pal, long now) {
		return switch (s) {
			case FAVICON -> getCachedFaviconUi(w, h, pal);
			case RACER_CARD -> getCachedRacerUi(w, h, pal, id, name, now);
		};
	}

	private UiElement getCachedFaviconUi(int w, int h, BroadcastTheme.Palette pal) {
		BufferedImage icon = loadFaviconIfNeeded();
		if (icon == null)
			icon = loadBundledLogoCached();
		if (cachedFaviconUi != null && cachedFaviconW == w && cachedFaviconH == h && cachedFaviconImage == icon)
			return cachedFaviconUi;
		cachedFaviconUi = buildFaviconUi(w, h, pal, icon);
		cachedFaviconW = w;
		cachedFaviconH = h;
		cachedFaviconImage = icon;
		return cachedFaviconUi;
	}

	private UiElement getCachedRacerUi(int w, int h, BroadcastTheme.Palette pal, UUID id, String name, long now) {
		if (id == null) {
			return buildRacerUi(w, h, pal, null, name, SHOW_MS, false);
		}
		CachedRacerUi cached = racerUiCache.get(id);
		if (cached != null) {
			boolean sameName = (cached.name == null && name == null) || (cached.name != null && cached.name.equals(name));
			if (cached.w == w && cached.h == h && sameName && (now - cached.builtAtMs) <= RACER_UI_CACHE_TTL_MS) {
				return cached.ui;
			}
		}

		UiElement ui = buildRacerUi(w, h, pal, id, name, SHOW_MS, false);
		racerUiCache.put(id, new CachedRacerUi(ui, now, w, h, id, name));
		return ui;
	}

	private boolean shouldRenderNow(long now) {
		// During transitions/flash, we need per-frame updates.
		if (transitioning)
			return true;
		if (flashStartMs > 0L && (now - flashStartMs) < FLASH_MS)
			return true;

		// If screen is racer card, refresh occasionally for profile stats (but not 20 TPS).
		if (screen == Screen.RACER_CARD) {
			if ((now - lastRacerUiRefreshAtMs) >= RACER_UI_CACHE_TTL_MS) {
				lastRacerUiRefreshAtMs = now;
				return true;
			}
		}

		// Favicon can change on disk; if icon reference changes, rerender.
		if (screen == Screen.FAVICON) {
			BufferedImage icon = loadFaviconIfNeeded();
			if (icon == null)
				icon = loadBundledLogoCached();
			if (cachedFaviconImage != icon)
				return true;
		}

		// Finally, avoid spamming render/flush if nothing changed.
		long key = computeRenderKey();
		if (key != lastRenderedKey) {
			lastRenderedKey = key;
			return true;
		}

		return (now - lastRenderAtMs) >= RENDER_IDLE_THROTTLE_MS;
	}

	private long computeRenderKey() {
		int w = (placement != null ? placement.pixelWidth() : 0);
		int h = (placement != null ? placement.pixelHeight() : 0);
		long k = 1469598103934665603L;
		k = (k ^ w) * 1099511628211L;
		k = (k ^ h) * 1099511628211L;
		k = (k ^ (screen != null ? screen.ordinal() : 0)) * 1099511628211L;
		UUID id = racerId;
		if (id != null) {
			k = (k ^ id.getMostSignificantBits()) * 1099511628211L;
			k = (k ^ id.getLeastSignificantBits()) * 1099511628211L;
		}
		String n = racerName;
		if (n != null)
			k = (k ^ n.hashCode()) * 1099511628211L;
		return k;
	}

	private UiElement buildRootFor(Screen s, UUID id, String name, int w, int h, BroadcastTheme.Palette pal, long showDtMs, boolean animateIn) {
		return switch (s) {
			case FAVICON -> buildFaviconUi(w, h, pal, null);
			case RACER_CARD -> buildRacerUi(w, h, pal, id, name, showDtMs, animateIn);
		};
	}

	private UiElement buildFaviconUi(int w, int h, BroadcastTheme.Palette pal, BufferedImage iconOverride) {
		BufferedImage icon = iconOverride;
		if (icon == null) {
			icon = loadFaviconIfNeeded();
			if (icon == null)
				icon = loadBundledLogoCached();
		}

		// Modern "broadcast" splash inspired by the references: big headline + clean blocks.
		ColumnContainer root = new ColumnContainer()
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.CENTER);
		root.style().padding(UiInsets.all(Math.max(10, (int) Math.round(Math.min(w, h) * 0.06))));

		int iconH = (int) Math.round(h * 0.22);
		if (icon != null) {
			ImageElement image = new ImageElement(icon)
					.fit(ImageElement.Fit.CONTAIN)
					.smoothing(true);
			image.style().heightPx(iconH);
			root.add(image);
		}

		Font big;
		Font mid;
		try {
			big = titleFont != null ? titleFont.deriveFont(Font.BOLD, (float) clamp((int) Math.round(Math.min(w, h) * 0.18), 18, 64)) : titleFont;
			mid = bodyFont != null ? bodyFont.deriveFont(Font.PLAIN, (float) clamp((int) Math.round(Math.min(w, h) * 0.065), 10, 28)) : bodyFont;
		} catch (Throwable ignored) {
			big = titleFont;
			mid = bodyFont;
		}

		LegacyTextElement headline = new LegacyTextElement("&f&lĐUA THUYỀN")
				.font(big)
				.align(LegacyTextElement.Align.CENTER)
				.trimToFit(true);
		root.add(headline);

		LegacyTextElement brand = new LegacyTextElement("&8BoatRacing")
				.font(mid)
				.defaultColor(pal.textDim())
				.align(LegacyTextElement.Align.CENTER)
				.trimToFit(true);
		root.add(brand);

		LegacyTextElement sub = new LegacyTextElement("&7MỞ ĐẦU SỰ KIỆN")
				.font(mid)
				.defaultColor(pal.textDim())
				.align(LegacyTextElement.Align.CENTER)
				.trimToFit(true);
		root.add(sub);

		return root;
	}

	private UiElement buildRacerUi(int w, int h, BroadcastTheme.Palette pal, UUID id, String name, long showDtMs, boolean animateIn) {

		String display;
		try {
			PlayerProfileManager pm = plugin.getProfileManager();
			if (pm != null) {
				String n;
				if (name != null && !name.isBlank()) {
					n = name;
				} else {
					Player p = (id != null ? Bukkit.getPlayer(id) : null);
					n = (p != null ? p.getName() : "(không rõ)");
				}
				display = pm.formatRacerLegacy(id, n);
			} else {
				display = "&f(không rõ)";
			}
		} catch (Throwable ignored) {
			display = "&f(không rõ)";
		}

		int padX = Math.max(14, (int) Math.round(w * 0.08));
		int padY = Math.max(12, (int) Math.round(h * 0.10));
		int gap = Math.max(6, (int) Math.round(Math.min(w, h) * 0.02));
		ColumnContainer root = new ColumnContainer()
				.alignItems(UiAlign.STRETCH)
				.justifyContent(UiJustify.START)
				.gap(Math.max(4, gap));
		root.style().padding(UiInsets.symmetric(padY, padX));

		Font label;
		Font big;
		Font micro;
		try {
			label = bodyFont != null ? bodyFont.deriveFont(Font.PLAIN, (float) clamp((int) Math.round(Math.min(w, h) * 0.05), 10, 26)) : bodyFont;
			big = titleFont != null ? titleFont.deriveFont(Font.BOLD, (float) clamp((int) Math.round(Math.min(w, h) * 0.13), 16, 56)) : titleFont;
			micro = bodyFont != null ? bodyFont.deriveFont(Font.PLAIN, (float) clamp((int) Math.round(Math.min(w, h) * 0.032), 8, 18)) : bodyFont;
		} catch (Throwable ignored) {
			label = bodyFont;
			big = titleFont;
			micro = bodyFont;
		}

		// Top micro bar (left + right)
		RowContainer top = new RowContainer()
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.START)
				.gap(8);
		LegacyTextElement topLeft = new LegacyTextElement("&7BOATRACING &8● &7MỞ ĐẦU")
				.font(micro)
				.defaultColor(pal.textDim())
				.align(LegacyTextElement.Align.LEFT)
				.trimToFit(true);
		topLeft.style().flexGrow(1);
		LegacyTextElement topRight = new LegacyTextElement("&7MÙA &f" + Year.now().getValue())
				.font(micro)
				.defaultColor(pal.textDim())
				.align(LegacyTextElement.Align.RIGHT)
				.trimToFit(true);
		top.add(topLeft);
		top.add(topRight);
		root.add(top);

		// Thin divider line
		GraphicsElement topLine = new GraphicsElement((ctx, rect) -> {
			try {
				if (ctx == null || ctx.g == null)
					return;
				Graphics2D g = ctx.g;
				Color a = pal.accentSoft(120);
				if (a != null)
					g.setColor(a);
				g.fillRect(rect.x(), rect.y(), rect.w(), Math.max(1, rect.h()));
			} catch (Throwable ignored) {
			}
		});
		topLine.style().heightPx(Math.max(1, (int) Math.round(Math.min(w, h) * 0.004)));
		root.add(topLine);

		LegacyTextElement k = new LegacyTextElement("&7TAY ĐUA")
				.font(label)
				.defaultColor(pal.textDim())
				.align(LegacyTextElement.Align.LEFT)
				.trimToFit(true);
		root.add(k);

		LegacyTextElement title = new LegacyTextElement(display)
				.font(big)
				.align(LegacyTextElement.Align.LEFT)
				.trimToFit(true);
		root.add(withAppearDelay(title, showDtMs, animateIn, 100L, Math.max(6, gap)));

		// Stats panel (wins/completed/time raced/personal best)
		int wins = 0;
		int completed = 0;
		long timeRaced = 0L;
		long bestPb = 0L;
		String bestPbTrack = "";
		try {
			PlayerProfileManager pm = plugin.getProfileManager();
			if (pm != null && id != null) {
				wins = pm.getWins(id);
				completed = pm.getCompleted(id);
				timeRaced = pm.getTimeRacedMillis(id);
				PlayerProfileManager.Profile prof = pm.get(id);
				if (prof != null && prof.personalBests != null && !prof.personalBests.isEmpty()) {
					for (var e : prof.personalBests.entrySet()) {
						if (e == null)
							continue;
						String tn = e.getKey();
						Long ms = e.getValue();
						if (tn == null || tn.isBlank() || ms == null || ms <= 0L)
							continue;
						if (bestPb <= 0L || ms < bestPb) {
							bestPb = ms;
							bestPbTrack = tn;
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}

		int panelW = Math.max(160, (int) Math.round(w * 0.62));
		int borderW = Math.max(1, (int) Math.round(Math.min(w, h) * 0.005));
		int panelPad = Math.max(8, (int) Math.round(Math.min(w, h) * 0.04));

		ColumnContainer panel = new ColumnContainer()
				.alignItems(UiAlign.STRETCH)
				.justifyContent(UiJustify.START)
				.gap(Math.max(2, gap / 2));
		panel.style()
				.widthPx(panelW)
				.padding(UiInsets.all(panelPad))
				.background(pal.panel2())
				.border(pal.border(), borderW);

		// Panel header with accent block
		RowContainer header = new RowContainer()
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.START)
				.gap(Math.max(6, gap));
		GraphicsElement accentBlock = new GraphicsElement((ctx, rect) -> {
			try {
				if (ctx == null || ctx.g == null)
					return;
				Graphics2D g = ctx.g;
				Color a = pal.accentSoft(220);
				if (a != null)
					g.setColor(a);
				g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
				// small cut corner
				Color cut = pal.panel();
				if (cut != null)
					g.setColor(cut);
				int s = Math.max(3, rect.h() / 3);
				g.fillRect(rect.x() + rect.w() - s, rect.y(), s, s);
			} catch (Throwable ignored) {
			}
		});
		accentBlock.style().widthPx(Math.max(10, borderW * 2 + 8)).heightPx(Math.max(14, (int) Math.round(Math.min(w, h) * 0.045)));
		header.add(accentBlock);

		LegacyTextElement hdr = new LegacyTextElement("&f&lTHÀNH TÍCH")
				.font(label)
				.align(LegacyTextElement.Align.LEFT)
				.trimToFit(true);
		header.add(hdr);
		panel.add(withAppearDelayOffset(header, showDtMs, animateIn, 0L, Math.max(3, gap / 2)));

		// Divider line
		GraphicsElement divider = new GraphicsElement((ctx, rect) -> {
			try {
				if (ctx == null || ctx.g == null)
					return;
				Graphics2D g = ctx.g;
				Color c = pal.border();
				if (c != null)
					g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 180));
				g.fillRect(rect.x(), rect.y() + rect.h() / 2, rect.w(), Math.max(1, rect.h() / 2));
			} catch (Throwable ignored) {
			}
		});
		divider.style().heightPx(Math.max(2, borderW));
		panel.add(withAppearDelayOffset(divider, showDtMs, animateIn, 40L, Math.max(2, gap / 2)));

		panel.add(withAppearDelayOffset(statRow(label, "&7★ Thắng", "&f" + wins, pal), showDtMs, animateIn, 80L, Math.max(2, gap / 2)));
		panel.add(withAppearDelayOffset(statRow(label, "&7✔ Hoàn thành", "&f" + completed, pal), showDtMs, animateIn, 120L, Math.max(2, gap / 2)));
		panel.add(withAppearDelayOffset(statRow(label, "&7⌚ Thời gian đua", "&f" + formatDurationVi(timeRaced), pal), showDtMs, animateIn, 160L, Math.max(2, gap / 2)));
		String pbV = (bestPb > 0L) ? (formatRaceTime(bestPb) + (bestPbTrack == null || bestPbTrack.isBlank() ? "" : (" &8● &7" + trimTrack(bestPbTrack)))) : "-";
		panel.add(withAppearDelayOffset(statRow(label, "&7⌚ PB tốt nhất", "&f" + pbV, pal), showDtMs, animateIn, 200L, Math.max(2, gap / 2)));

		// Stats panel itself: start after 0.2s
		root.add(withAppearDelay(panel, showDtMs, animateIn, 200L, Math.max(8, gap)));

		return root;
	}

	private UiElement withAppearDelayOffset(UiElement child, long showDtMs, boolean animateIn, long delayMs, int risePx) {
		if (child == null)
			return child;
		if (!animateIn)
			return child;
		if (SHOW_MS <= 0L)
			return child;

		long d = Math.max(0L, delayMs);
		long available = Math.max(1L, SHOW_MS - d);
		double t = (double) (Math.max(0L, showDtMs - d)) / (double) available;
		double e = easeOutCubic(t);
		int y = (int) Math.round((1.0 - e) * (double) Math.max(0, risePx));
		return wrapFx(child).alpha(1.0).offset(0, y);
	}

	private UiElement withAppearDelay(UiElement child, long showDtMs, boolean animateIn, long delayMs, int risePx) {
		if (child == null)
			return child;
		if (!animateIn)
			return child;
		if (SHOW_MS <= 0L)
			return child;

		long d = Math.max(0L, delayMs);
		long available = Math.max(1L, SHOW_MS - d);
		double t = (double) (Math.max(0L, showDtMs - d)) / (double) available;
		double e = easeOutCubic(t);
		int y = (int) Math.round((1.0 - e) * (double) Math.max(0, risePx));
		return wrapFx(child).alpha(e).offset(0, y);
	}

	private FxContainer wrapFx(UiElement child) {
		FxContainer fx = new FxContainer().child(child);
		try {
			fx.style().widthPx(child.style().widthPx());
			fx.style().heightPx(child.style().heightPx());
			fx.style().margin(child.style().margin());
		} catch (Throwable ignored) {
		}
		return fx;
	}

	private static RowContainer statRow(Font font, String label, String value, BroadcastTheme.Palette pal) {
		RowContainer row = new RowContainer()
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.START)
				.gap(10);
		LegacyTextElement l = new LegacyTextElement(label)
				.font(font)
				.defaultColor(pal.textDim())
				.align(LegacyTextElement.Align.LEFT)
				.trimToFit(true);
		l.style().flexGrow(1);
		LegacyTextElement v = new LegacyTextElement(value)
				.font(font)
				.align(LegacyTextElement.Align.RIGHT)
				.trimToFit(true);
		row.add(l);
		row.add(v);
		return row;
	}

	private static String formatDurationVi(long millis) {
		long ms = Math.max(0L, millis);
		long totalSeconds = ms / 1000L;
		long seconds = totalSeconds % 60L;
		long totalMinutes = totalSeconds / 60L;
		long minutes = totalMinutes % 60L;
		long hours = totalMinutes / 60L;
		if (hours > 0L) {
			return hours + "g " + String.format(Locale.ROOT, "%02dp %02ds", minutes, seconds);
		}
		return String.format(Locale.ROOT, "%dp %02ds", minutes, seconds);
	}

	private static String formatRaceTime(long millis) {
		long ms = Math.max(0L, millis);
		long totalSeconds = ms / 1000L;
		long seconds = totalSeconds % 60L;
		long totalMinutes = totalSeconds / 60L;
		long minutes = totalMinutes % 60L;
		long hours = totalMinutes / 60L;
		long remMs = ms % 1000L;
		if (hours > 0L) {
			return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, remMs);
		}
		return String.format(Locale.ROOT, "%d:%02d.%03d", minutes, seconds, remMs);
	}

	private static String trimTrack(String trackName) {
		if (trackName == null)
			return "";
		String t = trackName.trim();
		if (t.length() <= 18)
			return t;
		return t.substring(0, 18) + "…";
	}

	private static double easeInCubic(double t) {
		double x = Math.max(0.0, Math.min(1.0, t));
		return x * x * x;
	}

	private static double easeOutCubic(double t) {
		double x = Math.max(0.0, Math.min(1.0, t));
		double k = 1.0 - x;
		return 1.0 - (k * k * k);
	}

	private BufferedImage loadBundledLogoCached() {
		if (bundledLogo != null)
			return bundledLogo;
		try (java.io.InputStream is = plugin.getResource("imgs/logo.png")) {
			if (is == null)
				return null;
			bundledLogo = ImageIO.read(is);
			return bundledLogo;
		} catch (Throwable ignored) {
			bundledLogo = null;
			return null;
		}
	}

	private BufferedImage loadFaviconIfNeeded() {
		long now = System.currentTimeMillis();
		if (faviconImage != null && (now - faviconLastLoadMs) < 5000L)
			return faviconImage;

		faviconLastLoadMs = now;

		try {
			File f = new File("server-icon.png");
			if (!f.exists()) {
				faviconImage = null;
				return null;
			}
			BufferedImage img = ImageIO.read(f);
			faviconImage = img;
			return img;
		} catch (Throwable t) {
			faviconImage = null;
			if (debug) {
				try {
					plugin.getLogger().warning("[OpeningTitlesBoard] Không thể đọc server-icon.png: "
							+ (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
				} catch (Throwable ignored) {
				}
			}
			return null;
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
