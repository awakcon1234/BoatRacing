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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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

		Set<UUID> eligible = new HashSet<>();
		for (UUID id : desiredViewers) {
			if (id == null)
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			eligible.add(id);
		}
		for (UUID id : previewViewers) {
			if (id == null)
				continue;
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			eligible.add(id);
		}

		if (eligible.isEmpty()) {
			if (tickTask != null && (desiredViewers == null || desiredViewers.isEmpty())
					&& (previewViewers == null || previewViewers.isEmpty())) {
				// No viewers at all; stop to free resources.
				stop();
			}
			return;
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

		BufferedImage img = renderUi(placement.pixelWidth(), placement.pixelHeight());
		boardDisplay.renderAndFlush(img);
	}

	private BufferedImage renderUi(int w, int h) {
		BufferedImage img = renderBuffers.acquire(w, h);
		UiElement root = buildRoot(w, h);
		UiComposer.renderInto(img, root, bodyFont, fallbackFont);
		return img;
	}

	private UiElement buildRoot(int w, int h) {
		BroadcastTheme.Palette pal = BroadcastTheme.palette(BroadcastTheme.ACCENT_READY);
		final long now = System.currentTimeMillis();

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

		// Background: solid fill + minimal broadcast-style decoration.
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

			// Colorful broadcast accents (blocks/lines) using existing theme accents.
			try {
				Color cReady = BroadcastTheme.palette(BroadcastTheme.ACCENT_READY).accentSoft(160);
				Color cRun = BroadcastTheme.palette(BroadcastTheme.ACCENT_RUNNING).accentSoft(150);
				Color cCd = BroadcastTheme.palette(BroadcastTheme.ACCENT_COUNTDOWN).accentSoft(150);
				Color cReg = BroadcastTheme.palette(BroadcastTheme.ACCENT_REGISTERING).accentSoft(150);

				int barW = Math.max(10, (int) Math.round(rect.w() * 0.055));
				int segH = Math.max(10, rect.h() / 5);
				int x0 = rect.x();
				int y0 = rect.y();

				if (cReady != null) {
					g.setColor(cReady);
					g.fillRect(x0, y0, barW, Math.min(segH, rect.h()));
				}
				if (cRun != null) {
					g.setColor(cRun);
					g.fillRect(x0, y0 + segH, barW, Math.min(segH, rect.h() - segH));
				}
				if (cCd != null) {
					g.setColor(cCd);
					g.fillRect(x0, y0 + segH * 2, barW, Math.min(segH, rect.h() - segH * 2));
				}
				if (cReg != null) {
					g.setColor(cReg);
					g.fillRect(x0, y0 + segH * 3, barW, Math.max(0, rect.h() - segH * 3));
				}

				// Thin top accent line
				Color topLine = pal.accentSoft(130);
				if (topLine != null) {
					g.setColor(topLine);
					int lh = Math.max(2, (int) Math.round(rect.h() * 0.012));
					g.fillRect(rect.x(), rect.y(), rect.w(), lh);
				}

				// Diagonal slash blocks (subtle) - keep inside the right margin band (avoid overlapping content)
				Color slash = pal.accentSoft(55);
				if (slash != null) {
					g.setColor(slash);
					int sw = Math.max(10, Math.min((int) Math.round(rect.w() * 0.09), Math.max(12, marginX - 8)));
					int sh = Math.max(10, (int) Math.round(rect.h() * 0.55));
					int sx = rect.x() + rect.w() - marginX + (int) Math.round(marginX * 0.12);
					int sy = safeTop - (int) Math.round(marginY * 0.20);
					sh = Math.min(sh, Math.max(10, safeBottom - sy));
					int[] px = new int[] { sx, sx + sw, sx + sw - (int) (sw * 0.35), sx - (int) (sw * 0.35) };
					int[] py = new int[] { sy, sy, sy + sh, sy + sh };
					g.fillPolygon(px, py, 4);
					int sx2 = sx + sw + (int) (sw * 0.55);
					if (sx2 < rect.x() + rect.w() - 2) {
						g.fillPolygon(
								new int[] { sx2, sx2 + sw, sx2 + sw - (int) (sw * 0.35), sx2 - (int) (sw * 0.35) },
								py,
								4
						);
					}
				}
			} catch (Throwable ignored) {
			}

			// Outer border + corner marks
			try {
				int bw = Math.max(1, (int) Math.round(Math.min(rect.w(), rect.h()) * 0.006));
				g.setColor(pal.border());
				g.setStroke(new BasicStroke(bw));
				int half = bw / 2;
				g.drawRect(rect.x() + half, rect.y() + half, Math.max(0, rect.w() - bw), Math.max(0, rect.h() - bw));

				int m = Math.max(6, (int) Math.round(Math.min(rect.w(), rect.h()) * 0.04));
				int l = Math.max(10, (int) Math.round(Math.min(rect.w(), rect.h()) * 0.07));
				Color a = pal.accentSoft(170);
				if (a != null) g.setColor(a);
				// TL
				g.drawLine(rect.x() + m, rect.y() + m, rect.x() + m + l, rect.y() + m);
				g.drawLine(rect.x() + m, rect.y() + m, rect.x() + m, rect.y() + m + l);
				// TR
				g.drawLine(rect.x() + rect.w() - m - l, rect.y() + m, rect.x() + rect.w() - m, rect.y() + m);
				g.drawLine(rect.x() + rect.w() - m, rect.y() + m, rect.x() + rect.w() - m, rect.y() + m + l);
				// BL
				g.drawLine(rect.x() + m, rect.y() + rect.h() - m, rect.x() + m + l, rect.y() + rect.h() - m);
				g.drawLine(rect.x() + m, rect.y() + rect.h() - m - l, rect.x() + m, rect.y() + rect.h() - m);
			} catch (Throwable ignored) {
			}

			// Font Awesome glyph sprinkles (optional)
			try {
				if (iconFont != null) {
					int size = clamp((int) Math.round(Math.min(rect.w(), rect.h()) * 0.06), 10, 28);
					Font f = iconFont.deriveFont(Font.PLAIN, (float) size);
					g.setFont(f);
					Color c = pal.accentSoft(140);
					if (c != null)
						g.setColor(c);

					// A few safe/common FA codepoints (regular set typically supports these)
					String[] glyphs = new String[] {
						"\uf111", // circle
						"\uf005", // star
						"\uf0c8", // square
						"\uf04b", // play
						"\uf0e7", // bolt
						"\uf135"  // rocket
					};

					int pad = Math.max(8, (int) Math.round(Math.min(rect.w(), rect.h()) * 0.06));
					int xL = rect.x() + pad;
					int xR = rect.x() + rect.w() - pad - size;
					int yT = rect.y() + pad + size;
					int yB = rect.y() + rect.h() - pad;

					// Draw only if glyph exists in the font.
					String g0 = glyphs[0];
					String g1 = glyphs[1];
					String g2 = glyphs[2];
					String g3 = glyphs[3];
					String g4 = glyphs[4];
					String g5 = glyphs[5];
					if (f.canDisplay(g0.codePointAt(0))) g.drawString(g0, xL, yT);
					if (f.canDisplay(g1.codePointAt(0))) g.drawString(g1, xR, yT);
					if (f.canDisplay(g2.codePointAt(0))) g.drawString(g2, xL, yB);
					if (f.canDisplay(g3.codePointAt(0))) g.drawString(g3, xR, yB);
					// Extra glyphs: keep in the side margin bands only
					int midY = rect.y() + rect.h() / 2;
					int xLeftBand = rect.x() + Math.max(6, marginX / 2);
					int xRightBand = rect.x() + rect.w() - Math.max(6, marginX / 2) - size;
					if (xLeftBand < safeLeft - 4 && f.canDisplay(g5.codePointAt(0))) g.drawString(g5, xLeftBand, midY);
					if (xRightBand > safeRight + 4 && f.canDisplay(g4.codePointAt(0))) g.drawString(g4, xRightBand, midY);
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

			// Small decorative dots row
			try {
				Color dim = pal.textDimSoft(90);
				if (dim != null) g.setColor(dim);
				int baseY = rect.y() + rect.h() - Math.max(8, marginY / 2);
				int startX = rect.x() + Math.max(10, marginX / 2);
				int gap = Math.max(6, (int) Math.round(rect.w() * 0.015));
				int r = Math.max(2, (int) Math.round(Math.min(rect.w(), rect.h()) * 0.006));
				for (int i = 0; i < 8; i++) {
					int x = startX + i * gap;
					if (x >= safeLeft && x <= safeRight)
						continue;
					g.fillOval(x, baseY, r, r);
				}
			} catch (Throwable ignored) {
			}
		}));

		UiElement curUi;
		if (!isTrans) {
			Screen s = screen;
			curUi = buildRootFor(s, racerId, racerName, w, h, pal, showDtMs, false);
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

	private UiElement buildRootFor(Screen s, UUID id, String name, int w, int h, BroadcastTheme.Palette pal, long showDtMs, boolean animateIn) {
		return switch (s) {
			case FAVICON -> buildFaviconUi(w, h, pal);
			case RACER_CARD -> buildRacerUi(w, h, pal, id, name, showDtMs, animateIn);
		};
	}

	private UiElement buildFaviconUi(int w, int h, BroadcastTheme.Palette pal) {
		BufferedImage icon = loadFaviconIfNeeded();
		if (icon == null) {
			// Fallback to bundled logo from EventBoardService
			icon = loadBundledLogo();
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

		int pad = Math.max(10, (int) Math.round(Math.min(w, h) * 0.07));
		int gap = Math.max(6, (int) Math.round(Math.min(w, h) * 0.02));
		ColumnContainer root = new ColumnContainer()
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.CENTER)
				.gap(gap);
		root.style().padding(UiInsets.all(pad));

		Font label;
		Font big;
		try {
			label = bodyFont != null ? bodyFont.deriveFont(Font.PLAIN, (float) clamp((int) Math.round(Math.min(w, h) * 0.05), 10, 26)) : bodyFont;
			big = titleFont != null ? titleFont.deriveFont(Font.BOLD, (float) clamp((int) Math.round(Math.min(w, h) * 0.13), 16, 56)) : titleFont;
		} catch (Throwable ignored) {
			label = bodyFont;
			big = titleFont;
		}

		LegacyTextElement k = new LegacyTextElement("&7TAY ĐUA")
				.font(label)
				.defaultColor(pal.textDim())
				.align(LegacyTextElement.Align.CENTER)
				.trimToFit(true);
		root.add(k);

		LegacyTextElement title = new LegacyTextElement(display)
				.font(big)
				.align(LegacyTextElement.Align.CENTER)
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

		int panelW = Math.max(160, (int) Math.round(w * 0.74));
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
		panel.add(withAppearDelay(header, showDtMs, animateIn, 200L, Math.max(4, gap / 2)));

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
		panel.add(withAppearDelay(divider, showDtMs, animateIn, 240L, Math.max(3, gap / 2)));

		panel.add(withAppearDelay(statRow(label, "&7★ Thắng", "&f" + wins, pal), showDtMs, animateIn, 280L, Math.max(3, gap / 2)));
		panel.add(withAppearDelay(statRow(label, "&7✔ Hoàn thành", "&f" + completed, pal), showDtMs, animateIn, 320L, Math.max(3, gap / 2)));
		panel.add(withAppearDelay(statRow(label, "&7⌚ Thời gian đua", "&f" + formatDurationVi(timeRaced), pal), showDtMs, animateIn, 360L, Math.max(3, gap / 2)));
		String pbV = (bestPb > 0L) ? (formatRaceTime(bestPb) + (bestPbTrack == null || bestPbTrack.isBlank() ? "" : (" &8● &7" + trimTrack(bestPbTrack)))) : "-";
		panel.add(withAppearDelay(statRow(label, "&7⌚ PB tốt nhất", "&f" + pbV, pal), showDtMs, animateIn, 400L, Math.max(3, gap / 2)));

		// Stats panel itself: start after 0.2s
		root.add(withAppearDelay(panel, showDtMs, animateIn, 200L, Math.max(8, gap)));

		return root;
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
		return new FxContainer().alpha(e).offset(0, y).child(child);
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

	private BufferedImage loadBundledLogo() {
		try (java.io.InputStream is = plugin.getResource("imgs/logo.png")) {
			if (is == null)
				return null;
			return ImageIO.read(is);
		} catch (Throwable ignored) {
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
