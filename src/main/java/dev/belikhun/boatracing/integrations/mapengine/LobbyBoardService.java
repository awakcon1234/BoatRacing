package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.util.Converter;
import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.profile.PlayerProfileManager;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.race.RaceService;
import dev.belikhun.boatracing.track.TrackLibrary;
import dev.belikhun.boatracing.track.TrackRecordManager;
import dev.belikhun.boatracing.util.ColorTranslator;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lobby information board rendered with MapEngine.
 *
 * Visibility rules (per user request):
 * - Only for online players who are currently in the lobby (not in any race)
 * - AND are within a configurable chunk radius (default: 12 chunks) of the board
 */
public final class LobbyBoardService {
	// Icons used on the lobby board UI (Unicode). These are rendered with font fallback.
	private static final String ICON_INFO = "ⓘ";
	private static final String ICON_CLOCK = "⏰";

    // Inner padding for left track rows (moves the leading status dot away from the border)
    private static final int TRACK_ROW_INNER_PAD = 8;

    private static int trackRowInnerPad(Font bodyFont) {
        int size = (bodyFont != null ? bodyFont.getSize() : 16);
        // Scale gently with font size so spacing remains consistent across board resolutions.
        return Math.max(TRACK_ROW_INNER_PAD, (int) Math.round(size * 0.40));
    }

    private static float minimapStrokeFromBorder(int border) {
        // Border already scales with overall UI; derive minimap stroke from it.
        // Clamp to keep the map readable without becoming chunky.
        float b = Math.max(1, border);
        // The minimap centerline can look too thin on large boards when we clamp too low.
        // Use a slightly higher multiplier and a higher cap so it remains visible.
        float s = b * 0.90f;
        if (s < 1.5f) s = 1.5f;
        if (s > 6.0f) s = 6.0f;
        return s;
    }

    private final BoatRacingPlugin plugin;
    private final RaceService raceService;
    private final TrackLibrary trackLibrary;
    private final PlayerProfileManager profileManager;

    private BukkitTask tickTask;

    private IMapDisplay display;
    private IDrawingSpace drawing;

    private BoardPlacement placement;
    private int visibleRadiusChunks = 12;
    private int updateTicks = 20;
    private boolean debug = false;

    // MapEngine drawing pipeline toggles
    private boolean mapBuffering = true;
    private boolean mapBundling = false;

    // Optional custom font (Minecraft-style). We do not ship a font file; users can provide one.
    private String fontFile;
    private volatile Font boardFontBase;

    private long lastDebugTickLogMillis = 0L;

    private final Set<UUID> spawnedTo = new HashSet<>();

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
        visibleRadiusChunks = clamp(plugin.getConfig().getInt("mapengine.lobby-board.visible-radius-chunks", 12), 1, 64);
        updateTicks = clamp(plugin.getConfig().getInt("mapengine.lobby-board.update-ticks", 20), 1, 200);
        debug = plugin.getConfig().getBoolean("mapengine.lobby-board.debug", false);

        // MapEngine pipeline options
        mapBuffering = plugin.getConfig().getBoolean("mapengine.lobby-board.pipeline.buffering", true);
        mapBundling = plugin.getConfig().getBoolean("mapengine.lobby-board.pipeline.bundling", false);

        String ff = null;
        try { ff = plugin.getConfig().getString("mapengine.lobby-board.font-file", null); } catch (Throwable ignored) { ff = null; }
        if (ff != null) {
            ff = ff.trim();
            if (ff.isEmpty()) ff = null;
        }
        this.fontFile = ff;
        this.boardFontBase = null;
        tryLoadBoardFont();
    }

    public void start() {
        if (tickTask != null) return;

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
        if (display == null || drawing == null) {
            dbg("start(): failed to create display/drawing");
            return;
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, updateTicks);
        dbg("start(): started tick task (updateTicks=" + updateTicks + ") maps="
                + placement.mapsWide + "x" + placement.mapsHigh + " px=" + placement.pixelWidth() + "x" + placement.pixelHeight());
    }

    public void stop() {
        if (tickTask != null) {
            try { tickTask.cancel(); } catch (Throwable ignored) {}
            tickTask = null;
        }

        // despawn for everyone
        for (UUID id : new HashSet<>(spawnedTo)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                try { despawnFor(p); } catch (Throwable ignored) {}
            }
        }
        spawnedTo.clear();

        // best-effort destroy the display object (API varies between versions)
        if (display != null) {
            tryInvoke(display, "destroy");
            display = null;
        }
        drawing = null;

        dbg("stop(): stopped");
    }

    /**
     * Player-facing diagnostics for /boatracing board status.
     */
    public java.util.List<String> statusLines() {
        java.util.List<String> out = new java.util.ArrayList<>();

        boolean enabled = false;
        try { enabled = plugin.getConfig().getBoolean("mapengine.lobby-board.enabled", false); } catch (Throwable ignored) { enabled = false; }
        boolean apiOk = MapEngineService.isAvailable();

        out.add("&eBảng thông tin sảnh (MapEngine):");
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
            out.add("&7● Kích thước: &f" + placement.mapsWide + "&7x&f" + placement.mapsHigh + "&7 maps (&f" + placement.pixelWidth() + "&7x&f" + placement.pixelHeight() + "&7 px)");
        }

        out.add("&7● Người đang thấy: &f" + spawnedTo.size());
        out.add("&7● Debug log: " + (debug ? "&aBật" : "&cTắt") + " &8(&7mapengine.lobby-board.debug&8)");
        try {
            Font f = boardFontBase;
            out.add("&7● Font: &f" + (f != null ? (f.getFontName() + " (" + f.getFamily() + ")") : "Mặc định"));
            if (fontFile != null) out.add("&7● Font file: &f" + fontFile);
            else out.add("&7● Font file: &8(chưa cấu hình)");
        } catch (Throwable ignored) {}
        return out;
    }

    private void tryLoadBoardFont() {
        // NOTE: We do not distribute any Minecraft font file. Admins can provide a TTF/OTF they have rights to use.
        // This method searches in:
        // 1) config path mapengine.lobby-board.font-file (relative to plugin data folder if not absolute)
        // 2) plugins/BoatRacing/fonts/minecraft.ttf|otf
        // 3) plugins/BoatRacing/minecraft.ttf|otf
        // 4) bundled resources fonts/minecraft.ttf|otf (optional)

        // Keep existing font if already loaded.
        if (boardFontBase != null) return;

        List<java.util.function.Supplier<InputStream>> candidates = new ArrayList<>();

        if (fontFile != null) {
            candidates.add(() -> {
                try {
                    File f = new File(fontFile);
                    if (!f.isAbsolute()) f = new File(plugin.getDataFolder(), fontFile);
                    if (!f.exists() || !f.isFile()) return null;
                    return new FileInputStream(f);
                } catch (Throwable ignored) { return null; }
            });
        }

        candidates.add(() -> {
            try {
                File f = new File(plugin.getDataFolder(), "fonts/minecraft.ttf");
                if (!f.exists()) f = new File(plugin.getDataFolder(), "fonts/minecraft.otf");
                if (!f.exists()) return null;
                return new FileInputStream(f);
            } catch (Throwable ignored) { return null; }
        });
        candidates.add(() -> {
            try {
                File f = new File(plugin.getDataFolder(), "minecraft.ttf");
                if (!f.exists()) f = new File(plugin.getDataFolder(), "minecraft.otf");
                if (!f.exists()) return null;
                return new FileInputStream(f);
            } catch (Throwable ignored) { return null; }
        });
        candidates.add(() -> {
            try {
                InputStream is = plugin.getResource("fonts/minecraft.ttf");
                if (is == null) is = plugin.getResource("fonts/minecraft.otf");
                return is;
            } catch (Throwable ignored) { return null; }
        });

        for (var sup : candidates) {
            InputStream is = null;
            try {
                is = sup.get();
                if (is == null) continue;
                Font f = Font.createFont(Font.TRUETYPE_FONT, is);
                try { GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f); } catch (Throwable ignored) {}
                boardFontBase = f;
                dbg("Loaded board font: " + f.getFontName());
                return;
            } catch (Throwable t) {
                dbg("Failed to load board font: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            } finally {
                try { if (is != null) is.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private Font boardPlain(int size) {
        int s = Math.max(8, size);
        Font base = boardFontBase;
        if (base == null) return new Font(Font.MONOSPACED, Font.PLAIN, s);
        try { return base.deriveFont(Font.PLAIN, (float) s); } catch (Throwable ignored) { return new Font(Font.MONOSPACED, Font.PLAIN, s); }
    }

    private Font boardBold(int size) {
        int s = Math.max(8, size);
        Font base = boardFontBase;
        if (base == null) return new Font(Font.MONOSPACED, Font.BOLD, s);
        try { return base.deriveFont(Font.BOLD, (float) s); } catch (Throwable ignored) { return base.deriveFont(Font.PLAIN, (float) s); }
    }

    public boolean setPlacementFromSelection(org.bukkit.entity.Player p, org.bukkit.util.BoundingBox box, BlockFace facing) {
        if (p == null || box == null) return false;
        if (p.getWorld() == null) return false;

        BlockFace dir;
        if (facing != null) {
            dir = normalizeCardinal(facing);
        } else {
            dir = autoFacingFromPlayer(p, box);
        }
        if (dir == null) return false;

        BoardPlacement pl = BoardPlacement.fromSelection(p.getWorld(), box, dir);
        if (pl == null || !pl.isValid()) return false;

        // persist
        pl.save(plugin.getConfig().createSection("mapengine.lobby-board.placement"));
        plugin.getConfig().set("mapengine.lobby-board.enabled", true);
        plugin.saveConfig();

        // reload running service
        reloadFromConfig();

        // Preview: spawn/render immediately for the setter so they can confirm placement instantly.
        try { previewTo(p); } catch (Throwable ignored) {}
        return true;
    }

    private void previewTo(org.bukkit.entity.Player p) {
        if (p == null || !p.isOnline()) return;
        if (!plugin.getConfig().getBoolean("mapengine.lobby-board.enabled", false)) return;
        if (placement == null || !placement.isValid()) return;

        MapEngineApi api = MapEngineService.get();
        if (api == null) return;

        ensureDisplay(api);
        if (display == null || drawing == null) return;

        // Always show preview to the admin who just placed it, regardless of lobby/radius filters.
        try { spawnFor(p); } catch (Throwable ignored) {}
        try { spawnedTo.add(p.getUniqueId()); } catch (Throwable ignored) {}

        try {
            BufferedImage img = renderImage(placement.pixelWidth(), placement.pixelHeight());
            drawing.image(img, 0, 0);
            drawing.flush();
        } catch (Throwable ignored) {}
    }

    private static BlockFace autoFacingFromPlayer(org.bukkit.entity.Player p, org.bukkit.util.BoundingBox box) {
        if (p == null || box == null) return null;
        org.bukkit.Location loc = p.getLocation();
        if (loc == null) return null;

        int minX = (int) Math.floor(Math.min(box.getMinX(), box.getMaxX()));
        int maxX = (int) Math.floor(Math.max(box.getMinX(), box.getMaxX()));
        int minZ = (int) Math.floor(Math.min(box.getMinZ(), box.getMaxZ()));
        int maxZ = (int) Math.floor(Math.max(box.getMinZ(), box.getMaxZ()));

        int dx = maxX - minX;
        int dz = maxZ - minZ;

        // If selection is a thin vertical plane, determine facing from which side the player is on.
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

        // Otherwise, pick based on where the player is looking: board should face the player.
        float yaw = loc.getYaw();
        BlockFace looking = yawToCardinal(yaw);
        if (looking == null) return BlockFace.SOUTH;
        return looking.getOppositeFace();
    }

    private static BlockFace yawToCardinal(float yaw) {
        float y = yaw;
        y = (y % 360.0f + 360.0f) % 360.0f;
        // 0=south, 90=west, 180=north, 270=east
        if (y >= 315.0f || y < 45.0f) return BlockFace.SOUTH;
        if (y < 135.0f) return BlockFace.WEST;
        if (y < 225.0f) return BlockFace.NORTH;
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
        if (placement == null || !placement.isValid()) return "&cChưa đặt bảng.";
        return "&aĐã đặt bảng tại &f" + placement.world
                + " &7(" + placement.a.getBlockX() + "," + placement.a.getBlockY() + "," + placement.a.getBlockZ() + ")"
                + " -> &7(" + placement.b.getBlockX() + "," + placement.b.getBlockY() + "," + placement.b.getBlockZ() + ")"
                + " &8● &7hướng &f" + placement.facing;
    }

    private void tick() {
        if (placement == null || !placement.isValid()) return;
        MapEngineApi api = MapEngineService.get();
        if (api == null) {
            dbg("tick(): MapEngineApi became unavailable; stopping");
            stop();
            return;
        }

        ensureDisplay(api);
        if (display == null || drawing == null) return;

        // Determine eligible viewers.
        Set<UUID> eligible = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline() || p.getWorld() == null) continue;

            // Only lobby players (not in any race).
            if (raceService != null && raceService.findRaceFor(p.getUniqueId()) != null) continue;

            if (!isWithinRadiusChunks(p, placement, visibleRadiusChunks)) continue;
            eligible.add(p.getUniqueId());
        }

        dbgTick("tick(): eligible=" + eligible.size() + " spawnedTo=" + spawnedTo.size());

        // Despawn players that are no longer eligible.
        for (UUID id : new HashSet<>(spawnedTo)) {
            if (eligible.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                try { despawnFor(p); } catch (Throwable ignored) {}
            }
            spawnedTo.remove(id);
        }

        // Spawn to new eligible viewers.
        for (UUID id : eligible) {
            if (spawnedTo.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            try { spawnFor(p); } catch (Throwable ignored) {}
            spawnedTo.add(id);
        }

        if (spawnedTo.isEmpty()) return;

        // Render content and flush to receivers.
        try {
            BufferedImage img = renderImage(placement.pixelWidth(), placement.pixelHeight());
            drawing.image(img, 0, 0);
            drawing.flush();
        } catch (Throwable t) {
            dbg("tick(): render/flush failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void ensureDisplay(MapEngineApi api) {
        if (api == null) return;
        if (placement == null || !placement.isValid()) return;
        if (display != null && drawing != null) return;

        try {
            display = api.displayProvider().createBasic(placement.a, placement.b, placement.facing);
            drawing = api.pipeline().createDrawingSpace(display);
            try {
                // Best for UI: crisp solids and stable text (avoid dithering artifacts).
                drawing.ctx().converter(Converter.DIRECT);
                applyPipelineToggles();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            dbg("ensureDisplay(): failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            display = null;
            drawing = null;
        }
    }

    private void applyPipelineToggles() {
        if (drawing == null) return;
        Object ctx;
        try { ctx = drawing.ctx(); }
        catch (Throwable ignored) { return; }

        // Buffering: improves visual stability and reduces partial-frame flicker.
        if (mapBuffering) {
            try {
                drawing.ctx().buffering(true);
            } catch (Throwable ignored) {
                tryInvoke(ctx, "buffering", true);
                tryInvoke(ctx, "setBuffering", true);
            }
        }

        // Bundling: when disabled, flushes are sent immediately rather than batched.
        // Method names differ across MapEngine versions; try best-effort reflection.
        if (!mapBundling) {
            tryInvoke(ctx, "bundling", false);
            tryInvoke(ctx, "setBundling", false);
            tryInvoke(ctx, "bundle", false);
            tryInvoke(ctx, "setBundle", false);
            tryInvoke(ctx, "bundled", false);
            tryInvoke(ctx, "setBundled", false);
        }
    }

    private void spawnFor(Player p) {
        if (p == null) return;
        if (display == null || drawing == null) return;

        // Different MapEngine versions may differ in method names/signatures; use best-effort reflection.
        boolean spawnOk = false;
        try {
            // Prefer direct call (fast path)
            display.spawn(p);
            spawnOk = true;
        } catch (Throwable t) {
            // Fallbacks
            tryInvoke(display, "spawn", p);
            tryInvoke(display, "show", p);
            tryInvoke(display, "spawnTo", p);
            tryInvoke(display, "addViewer", p);
        }

        boolean recvOk = false;
        try {
            drawing.ctx().addReceiver(p);
            recvOk = true;
        } catch (Throwable t) {
            tryInvoke(drawing.ctx(), "addReceiver", p);
            tryInvoke(drawing.ctx(), "add", p);
        }

        dbgTick("spawnFor(" + p.getName() + "): spawnOk=" + spawnOk + " recvOk=" + recvOk);
    }

    private void despawnFor(Player p) {
        if (p == null) return;
        if (display == null || drawing == null) return;

        // Receiver removal name varies; try a few.
        tryInvoke(drawing.ctx(), "removeReceiver", p);
        tryInvoke(drawing.ctx(), "remove", p);

        // Display despawn/destroy name varies; try a few.
        tryInvoke(display, "destroy", p);
        tryInvoke(display, "despawn", p);
        tryInvoke(display, "remove", p);
        tryInvoke(display, "hide", p);
        tryInvoke(display, "removeViewer", p);
    }

    private void dbg(String msg) {
        if (!debug || plugin == null) return;
        try { plugin.getLogger().info("[LobbyBoard] " + msg); } catch (Throwable ignored) {}
    }

    private void dbgTick(String msg) {
        if (!debug || plugin == null) return;
        long now = System.currentTimeMillis();
        // Rate limit noisy tick logs.
        if (lastDebugTickLogMillis != 0L && (now - lastDebugTickLogMillis) < 1500L) return;
        lastDebugTickLogMillis = now;
        try { plugin.getLogger().info("[LobbyBoard] " + msg); } catch (Throwable ignored) {}
    }

    private enum TrackStatus {
        RUNNING,
        COUNTDOWN,
        REGISTERING,
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
            int countdownSeconds
    ) {}

    private static TrackStatus statusOf(RaceManager rm) {
        if (rm == null) return TrackStatus.OFF;
        if (rm.isRunning()) return TrackStatus.RUNNING;
        if (rm.isAnyCountdownActive()) return TrackStatus.COUNTDOWN;
        if (rm.isRegistering()) return TrackStatus.REGISTERING;
        return TrackStatus.READY;
    }

    private static Color accentForStatus(TrackStatus status) {
        if (status == null) status = TrackStatus.OFF;
        return switch (status) {
            case RUNNING -> new Color(0x56, 0xF2, 0x7A);
            case COUNTDOWN -> new Color(0xFF, 0xB8, 0x4D);
            case REGISTERING -> new Color(0xFF, 0xD7, 0x5E);
            case READY -> new Color(0x5E, 0xA8, 0xFF);
            case OFF -> new Color(0x8A, 0x8A, 0x8A);
        };
    }

    private static Color mix(Color a, Color b, double t) {
        if (a == null) return b;
        if (b == null) return a;
        double k = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(a.getRed() * (1.0 - k) + b.getRed() * k);
        int g = (int) Math.round(a.getGreen() * (1.0 - k) + b.getGreen() * k);
        int bl = (int) Math.round(a.getBlue() * (1.0 - k) + b.getBlue() * k);
        return new Color(clamp(r, 0, 255), clamp(g, 0, 255), clamp(bl, 0, 255));
    }

    private List<TrackInfo> collectTrackInfos() {
        if (trackLibrary == null) return java.util.Collections.emptyList();

        List<String> tracks = new ArrayList<>();
        try { tracks.addAll(trackLibrary.list()); } catch (Throwable ignored) {}
        tracks.sort(String.CASE_INSENSITIVE_ORDER);

        List<TrackInfo> out = new ArrayList<>();
        TrackRecordManager trm = null;
        try { trm = plugin != null ? plugin.getTrackRecordManager() : null; } catch (Throwable ignored) { trm = null; }

        for (String tn : tracks) {
            if (tn == null || tn.isBlank()) continue;

            RaceManager rm = null;
            try { rm = raceService != null ? raceService.getOrCreate(tn) : null; } catch (Throwable ignored) { rm = null; }

            TrackStatus status = statusOf(rm);
            int regs = 0;
            int involved = 0;
            int max = 0;
            int countdownSec = 0;
            try {
                if (rm != null) {
                    regs = rm.getRegistered().size();
                    involved = rm.getInvolved().size();
                    max = rm.getTrackConfig() != null ? rm.getTrackConfig().getStarts().size() : 0;
                    countdownSec = Math.max(0, rm.getCountdownRemainingSeconds());
                }
            } catch (Throwable ignored) {}

            // Reuse the 'registered' column for display counts:
            // - REGISTERING: registered count
            // - RUNNING/COUNTDOWN: racers involved in the race instance
            // - READY/OFF: 0
            int displayCount = switch (status) {
                case REGISTERING -> regs;
                case RUNNING, COUNTDOWN -> involved;
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
            } catch (Throwable ignored) {}

            List<Location> cl = java.util.Collections.emptyList();
            try {
                if (rm != null && rm.getTrackConfig() != null) {
                    List<Location> got = rm.getTrackConfig().getCenterline();
                    if (got != null) cl = got;
                }
            } catch (Throwable ignored) {}

            out.add(new TrackInfo(tn, status, displayCount, max, bestMs, holder, cl, countdownSec));
        }
        return out;
    }

    private static TrackInfo pickFocusedTrack(List<TrackInfo> infos) {
        if (infos == null || infos.isEmpty()) return null;
        for (TrackInfo i : infos) if (i != null && i.status == TrackStatus.RUNNING) return i;
        for (TrackInfo i : infos) if (i != null && i.status == TrackStatus.COUNTDOWN) return i;
        for (TrackInfo i : infos) if (i != null && i.status == TrackStatus.REGISTERING) return i;
        for (TrackInfo i : infos) if (i != null) return i;
        return null;
    }

    private static String statusLabel(TrackStatus st, int regs, int max, int countdownSeconds) {
        if (st == null) st = TrackStatus.OFF;
        return switch (st) {
            case RUNNING -> "Đang chạy";
            // Countdown seconds are shown only as the large centered overlay on the focused minimap.
            case COUNTDOWN -> "Đếm ngược";
            case REGISTERING -> "Đang đăng ký " + regs + "/" + max;
            case READY -> "Sẵn sàng";
            case OFF -> "Tắt";
        };
    }

    private static String recordLabel(long ms, String holderName) {
        if (ms <= 0L) return ICON_CLOCK + " Kỷ lục: -";
        String t = Time.formatStopwatchMillis(ms);
        String hn = (holderName == null ? "" : holderName.trim());
        if (hn.isEmpty()) return ICON_CLOCK + " Kỷ lục: " + t;
        return ICON_CLOCK + " Kỷ lục: " + t + " - " + hn;
    }

    private int getTrackPageSeconds() {
        try {
            if (plugin != null) {
                int v = plugin.getConfig().getInt("mapengine.lobby-board.page-seconds", 6);
                return Math.max(2, v);
            }
        } catch (Throwable ignored) {}
        return 6;
    }

    private static void drawMiniMap(Graphics2D g, List<Location> centerline, int x, int y, int w, int h, Color accent, Color borderC, Color textDim, Font smallFont, float strokePx) {
        if (g == null) return;
        if (w <= 2 || h <= 2) return;

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
            if (p == null) continue;
            double px = p.getX();
            double pz = p.getZ();
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (pz < minZ) minZ = pz;
            if (pz > maxZ) maxZ = pz;
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
            if (p == null) continue;
            int px = (int) Math.round(ox + (p.getX() - minX) * s);
            int py = (int) Math.round(oz + (p.getZ() - minZ) * s);
            if (px == lastPx && py == lastPy) continue;
            pts.add(new java.awt.Point(px, py));
            lastPx = px;
            lastPy = py;
        }
        if (pts.size() < 2) return;

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

    private record MiniDot(double x, double z, Color color) {}

    private List<MiniDot> collectRacerDots(RaceManager rm) {
        if (rm == null || !rm.isRunning()) return java.util.Collections.emptyList();

        List<MiniDot> dots = new java.util.ArrayList<>();
        List<UUID> order;
        try { order = rm.getLiveOrder(); }
        catch (Throwable t) { order = java.util.Collections.emptyList(); }

        for (UUID id : order) {
            if (id == null) continue;

            try {
                var st = rm.getParticipantState(id);
                if (st != null && st.finished) continue;
            } catch (Throwable ignored) {}

            Player p;
            try { p = Bukkit.getPlayer(id); }
            catch (Throwable t) { p = null; }
            if (p == null || !p.isOnline()) continue;

            Location loc = null;
            try {
                var v = p.getVehicle();
                if (v != null) loc = v.getLocation();
            } catch (Throwable ignored) { loc = null; }
            if (loc == null) {
                try { loc = p.getLocation(); } catch (Throwable ignored) { loc = null; }
            }
            if (loc == null) continue;

            Color c = new Color(0xEE, 0xEE, 0xEE);
            try {
                if (profileManager != null) {
                    var prof = profileManager.get(id);
                    if (prof != null && prof.color != null) {
                        c = ColorTranslator.awtColor(prof.color);
                    }
                }
            } catch (Throwable ignored) {}

            dots.add(new MiniDot(loc.getX(), loc.getZ(), c));
        }

        return dots;
    }

    private static void drawMiniMapWithDots(Graphics2D g, List<Location> centerline, List<MiniDot> dots,
                                           int x, int y, int w, int h,
                                           Color accent, Color borderC, Color textDim, Font smallFont, float strokePx) {
        if (g == null) return;
        if (w <= 2 || h <= 2) return;

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
            if (p == null) continue;
            double px = p.getX();
            double pz = p.getZ();
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (pz < minZ) minZ = pz;
            if (pz > maxZ) maxZ = pz;
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ) || !Double.isFinite(maxZ)) {
            return;
        }

        double dx = Math.max(1.0e-6, maxX - minX);
        double dz = Math.max(1.0e-6, maxZ - minZ);

        int innerW = Math.max(1, w - margin * 2);
        int innerH = Math.max(1, h - margin * 2);

        // Maintain aspect ratio
        double sx = innerW / dx;
        double sz = innerH / dz;
        double s = Math.min(sx, sz);

        int drawW = Math.max(1, (int) Math.round(dx * s));
        int drawH = Math.max(1, (int) Math.round(dz * s));
        int ox = x + margin + (innerW - drawW) / 2;
        int oy = y + margin + (innerH - drawH) / 2;

        // Project centerline points into pixel grid
        java.util.List<java.awt.Point> pts = new java.util.ArrayList<>(centerline.size());
        int lastPx = Integer.MIN_VALUE;
        int lastPy = Integer.MIN_VALUE;
        for (Location p : centerline) {
            if (p == null) continue;
            double px = p.getX();
            double pz = p.getZ();
            int ix = (int) Math.round((px - minX) * s);
            int iz = (int) Math.round((pz - minZ) * s);
            int rx = ox + clamp(ix, 0, drawW);
            int ry = oy + clamp(iz, 0, drawH);
            if (rx == lastPx && ry == lastPy) continue;
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
                if (d == null) continue;
                double px = d.x;
                double pz = d.z;
                if (!Double.isFinite(px) || !Double.isFinite(pz)) continue;
                int ix = (int) Math.round((px - minX) * s);
                int iz = (int) Math.round((pz - minZ) * s);
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
        if (s == null) return false;
        int close = s.indexOf(')');
        if (close <= 0) return false;
        String head = s.substring(0, close).trim();
        if (head.isEmpty()) return false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private BufferedImage renderImage(int widthPx, int heightPx) {
        int w = Math.max(128, widthPx);
        int h = Math.max(128, heightPx);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            // Crisp-ish UI.
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            // Ensure we tried loading the board font (in case render happens before tick/start).
            tryLoadBoardFont();

            // Broadcast-like scaling: drive sizes from pixel height.
            // (We use FontMetrics for row height to prevent overlap.)
            // Requested: overall UI size increase ~25%.
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

            java.awt.FontMetrics fmTitle = g.getFontMetrics(titleFont);
            java.awt.FontMetrics fmHeader = g.getFontMetrics(headerFont);
            java.awt.FontMetrics fmBody = g.getFontMetrics(bodyFont);
            java.awt.FontMetrics fmSmall = g.getFontMetrics(smallFont);

            int rowH = fmBody.getHeight() + Math.max(2, (int) Math.round(bodySize * 0.12));

            int trackInnerPad = trackRowInnerPad(bodyFont);

            // Background
            g.setColor(bg0);
            g.fillRect(0, 0, w, h);

            // Common layout elements
            int titleBarH = Math.max(fmTitle.getHeight() + Math.max(8, border * 3), (int) Math.round(bodySize * 2.2));

            // Reserve a footer box inside the inner border so footer text never overlaps the border.
            int footerPadV = Math.max(6, (int) Math.round(bodySize * 0.35));
            int footerBoxH = fmSmall.getHeight() + footerPadV;
            int footerTop = h - inset - footerBoxH;
            int contentBottom = footerTop - Math.max(8, (int) Math.round(bodySize * 0.50));

            // Border
            g.setColor(borderC);
            g.setStroke(new BasicStroke(border));
            g.drawRect(inset, inset, w - (inset * 2 + 1), h - (inset * 2 + 1));

            // Inner edge of the border stroke (used to keep filled bars from painting under the border,
            // which can look uneven at the left/right edges due to pixel snapping).
            int borderInnerPad = Math.max(1, (border + 1) / 2);

            // Title bar
            int titleBarX = inset;
            int titleBarY = inset;
            int titleBarW = w - (titleBarX * 2);
            g.setColor(panel);
            g.fillRect(titleBarX, titleBarY, titleBarW, titleBarH);
            // Accent stripe
            g.setColor(accent);
            g.fillRect(titleBarX, titleBarY, titleBarW, Math.max(3, border + 1));

            // Center title precisely within title bar
            g.setFont(titleFont);
            g.setColor(text);
            String sub = "BẢNG THÔNG TIN SẢNH";
            int subW = fmTitle.stringWidth(sub);
            int subX = titleBarX + Math.max(pad, (titleBarW - subW) / 2);
            int subY = titleBarY + (titleBarH + fmTitle.getAscent() - fmTitle.getDescent()) / 2;
            drawStringWithFallback(g, sub, subX, subY, titleFont, monoMatch(titleFont));

            // Layout
            int top = titleBarY + titleBarH + Math.max(10, (int) Math.round(bodySize * 0.9));
            int mid = w / 2;
            int leftX = pad;
            int rightX = mid + pad;
            int colW = (w / 2) - (pad * 2);

            // Header row (two columns)
            int headerRowH = Math.max(rowH, fmHeader.getHeight() + Math.max(6, border * 2));
            g.setColor(panel2);
            int headerBarX = titleBarX + borderInnerPad;
            int headerBarW = Math.max(0, titleBarW - (borderInnerPad * 2));
            g.fillRect(headerBarX, top, headerBarW, headerRowH);
            g.setColor(accent);
            int headerStripeH = Math.min(headerRowH, Math.max(3, border + 1));
            g.fillRect(headerBarX, top + headerRowH - headerStripeH, headerBarW, headerStripeH);

            g.setFont(headerFont);
            g.setColor(accent);
            int hy = top + (headerRowH + fmHeader.getAscent() - fmHeader.getDescent()) / 2;
            g.drawString("ĐƯỜNG ĐUA", leftX, hy);
            g.drawString("BẢNG XẾP HẠNG", rightX, hy);

            // Panels
            int contentTop = top + headerRowH + Math.max(10, (int) Math.round(bodySize * 0.6));
            int panelH = Math.max(0, contentBottom - contentTop);
            g.setColor(panel);
            g.fillRect(titleBarX, contentTop - 6, titleBarW, panelH + 12);

            // Divider (content only) — do not draw through the header.
            g.setColor(borderC);
            g.setStroke(new BasicStroke(Math.max(1, border - 1)));
            int panelTopY = contentTop - 6;
            int panelBottomY = panelTopY + panelH + 12;
            g.drawLine(mid, panelTopY, mid, panelBottomY);

            // Content
            // Left: track list with details
            int y = contentTop;
            int blockGap = Math.max(6, rowH / 3);
            g.setFont(bodyFont);

            if (tracks == null || tracks.isEmpty()) {
                g.setColor(text);
                drawTrimmed(g, "(Chưa có đường đua)", leftX, y + fmBody.getAscent(), colW);
                g.setFont(smallFont);
                g.setColor(textDim);
                drawTrimmed(g, "Dùng /boatracing setup để tạo.", leftX, y + rowH + fmSmall.getAscent(), colW);
            } else {
                // Airport-board style paging for long track lists
                int blockPadV = Math.max(4, (int) Math.round(bodySize * 0.22));
                int estSmallLines = 2;
                int blockH = rowH + (fmSmall.getHeight() * estSmallLines) + blockPadV;
                int hintLines = 2;
                int hintH = hintLines * fmSmall.getHeight() + 10;
                int availableH = Math.max(0, (contentBottom - contentTop) - hintH);
                int perPage = Math.max(1, availableH / Math.max(1, blockH + blockGap));
                int total = tracks.size();
                int totalPages = Math.max(1, (int) Math.ceil(total / (double) perPage));
                long now = System.currentTimeMillis();
                long pageMs = getTrackPageSeconds() * 1000L;
                int pageIndex = (totalPages <= 1) ? 0 : (int) ((now / Math.max(1L, pageMs)) % totalPages);
                int startIndex = pageIndex * perPage;
                int endIndex = Math.min(total, startIndex + perPage);

                int idx = 0;
                for (int i = startIndex; i < endIndex; i++) {
                    TrackInfo ti = tracks.get(i);
                    if (ti == null) continue;

                    int smallLines = (ti.status == TrackStatus.RUNNING || ti.status == TrackStatus.COUNTDOWN) ? 1 : 2;
                    int thisBlockH = rowH + (fmSmall.getHeight() * smallLines) + blockPadV;

                    int blockTop = y;
                    if (blockTop + thisBlockH > contentBottom) break;

                    // Stronger status-tinted block background (visible on map palette)
                    Color rowAccent = accentForStatus(ti.status);
                    Color rowTint = mix(new Color(0x10, 0x11, 0x13), rowAccent, 0.22);
                    if ((idx % 2) == 1) rowTint = mix(rowTint, new Color(0x00, 0x00, 0x00), 0.16);
                    g.setColor(rowTint);
                    g.fillRect(leftX - 6, blockTop, colW + 12, thisBlockH - 2);

                    // Bold accent stripe to ensure per-track color is obvious
                    g.setColor(rowAccent);
                    g.fillRect(leftX - 6, blockTop, Math.max(4, border + 2), thisBlockH - 2);

                    // Row outline
                    g.setColor(mix(borderC, rowAccent, 0.10));
                    g.setStroke(new BasicStroke(Math.max(1, border - 1)));
                    g.drawRect(leftX - 6, blockTop, colW + 11, thisBlockH - 3);

                    // Mini-map viewport inside the track row (right side of the row)
                    // RUNNING tracks should be minimal: hide the per-row minimap.
                    boolean minimalRunningRow = (ti.status == TrackStatus.RUNNING);

                    int miniW = 0;
                    if (!minimalRunningRow) {
                        // Make the minimap thumbnail fill the row height (no vertical letterboxing).
                        int mapPad = Math.max(3, border + 1);
                        int availH = thisBlockH - (mapPad * 2);
                        if (availH < 24) {
                            availH = Math.max(24, thisBlockH - 2);
                            mapPad = Math.max(1, (thisBlockH - availH) / 2);
                        }

                        // Use full available height for the minimap box.
                        int maxMiniW = clamp((int) Math.round(colW * 0.40), 72, Math.max(72, colW - 140));
                        int miniH = Math.max(24, availH);
                        int desiredW = (int) Math.round(miniH * 4.0 / 3.0);
                        miniW = Math.min(maxMiniW, Math.max(24, desiredW));

                        int miniX = leftX + colW - miniW;
                        int miniY = blockTop + mapPad;
                        drawMiniMap(g, ti.centerline, miniX, miniY, miniW, miniH, rowAccent, borderC, textDim, smallFont, minimapStrokeFromBorder(border));
                    }

                    int textW = Math.max(0, colW - (minimalRunningRow ? 0 : miniW) - 10);

                    // Line 1: name + status
                    String line1 = "● " + ti.trackName + "  [" + statusLabel(ti.status, ti.registered, ti.maxRacers, ti.countdownSeconds) + "]";
                    g.setFont(bodyFont);
                    drawTrackRow(g, line1, leftX, blockTop + fmBody.getAscent(), textW, trackInnerPad, bodyFont, accent, text, textDim);

                        // Line 2/3: compact info. RUNNING tracks are rendered in a minimal form.
                        g.setFont(smallFont);
                        g.setColor(textDim);
                        if (ti.status == TrackStatus.RUNNING) {
                            drawTrimmed(g, "Đang đua: " + Math.max(0, ti.registered) + " người", leftX + 18 + trackInnerPad, blockTop + rowH + fmSmall.getAscent(), Math.max(0, textW - (18 + trackInnerPad)));
                        } else if (ti.status == TrackStatus.COUNTDOWN) {
                            drawTrimmed(g, "Người chơi: " + Math.max(0, ti.registered) + " người", leftX + 18 + trackInnerPad, blockTop + rowH + fmSmall.getAscent(), Math.max(0, textW - (18 + trackInnerPad)));
                        } else {
                            // Line 2/3: compact info for idle/registering.
                            // REGISTERING: avoid duplicating max racers (already shown in status); show remaining waiting time instead.
                            if (ti.status == TrackStatus.REGISTERING) {
                                String remain = (ti.countdownSeconds > 0)
                                        ? ("⌛ Còn lại: " + dev.belikhun.boatracing.util.Time.formatCountdownSeconds(ti.countdownSeconds))
                                        : "⌛ Chờ người chơi...";
                                drawTrimmed(g, remain, leftX + 18 + trackInnerPad, blockTop + rowH + fmSmall.getAscent(), Math.max(0, textW - (18 + trackInnerPad)));
                                drawTrimmed(g, recordLabel(ti.recordMillis, ti.recordHolderName), leftX + 18 + trackInnerPad, blockTop + rowH + fmSmall.getHeight() + fmSmall.getAscent(), Math.max(0, textW - (18 + trackInnerPad)));
                            } else {
                                // IDLE: show max racers + record.
                                drawTrimmed(g, "Tối đa: " + Math.max(0, ti.maxRacers) + " người", leftX + 18 + trackInnerPad, blockTop + rowH + fmSmall.getAscent(), Math.max(0, textW - (18 + trackInnerPad)));
                                drawTrimmed(g, recordLabel(ti.recordMillis, ti.recordHolderName), leftX + 18 + trackInnerPad, blockTop + rowH + fmSmall.getHeight() + fmSmall.getAscent(), Math.max(0, textW - (18 + trackInnerPad)));
                            }
                        }

                    y += thisBlockH + blockGap;
                    idx++;
                }

                // Hint
                if (y + (fmSmall.getHeight() * 2) + 4 < contentBottom) {
                    g.setFont(smallFont);
                    g.setColor(textDim);
                    drawTrimmed(g, "Dùng: /boatracing race join <tên>", leftX, y + fmSmall.getAscent(), colW);

                    // Page indicator
                    int blockH2 = rowH + (fmSmall.getHeight() * 2) + blockPadV;
                    int hintLines2 = 2;
                    int hintH2 = hintLines2 * fmSmall.getHeight() + 10;
                    int availableH2 = Math.max(0, (contentBottom - contentTop) - hintH2);
                    int perPage2 = Math.max(1, availableH2 / Math.max(1, blockH2 + blockGap));
                    int total2 = tracks.size();
                    int totalPages2 = Math.max(1, (int) Math.ceil(total2 / (double) perPage2));
                    long now2 = System.currentTimeMillis();
                    long pageMs2 = getTrackPageSeconds() * 1000L;
                    int pageIndex2 = (totalPages2 <= 1) ? 0 : (int) ((now2 / Math.max(1L, pageMs2)) % totalPages2);
                    String pageLabel = "Trang " + (pageIndex2 + 1) + "/" + totalPages2;
                    drawTrimmed(g, pageLabel, leftX, y + fmSmall.getHeight() + fmSmall.getAscent(), colW);
                }
            }

            // Right: ongoing races list
            int rightPanelX = rightX - 6;
            int rightPanelY = contentTop;
            int rightPanelW = colW + 12;
            int rightPanelH = Math.max(0, contentBottom - contentTop);
            g.setColor(panel2);
            g.fillRect(rightPanelX, rightPanelY, rightPanelW, rightPanelH);

            // Small inner border for the map panel
            g.setColor(borderC);
            g.setStroke(new BasicStroke(Math.max(1, border - 1)));
            g.drawRect(rightPanelX, rightPanelY, rightPanelW - 1, rightPanelH - 1);

            // Keep the focused minimap tight to the top of the right panel (screenshot polish).
            // NOTE: Keep the inset consistent with the left/right inset (6px) to avoid a visible top gap.
            int listTop = rightPanelY + Math.max(6, border);
            int ry = listTop;

            // Minimap of the focused running track (shown above the live ranking list).
            try {
                TrackInfo mapTrack = (focused != null && (focused.status == TrackStatus.RUNNING || focused.status == TrackStatus.COUNTDOWN)) ? focused : null;
                RaceManager mapRm = null;
                if (mapTrack != null) {
                    try { mapRm = raceService != null ? raceService.getOrCreate(mapTrack.trackName) : null; }
                    catch (Throwable ignored) { mapRm = null; }
                    if (mapRm != null && !(mapRm.isRunning() || mapRm.isAnyCountdownActive())) mapRm = null;
                }

                // Fallback: pick the first live race (prefer RUNNING, else COUNTDOWN).
                if (mapRm == null && raceService != null) {
                    RaceManager running = null;
                    RaceManager countdown = null;
                    String runningName = null;
                    String countdownName = null;

                    for (RaceManager rm : raceService.allRaces()) {
                        if (rm == null) continue;
                        String tn = null;
                        try { tn = rm.getTrackConfig() != null ? rm.getTrackConfig().getCurrentName() : null; } catch (Throwable ignored) { tn = null; }
                        if (tn == null || tn.isBlank()) tn = "(không rõ)";

                        if (running == null && rm.isRunning()) {
                            running = rm;
                            runningName = tn;
                        }
                        if (countdown == null && rm.isAnyCountdownActive()) {
                            countdown = rm;
                            countdownName = tn;
                        }

                        if (running != null) break;
                    }

                    if (running != null) {
                        mapRm = running;
                        String tn = runningName;
                        TrackInfo found = null;
                        if (tracks != null) {
                            for (TrackInfo ti : tracks) {
                                if (ti != null && ti.trackName != null && ti.trackName.equalsIgnoreCase(tn)) { found = ti; break; }
                            }
                        }
                        if (found != null) mapTrack = found;
                        else {
                            List<Location> cl = java.util.Collections.emptyList();
                            try {
                                if (running.getTrackConfig() != null) {
                                    List<Location> got = running.getTrackConfig().getCenterline();
                                    if (got != null) cl = got;
                                }
                            } catch (Throwable ignored) {}
                            mapTrack = new TrackInfo(tn, statusOf(running), 0, 0, 0L, "", cl, 0);
                        }
                    } else if (countdown != null) {
                        mapRm = countdown;
                        String tn = countdownName;
                        TrackInfo found = null;
                        if (tracks != null) {
                            for (TrackInfo ti : tracks) {
                                if (ti != null && ti.trackName != null && ti.trackName.equalsIgnoreCase(tn)) { found = ti; break; }
                            }
                        }
                        if (found != null) mapTrack = found;
                        else {
                            List<Location> cl = java.util.Collections.emptyList();
                            try {
                                if (countdown.getTrackConfig() != null) {
                                    List<Location> got = countdown.getTrackConfig().getCenterline();
                                    if (got != null) cl = got;
                                }
                            } catch (Throwable ignored) {}
                            int cd = 0;
                            try { cd = Math.max(0, countdown.getCountdownRemainingSeconds()); } catch (Throwable ignored) { cd = 0; }
                            mapTrack = new TrackInfo(tn, statusOf(countdown), 0, 0, 0L, "", cl, cd);
                        }
                    }
                }

                if (mapTrack != null && mapRm != null && mapTrack.centerline != null && mapTrack.centerline.size() >= 2) {
                    int mapX = rightX;
                    int mapW = colW;
                    int idealMapH = (int) Math.round(mapW * 0.62);
                    // Scale up the focused minimap on large boards, while reserving space for rankings.
                    int maxMapH = Math.max(140, (int) Math.round(rightPanelH * 0.42));
                    int mapH = clamp(idealMapH, 70, maxMapH);

                    int reserveForList = Math.max(60, rowH * 6);
                    mapH = Math.min(mapH, Math.max(0, rightPanelH - reserveForList));

                    // Reduce extra empty space between the minimap and the list.
                    int gapAfterMap = Math.max(6, (int) Math.round(bodySize * 0.25));
                    if (mapH >= 50 && (listTop + mapH + gapAfterMap) < contentBottom) {
                        List<MiniDot> dots = collectRacerDots(mapRm);
                        drawMiniMapWithDots(g, mapTrack.centerline, dots, mapX, listTop, mapW, mapH, accent, borderC, textDim, smallFont, minimapStrokeFromBorder(border));

                        // COUNTDOWN: draw a very large number overlay at the center of the track-in-progress area.
                        if (mapTrack.status == TrackStatus.COUNTDOWN) {
                            int sec = Math.max(0, mapTrack.countdownSeconds);
                            try { sec = Math.max(0, mapRm.getCountdownRemainingSeconds()); } catch (Throwable ignored) {}

                            String s = String.valueOf(sec);
                            int cx = mapX + (mapW / 2);
                            int cy = listTop + (mapH / 2);

                            // Start big and shrink-to-fit.
                            int px = clamp((int) Math.round(mapH * 0.75), 28, 260);
                            Font f = bodyFont.deriveFont(Font.BOLD, (float) px);
                            g.setFont(f);
                            java.awt.FontMetrics fm = g.getFontMetrics();
                            while (px > 18 && (fm.stringWidth(s) > (int) Math.round(mapW * 0.85)
                                    || fm.getAscent() > (int) Math.round(mapH * 0.85))) {
                                px = (int) Math.round(px * 0.9);
                                f = bodyFont.deriveFont(Font.BOLD, (float) px);
                                g.setFont(f);
                                fm = g.getFontMetrics();
                            }

                            int tx = cx - (fm.stringWidth(s) / 2);
                            int ty = cy + ((fm.getAscent() - fm.getDescent()) / 2);

                            // Shadow for readability
                            int shadowOff = Math.max(2, (int) Math.round(border * 0.45));
                            g.setColor(new Color(0, 0, 0, 170));
                            g.drawString(s, tx + shadowOff, ty + shadowOff);
                            g.drawString(s, tx - shadowOff, ty + shadowOff);
                            g.drawString(s, tx + shadowOff, ty - shadowOff);
                            g.drawString(s, tx - shadowOff, ty - shadowOff);

                            // Main number
                            g.setColor(accent);
                            g.drawString(s, tx, ty);

                            // Restore font for subsequent UI.
                            g.setFont(bodyFont);
                        }

                        ry = listTop + mapH + gapAfterMap;
                    }
                }
            } catch (Throwable ignored) {}

            java.util.List<String> rightLines = buildRightLines();
            for (String line : rightLines) {
                if (ry + fmBody.getHeight() > contentBottom) break;

                if (line == null || line.isBlank()) {
                    ry += Math.max(6, fmSmall.getHeight() / 2);
                    continue;
                }

                g.setFont(bodyFont);
                if (line.startsWith("●")) {
                    // Track header
                    g.setColor(accent);
                    drawTrackRow(g, line, rightX, ry + fmBody.getAscent(), colW, 0, bodyFont, accent, text, textDim);
                    ry += fmBody.getHeight();
                    continue;
                }

                if (isRankingLine(line)) {
                    g.setColor(text);
                    drawRankingRow(g, line, rightX, ry + fmBody.getAscent(), colW, bodyFont, accent, text, textDim);
                    ry += fmBody.getHeight();
                    continue;
                }

                g.setFont(smallFont);
                g.setColor(textDim);
                drawTrimmed(g, line, rightX, ry + fmSmall.getAscent(), colW);
                ry += fmSmall.getHeight();
            }

            // Footer hint
            g.setFont(smallFont);
            g.setColor(textDim);
            int fy = footerTop + (footerBoxH + fmSmall.getAscent() - fmSmall.getDescent()) / 2;
            fy = Math.max(fmSmall.getAscent() + inset, fy);
            // Left: visibility hint
            String footerHint = ICON_INFO + " Chỉ hiển thị khi bạn ở sảnh và gần bảng.";
            drawStringWithFallback(g, footerHint, titleBarX + pad, fy, smallFont, monoMatch(smallFont));

            // Right: server current time
            try {
                java.time.LocalTime now = java.time.LocalTime.now();
                String clock = ICON_CLOCK + " " + now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                int clockW = stringWidthWithFallback(g, clock, smallFont, monoMatch(smallFont));
                int rightEdge = titleBarX + (w - (titleBarX * 2)) - pad;
                int cx = Math.max(titleBarX + pad, rightEdge - clockW);
                drawStringWithFallback(g, clock, cx, fy, smallFont, monoMatch(smallFont));
            } catch (Throwable ignored) {}

        } finally {
            g.dispose();
        }
        return image;
    }

    private static void drawTrackRow(Graphics2D g, String line, int x, int y, int maxWidth, int innerPad, Font bodyFont,
                                     Color accent, Color text, Color textDim) {
        if (g == null) return;
        if (line == null) line = "";

        // Add a bit more breathing room from the left edge of the row background.
        int pad = Math.max(0, innerPad);
        x += pad;
        maxWidth = Math.max(0, maxWidth - pad);

        // Expected: "● <name>  [<state...>]"
        String s = line;
        if (s.startsWith("●")) s = s.substring(1).trim();

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
        // If the bullet can't be rendered by the board font, it will switch to the fallback font;
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
        if (bl.contains("đang chạy")) stC = new Color(0x56, 0xF2, 0x7A); // green-ish
        else if (bl.contains("đang đăng ký")) stC = accent;

        // Draw bracket: '[' + state + ']'
        g.setColor(textDim);
        // Keep bracket within maxWidth
        int maxBr = Math.max(0, maxWidth - (brX - x));
        if (maxBr <= 0) return;

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
        if (g == null) return;
        if (line == null) line = "";
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
        // Example: "1) Racer  V1/3  CP2/5  47%"
        String name = full;
        String meta = null;
        int sep = full.indexOf("  ");
        if (sep > 0) {
            name = full.substring(0, sep).trim();
            meta = full.substring(sep).trim();
            if (meta != null && meta.isBlank()) meta = null;
        }

        int pos;
        try { pos = Integer.parseInt(posStr); } catch (Throwable ignored) { pos = 0; }

        Color posC = textDim;
        if (pos == 1) posC = accent;
        else if (pos == 2) posC = new Color(0xD8, 0xD8, 0xD8);
        else if (pos == 3) posC = new Color(0xD0, 0x95, 0x5A);

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

        // Reserve a little room for the racer name so meta doesn't consume the whole row.
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

    // ===================== Legacy color rendering (for lobby board ranking) =====================
    // Supports Minecraft legacy formatting codes using either '&' or '§'.
    // We implement color + bold/italic and ignore other formatting codes.

    private static int legacyRenderedWidthWithFallback(Graphics2D g, String s, Font baseFont, Font fallbackFont) {
        if (g == null) return 0;
        if (s == null || s.isEmpty()) return 0;
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
            try { useBase = base.deriveFont(style); }
            catch (Throwable ignored) { useBase = base; }
            Font useFallback = (fallbackFont != null ? fallbackFont : monoMatch(useBase));
            // Ensure fallback matches style/size.
            try { useFallback = useFallback.deriveFont(style, (float) useBase.getSize()); } catch (Throwable ignored) {}

            boolean canPrimary;
            try { canPrimary = useBase.canDisplay(cp); }
            catch (Throwable ignored) { canPrimary = true; }
            Font use = canPrimary ? useBase : useFallback;
            try {
                w += g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp)));
            } catch (Throwable ignored) {}
            i += len;
        }
        return w;
    }

    private static String trimLegacyToWidthWithFallback(Graphics2D g, String s, int maxWidth, Font baseFont, Font fallbackFont) {
        if (g == null) return "";
        if (s == null || s.isEmpty()) return "";
        if (maxWidth <= 0) return "";

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
            try { useBase = base.deriveFont(style); }
            catch (Throwable ignored) { useBase = base; }
            Font useFallback = (fallbackFont != null ? fallbackFont : monoMatch(useBase));
            try { useFallback = useFallback.deriveFont(style, (float) useBase.getSize()); } catch (Throwable ignored) {}

            boolean canPrimary;
            try { canPrimary = useBase.canDisplay(cp); }
            catch (Throwable ignored) { canPrimary = true; }
            Font use = canPrimary ? useBase : useFallback;
            int cw = 0;
            try { cw = g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp))); }
            catch (Throwable ignored) { cw = 0; }

            if (w + cw > maxWidth) break;
            w += cw;
            i += len;
            cutIndex = i;
        }

        if (cutIndex <= 0) return "";
        return s.substring(0, Math.min(cutIndex, s.length()));
    }

    private static int drawLegacyRunWithFallback(Graphics2D g, String text, int x, int y, Font baseFont, Font fallbackBase, Color color, int style) {
        if (g == null) return 0;
        if (text == null || text.isEmpty()) return 0;

        Font base = (baseFont != null ? baseFont : g.getFont());
        Font fallback = (fallbackBase != null ? fallbackBase : monoMatch(base));

        Font runFont;
        try { runFont = base.deriveFont(style); }
        catch (Throwable ignored) { runFont = base; }

        Font runFallback = fallback;
        try { runFallback = runFallback.deriveFont(style, (float) runFont.getSize()); }
        catch (Throwable ignored) {}

        try { g.setColor(color != null ? color : Color.WHITE); } catch (Throwable ignored) {}

        try { drawStringWithFallback(g, text, x, y, runFont, runFallback); }
        catch (Throwable ignored) {}

        try { return stringWidthWithFallback(g, text, runFont, runFallback); }
        catch (Throwable ignored) { return 0; }
    }

    private static int flushLegacyRunWithFallback(Graphics2D g, StringBuilder run, int x, int y, Font baseFont, Font fallbackBase, Color color, int style) {
        if (run == null || run.isEmpty()) return 0;
        int w = drawLegacyRunWithFallback(g, run.toString(), x, y, baseFont, fallbackBase, color, style);
        try { run.setLength(0); } catch (Throwable ignored) {}
        return w;
    }

    private static void drawLegacyStringWithFallback(Graphics2D g, String s, int x, int y, Font baseFont, Color defaultColor) {
        if (g == null) return;
        if (s == null || s.isEmpty()) return;

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
                    if (!bold) styleChanged = true;
                    bold = true;
                } else if (lc == 'o') {
                    if (!italic) styleChanged = true;
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
        if (f == null) return new Font(Font.MONOSPACED, Font.PLAIN, 12);
        return new Font(Font.MONOSPACED, f.getStyle(), f.getSize());
    }

    private static int stringWidthWithFallback(Graphics2D g, String s, Font primary, Font fallback) {
        if (g == null) return 0;
        if (s == null || s.isEmpty()) return 0;
        Font p = (primary != null ? primary : g.getFont());
        Font f = (fallback != null ? fallback : monoMatch(p));

        int w = 0;
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            boolean canPrimary;
            try { canPrimary = p != null && p.canDisplay(cp); }
            catch (Throwable ignored) { canPrimary = true; }
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
        if (g == null) return;
        if (s == null || s.isEmpty()) return;
        Font p = (primary != null ? primary : g.getFont());
        Font f = (fallback != null ? fallback : monoMatch(p));

        int cx = x;
        StringBuilder run = new StringBuilder();
        Font runFont = null;

        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);

            boolean canPrimary;
            try { canPrimary = p != null && p.canDisplay(cp); }
            catch (Throwable ignored) { canPrimary = true; }

            Font use = canPrimary ? p : f;
            if (runFont == null) runFont = use;

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
        if (g == null) return "";
        if (s == null || s.isEmpty()) return "";
        if (maxWidth <= 0) return "";

        String out = s;
        while (!out.isEmpty() && stringWidthWithFallback(g, out, primary, fallback) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private List<String> buildLeftLines() {
        List<String> out = new ArrayList<>();
        if (trackLibrary == null) {
            out.add("❌ Không có dữ liệu đường đua.");
            return out;
        }

        List<String> tracks = new ArrayList<>();
        try { tracks.addAll(trackLibrary.list()); } catch (Throwable ignored) {}
        tracks.sort(String.CASE_INSENSITIVE_ORDER);

        if (tracks.isEmpty()) {
            out.add("(Chưa có đường đua)");
            out.add("Dùng /boatracing setup để tạo.");
            return out;
        }

        for (String tn : tracks) {
            if (tn == null || tn.isBlank()) continue;

            String state = "Tắt";
            int regs = 0;
            int max = 0;
            try {
                RaceManager rm = raceService != null ? raceService.getOrCreate(tn) : null;
                if (rm != null) {
                    if (rm.isRunning()) state = "Đang chạy";
                    else if (rm.isRegistering()) state = "Đang đăng ký";
                    else state = "Sẵn sàng";
                    regs = rm.getRegistered().size();
                    max = rm.getTrackConfig() != null ? rm.getTrackConfig().getStarts().size() : 0;
                }
            } catch (Throwable ignored) {}

            String line;
            if ("Đang đăng ký".equals(state)) line = "● " + tn + "  [" + state + " " + regs + "/" + max + "]";
            else line = "● " + tn + "  [" + state + "]";
            out.add(line);
        }

        // Keep it scannable
        out.add("");
        out.add("Dùng: /boatracing race join <tên>");
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

        record Entry(String track, int pos, UUID id, String name, String meta) {}
        List<Entry> entries = new ArrayList<>();

        try {
            for (RaceManager rm : raceService.allRaces()) {
                if (rm == null) continue;
                String track = "(không rõ)";
                try {
                    String n = rm.getTrackConfig() != null ? rm.getTrackConfig().getCurrentName() : null;
                    if (n != null && !n.isBlank()) track = n;
                } catch (Throwable ignored) {}

                // Countdown (non-running) summary
                try {
                    if (!rm.isRunning() && rm.isAnyCountdownActive()) {
                        int racers = 0;
                        try { racers = rm.getInvolved().size(); } catch (Throwable ignored2) { racers = 0; }

                        countdownLines.add("● " + track + "  [Đếm ngược]");
                        countdownLines.add("Người chơi: " + racers);
                        countdownLines.add("");
                    }
                } catch (Throwable ignored) {}

                if (!rm.isRunning()) continue;

                List<UUID> order = rm.getLiveOrder();
                int limit = Math.min(5, order.size());
                for (int i = 0; i < limit; i++) {
                    UUID id = order.get(i);
                    if (id == null) continue;
                    var st = rm.getParticipantState(id);
                    if (st != null && st.finished) continue;
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
                        } catch (Throwable ignored2) { totalCp = 0; }

                        // Requested UX: use ✔ for checkpoint (yellow) and 🗘 for lap (green).
                        // Use &r to reset back to the default meta color (textDim) after each colored segment.
                        String lapPart = "&a🗘 " + lapCurrent + "/" + lapTotal + "&r";
                        String cpPart = (totalCp > 0)
                            ? ("&e✔ " + Math.min(passedCp, totalCp) + "/" + totalCp + "&r")
                            : ("&e✔ -&r");

                        double lapRatio = 0.0;
                        try { lapRatio = rm.getLapProgressRatio(id); } catch (Throwable ignored2) { lapRatio = 0.0; }
                        if (!Double.isFinite(lapRatio)) lapRatio = 0.0;
                        lapRatio = Math.max(0.0, Math.min(1.0, lapRatio));

                        double overall = ((st == null ? 0.0 : (double) Math.max(0, st.currentLap)) + lapRatio) / (double) lapTotal;
                        overall = Math.max(0.0, Math.min(1.0, overall));
                        int pct = (int) Math.round(overall * 100.0);

                        meta = lapPart + "  " + cpPart + "  " + pct + "%";
                    } catch (Throwable ignored2) { meta = ""; }

                    entries.add(new Entry(track, i + 1, id, name, meta));
                }
            }
        } catch (Throwable ignored) {}

        if (!countdownLines.isEmpty()) {
            // Trim possible trailing blank
            while (!countdownLines.isEmpty() && countdownLines.get(countdownLines.size() - 1).isBlank()) countdownLines.remove(countdownLines.size() - 1);
            out.addAll(countdownLines);
            // If there are also running races, we'll append them below.
            if (!entries.isEmpty()) out.add("");
        }

        if (entries.isEmpty()) {
            if (out.isEmpty()) out.add("(Chưa có cuộc đua nào đang chạy)");
            return out;
        }

        // Sort by track then position.
        entries.sort(Comparator.comparing(Entry::track, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Entry::pos));

        String currentTrack = null;
        int lines = 0;
        for (Entry e : entries) {
            if (lines >= 18) break;

            if (currentTrack == null || !currentTrack.equalsIgnoreCase(e.track)) {
                currentTrack = e.track;
                if (!out.isEmpty()) {
                    out.add("");
                    lines++;
                    if (lines >= 18) break;
                }
                out.add("● " + currentTrack.toUpperCase(Locale.ROOT));
                lines++;
                if (lines >= 18) break;
            }

            String racer = e.name;
            try {
                if (profileManager != null) {
                    // Keep legacy color codes so the ranking renderer can show colored racer display.
                    racer = profileManager.formatRacerLegacy(e.id, e.name);
                }
            } catch (Throwable ignored) {}
            String meta = e.meta;
            if (meta != null && !meta.isBlank()) out.add(e.pos + ") " + racer + "  " + meta);
            else out.add(e.pos + ") " + racer);
            lines++;
        }

        return out;
    }

    private static String nameOf(UUID id) {
        try {
            var op = Bukkit.getOfflinePlayer(id);
            if (op != null && op.getName() != null) return op.getName();
        } catch (Throwable ignored) {}
        return id.toString().substring(0, 8);
    }

    private static String stripLegacyColors(String s) {
        if (s == null || s.isEmpty()) return "";
        // Support both '&' and '§' formatting codes.
        // Includes color codes (0-9a-f) and formats (k-o, r).
        return s
                .replaceAll("(?i)[&§][0-9a-fk-or]", "")
                .trim();
    }

    private static void drawTrimmed(Graphics2D g, String line, int x, int y, int maxWidth) {
        if (g == null) return;
        if (line == null) line = "";
        String s = line;
        Font primary = g.getFont();
        Font fallback = monoMatch(primary);
        if (maxWidth <= 0) return;

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
        if (face == null) return null;
        return switch (face) {
            case NORTH, SOUTH, EAST, WEST -> face;
            default -> null;
        };
    }

    private static boolean isWithinRadiusChunks(Player p, BoardPlacement pl, int radiusChunks) {
        if (p == null || pl == null || !pl.isValid()) return false;
        World w = p.getWorld();
        if (w == null || pl.world == null || !w.getName().equals(pl.world)) return false;

        int pcx = p.getLocation().getBlockX() >> 4;
        int pcz = p.getLocation().getBlockZ() >> 4;
        int bcx = pl.centerBlockX() >> 4;
        int bcz = pl.centerBlockZ() >> 4;
        int dx = pcx - bcx;
        int dz = pcz - bcz;
        return (dx * dx + dz * dz) <= (radiusChunks * radiusChunks);
    }

    private static void tryInvoke(Object target, String method, Object... args) {
        if (target == null || method == null) return;
        try {
            Class<?>[] sig = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) sig[i] = args[i].getClass();

            // Try exact match first
            try {
                var m = target.getClass().getMethod(method, sig);
                m.invoke(target, args);
                return;
            } catch (Throwable ignored) {}

            // Fallback: match by name + arg count
            for (var m : target.getClass().getMethods()) {
                if (!m.getName().equals(method)) continue;
                if (m.getParameterCount() != args.length) continue;
                m.invoke(target, args);
                return;
            }
        } catch (Throwable ignored) {}
    }

    public static final class BoardPlacement {
        public final String world;
        public final BlockVector a;
        public final BlockVector b;
        public final BlockFace facing;

        private final int mapsWide;
        private final int mapsHigh;

        private BoardPlacement(String world, BlockVector a, BlockVector b, BlockFace facing, int mapsWide, int mapsHigh) {
            this.world = world;
            this.a = a;
            this.b = b;
            this.facing = facing;
            this.mapsWide = mapsWide;
            this.mapsHigh = mapsHigh;
        }

        public boolean isValid() {
            return world != null && !world.isBlank() && a != null && b != null && facing != null && mapsWide > 0 && mapsHigh > 0;
        }

        public int pixelWidth() { return mapsWide * 128; }
        public int pixelHeight() { return mapsHigh * 128; }

        public int centerBlockX() {
            return (a.getBlockX() + b.getBlockX()) / 2;
        }

        public int centerBlockZ() {
            return (a.getBlockZ() + b.getBlockZ()) / 2;
        }

        public static BoardPlacement load(ConfigurationSection sec) {
            if (sec == null) return null;
            ConfigurationSection p = sec.getConfigurationSection("placement");
            if (p == null) return null;
            try {
                String world = p.getString("world");
                String facingRaw = p.getString("facing", "NORTH");
                BlockFace facing;
                try { facing = BlockFace.valueOf(facingRaw.toUpperCase(Locale.ROOT)); }
                catch (Throwable ignored) { facing = BlockFace.NORTH; }

                int ax = p.getInt("a.x");
                int ay = p.getInt("a.y");
                int az = p.getInt("a.z");
                int bx = p.getInt("b.x");
                int by = p.getInt("b.y");
                int bz = p.getInt("b.z");

                BlockVector a = new BlockVector(ax, ay, az);
                BlockVector b = new BlockVector(bx, by, bz);

                int mapsWide = p.getInt("maps.wide", 1);
                int mapsHigh = p.getInt("maps.high", 1);
                if (mapsWide <= 0 || mapsHigh <= 0) {
                    int[] computed = computeMaps(a, b, facing);
                    mapsWide = computed[0];
                    mapsHigh = computed[1];
                }

                BoardPlacement out = new BoardPlacement(world, a, b, facing, mapsWide, mapsHigh);
                return out.isValid() ? out : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        public void save(ConfigurationSection sec) {
            if (sec == null) return;
            sec.set("world", world);
            sec.set("facing", facing.name());

            sec.set("a.x", a.getBlockX());
            sec.set("a.y", a.getBlockY());
            sec.set("a.z", a.getBlockZ());

            sec.set("b.x", b.getBlockX());
            sec.set("b.y", b.getBlockY());
            sec.set("b.z", b.getBlockZ());

            sec.set("maps.wide", mapsWide);
            sec.set("maps.high", mapsHigh);
        }

        public static BoardPlacement fromSelection(World w, org.bukkit.util.BoundingBox box, BlockFace facing) {
            if (w == null || box == null || facing == null) return null;

            BlockFace dir = normalizeCardinal(facing);
            if (dir == null) return null;

            int minX = (int) Math.floor(Math.min(box.getMinX(), box.getMaxX()));
            int maxX = (int) Math.floor(Math.max(box.getMinX(), box.getMaxX()));
            int minY = (int) Math.floor(Math.min(box.getMinY(), box.getMaxY()));
            int maxY = (int) Math.floor(Math.max(box.getMinY(), box.getMaxY()));
            int minZ = (int) Math.floor(Math.min(box.getMinZ(), box.getMaxZ()));
            int maxZ = (int) Math.floor(Math.max(box.getMinZ(), box.getMaxZ()));

            // MapEngine requires the two points build a 2D box.
            // We derive a flat plane based on the chosen facing:
            // - NORTH/SOUTH: constant Z (pick the nearer edge)
            // - EAST/WEST: constant X
            int ax, ay, az, bx, by, bz;
            if (dir == BlockFace.NORTH) {
                int z = minZ;
                ax = minX; ay = minY; az = z;
                bx = maxX; by = maxY; bz = z;
            } else if (dir == BlockFace.SOUTH) {
                int z = maxZ;
                ax = minX; ay = minY; az = z;
                bx = maxX; by = maxY; bz = z;
            } else if (dir == BlockFace.WEST) {
                int x = minX;
                ax = x; ay = minY; az = minZ;
                bx = x; by = maxY; bz = maxZ;
            } else { // EAST
                int x = maxX;
                ax = x; ay = minY; az = minZ;
                bx = x; by = maxY; bz = maxZ;
            }

            // IMPORTANT: If the selection is filled with blocks (e.g., black concrete screen),
            // placing the display ON the plane will embed it inside the blocks.
            // Push the plane 1 block outward in the facing direction so it sits in front of the surface.
            int offX = 0;
            int offZ = 0;
            if (dir == BlockFace.NORTH) offZ = -1;
            else if (dir == BlockFace.SOUTH) offZ = 1;
            else if (dir == BlockFace.WEST) offX = -1;
            else if (dir == BlockFace.EAST) offX = 1;
            ax += offX; bx += offX;
            az += offZ; bz += offZ;

            BlockVector a = new BlockVector(ax, ay, az);
            BlockVector b = new BlockVector(bx, by, bz);
            int[] maps = computeMaps(a, b, dir);
            return new BoardPlacement(w.getName(), a, b, dir, maps[0], maps[1]);
        }

        private static int[] computeMaps(BlockVector a, BlockVector b, BlockFace facing) {
            int wide;
            int high;

            if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
                wide = Math.abs(a.getBlockX() - b.getBlockX()) + 1;
                high = Math.abs(a.getBlockY() - b.getBlockY()) + 1;
            } else {
                wide = Math.abs(a.getBlockZ() - b.getBlockZ()) + 1;
                high = Math.abs(a.getBlockY() - b.getBlockY()) + 1;
            }

            // Do not enforce an arbitrary size cap here.
            // The placement selection determines the board size; MapEngine will be the practical limit.
            wide = Math.max(1, wide);
            high = Math.max(1, high);
            return new int[] { wide, high };
        }
    }
}
