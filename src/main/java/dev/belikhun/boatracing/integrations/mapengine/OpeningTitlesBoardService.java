package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardFontLoader;
import dev.belikhun.boatracing.integrations.mapengine.board.BoardPlacement;
import dev.belikhun.boatracing.integrations.mapengine.board.MapEngineBoardDisplay;
import dev.belikhun.boatracing.integrations.mapengine.board.RenderBuffers;
import dev.belikhun.boatracing.integrations.mapengine.ui.ColumnContainer;
import dev.belikhun.boatracing.integrations.mapengine.ui.ImageElement;
import dev.belikhun.boatracing.integrations.mapengine.ui.LegacyTextElement;
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
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
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

	// Assets
	private BufferedImage faviconImage;
	private long faviconLastLoadMs;

	// Runtime state
	private volatile Screen screen = Screen.FAVICON;
	private volatile UUID racerId;
	private volatile String racerName;
	private volatile Set<UUID> desiredViewers = new HashSet<>();

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

		try {
			BufferedImage img = renderUi(placement.pixelWidth(), placement.pixelHeight());
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

		boardDisplay.destroy();
	}

	public void setViewers(Set<UUID> viewers) {
		this.desiredViewers = (viewers == null) ? new HashSet<>() : new HashSet<>(viewers);
	}

	public void showFavicon() {
		screen = Screen.FAVICON;
		racerId = null;
		racerName = null;
	}

	public void showRacer(UUID racerId, String racerName) {
		screen = Screen.RACER_CARD;
		this.racerId = racerId;
		this.racerName = racerName;
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
		Screen s = screen;
		return switch (s) {
			case FAVICON -> buildFaviconUi(w, h);
			case RACER_CARD -> buildRacerUi(w, h);
		};
	}

	private UiElement buildFaviconUi(int w, int h) {
		BufferedImage icon = loadFaviconIfNeeded();
		if (icon == null) {
			// Fallback to bundled logo from EventBoardService
			icon = loadBundledLogo();
		}

		ColumnContainer root = new ColumnContainer()
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.CENTER);
		root.style().padding(UiInsets.all(Math.max(8, (int) Math.round(Math.min(w, h) * 0.04))));

		if (icon != null) {
			int imgH = (int) Math.round(h * 0.65);
			ImageElement image = new ImageElement(icon)
					.fit(ImageElement.Fit.CONTAIN)
					.smoothing(true);
			image.style().heightPx(imgH);
			root.add(image);
		}

		return root;
	}

	private UiElement buildRacerUi(int w, int h) {
		UUID id = racerId;
		String name = racerName;

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

		ColumnContainer root = new ColumnContainer()
				.alignItems(UiAlign.CENTER)
				.justifyContent(UiJustify.CENTER);
		root.style().padding(UiInsets.all(Math.max(10, (int) Math.round(Math.min(w, h) * 0.05))));

		LegacyTextElement title = new LegacyTextElement(display)
				.font(titleFont)
				.align(LegacyTextElement.Align.CENTER)
				.trimToFit(true);

		root.add(title);

		return root;
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
