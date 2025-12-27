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
import dev.belikhun.boatracing.util.Text;
import org.bukkit.Bukkit;
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
                drawing.ctx().converter(Converter.FLOYD_STEINBERG);
                drawing.ctx().buffering(true);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            dbg("ensureDisplay(): failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            display = null;
            drawing = null;
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

            // Theme (racing broadcast style): dark panels + strong yellow accent.
            final Color bg0 = new Color(0x0E, 0x10, 0x12);
            final Color panel = new Color(0x14, 0x16, 0x1A);
            final Color panel2 = new Color(0x12, 0x14, 0x17);
            final Color borderC = new Color(0x3A, 0x3A, 0x3A);
            final Color accent = new Color(0xFF, 0xD7, 0x5E);
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
            int titleBarH = Math.max(44, fmTitle.getHeight() + (int) Math.round(bodySize * 0.8));

            // Background
            g.setColor(bg0);
            g.fillRect(0, 0, w, h);

            // Border
            g.setColor(borderC);
            g.setStroke(new BasicStroke(border));
            g.drawRect(inset, inset, w - (inset * 2 + 1), h - (inset * 2 + 1));

            // Title bar
            int titleBarX = inset;
            int titleBarY = inset;
            g.setColor(panel);
            g.fillRect(titleBarX, titleBarY, w - (titleBarX * 2), titleBarH);
            // Accent stripe
            g.setColor(accent);
            g.fillRect(titleBarX, titleBarY, w - (titleBarX * 2), Math.max(3, border + 1));

            g.setFont(titleFont);
            g.setColor(text);
            int ty = titleBarY + (titleBarH + fmTitle.getAscent() - fmTitle.getDescent()) / 2;
            g.drawString("BOATRACING", titleBarX + pad, ty);

            // Subtitle (small, broadcast-like)
            g.setFont(smallFont);
            g.setColor(textDim);
            String sub = "BẢNG THÔNG TIN SẢNH";
            int subW = fmSmall.stringWidth(sub);
            int subX = Math.max(titleBarX + pad, (w - subW) / 2);
            int subY = titleBarY + titleBarH - Math.max(6, border * 2);
            g.drawString(sub, subX, subY);

            // Layout
            int top = titleBarY + titleBarH + Math.max(10, (int) Math.round(bodySize * 0.9));
            int mid = w / 2;
            int leftX = pad;
            int rightX = mid + pad;
            int colW = (w / 2) - (pad * 2);

            // Header row (two columns)
            int headerRowH = Math.max(rowH, fmHeader.getHeight() + Math.max(6, border * 2));
            g.setColor(panel2);
            g.fillRect(inset + 1, top, w - (inset * 2 + 2), headerRowH);
            g.setColor(accent);
            g.fillRect(inset + 1, top + headerRowH - Math.max(2, border), w - (inset * 2 + 2), Math.max(2, border));

            g.setFont(headerFont);
            g.setColor(accent);
            int hy = top + (headerRowH + fmHeader.getAscent() - fmHeader.getDescent()) / 2;
            g.drawString("ĐƯỜNG ĐUA", leftX, hy);
            g.drawString("BẢNG XẾP HẠNG", rightX, hy);

            // Divider
            g.setColor(borderC);
            g.setStroke(new BasicStroke(Math.max(1, border - 1)));
            g.drawLine(mid, top, mid, h - inset);

            // Panels
            int contentTop = top + headerRowH + Math.max(10, (int) Math.round(bodySize * 0.6));

            // Reserve a footer box inside the inner border so footer text never overlaps the border.
            int footerPadV = Math.max(6, (int) Math.round(bodySize * 0.35));
            int footerBoxH = fmSmall.getHeight() + footerPadV;
            int footerTop = h - inset - footerBoxH;
            int contentBottom = footerTop - Math.max(8, (int) Math.round(bodySize * 0.50));
            int panelH = Math.max(0, contentBottom - contentTop);
            g.setColor(panel);
            g.fillRect(inset + 1, contentTop - 6, w - (inset * 2 + 2), panelH + 12);

            // Content
            int yLeft = contentTop + fmBody.getAscent();
            g.setFont(bodyFont);

            List<String> leftLines = buildLeftLines();

            int rowIndex = 0;
            for (String line : leftLines) {
                if (yLeft > contentBottom) break;
                if (line == null) line = "";

                if (line.isEmpty()) {
                    yLeft += Math.max(6, rowH / 2);
                    continue;
                }

                // Alternating row background for readability
                if ((rowIndex % 2) == 1) {
                    g.setColor(new Color(0x12, 0x13, 0x16));
                    g.fillRect(leftX - 6, yLeft - fmBody.getAscent(), colW + 12, fmBody.getHeight());
                }

                // Style hints
                if (line.startsWith("Dùng:")) {
                    g.setFont(smallFont);
                    g.setColor(textDim);
                    drawTrimmed(g, line, leftX, yLeft, colW);
                    g.setFont(bodyFont);
                } else if (line.startsWith("● ")) {
                    // Track rows: bullet accent + state coloring
                    drawTrackRow(g, line, leftX, yLeft, colW, bodyFont, accent, text, textDim);
                } else {
                    g.setColor(text);
                    drawTrimmed(g, line, leftX, yLeft, colW);
                }

                yLeft += rowH;
                rowIndex++;
            }

            int yRight = contentTop + fmBody.getAscent();
            List<String> rightLines = buildRightLines();

            int rRowIndex = 0;
            for (String line : rightLines) {
                if (yRight > contentBottom) break;
                if (line == null) line = "";

                if (line.isEmpty()) {
                    yRight += Math.max(6, rowH / 2);
                    continue;
                }

                if ((rRowIndex % 2) == 1) {
                    g.setColor(new Color(0x12, 0x13, 0x16));
                    g.fillRect(rightX - 6, yRight - fmBody.getAscent(), colW + 12, fmBody.getHeight());
                }

                if (line.startsWith("● ")) {
                    // Track group header
                    g.setFont(headerFont);
                    g.setColor(accent);
                    drawTrimmed(g, line.substring(2).trim(), rightX, yRight, colW);
                    g.setFont(bodyFont);
                } else if (line.matches("\\d+\\)\\s+.*")) {
                    // Position row
                    drawRankingRow(g, line, rightX, yRight, colW, bodyFont, accent, text, textDim);
                } else {
                    g.setColor(textDim);
                    drawTrimmed(g, line, rightX, yRight, colW);
                }

                yRight += rowH;
                rRowIndex++;
            }

            // Footer hint
            g.setFont(smallFont);
            g.setColor(textDim);
            int fy = footerTop + (footerBoxH + fmSmall.getAscent() - fmSmall.getDescent()) / 2;
            fy = Math.max(fmSmall.getAscent() + inset, fy);
                drawStringWithFallback(
                    g,
                    "ℹ Chỉ hiển thị khi bạn ở sảnh và gần bảng.",
                    titleBarX + pad,
                    fy,
                    smallFont,
                    monoMatch(smallFont)
                );

        } finally {
            g.dispose();
        }
        return image;
    }

    private static void drawTrackRow(Graphics2D g, String line, int x, int y, int maxWidth, Font bodyFont,
                                     Color accent, Color text, Color textDim) {
        if (g == null) return;
        if (line == null) line = "";

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
        int bx = x + stringWidthWithFallback(g, bullet + " ", bodyFont, fallbackFont);

        // Name
        g.setColor(text);
        int used = bx - x;
        int available = Math.max(0, maxWidth - used);
        if (bracket == null) {
            drawTrimmed(g, name, bx, y, available);
            return;
        }

        // Reserve some space for bracket (trim name first if needed)
        int bracketW = stringWidthWithFallback(g, " " + bracket, bodyFont, fallbackFont);
        int nameW = Math.max(0, available - bracketW);
        String trimmedName = trimToWidthWithFallback(g, name, nameW, bodyFont, fallbackFont);
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
            int insideX = brX + stringWidthWithFallback(g, "[", bodyFont, fallbackFont);
            String inside = bracket.substring(1, bracket.length() - 1);
            g.setColor(stC);
            int insideMax = Math.max(0, maxBr - stringWidthWithFallback(g, "[]", bodyFont, fallbackFont));
            String insideTrim = trimToWidthWithFallback(g, inside, insideMax, bodyFont, fallbackFont);
            drawTrimmed(g, insideTrim, insideX, y, insideMax);
            int insideW = stringWidthWithFallback(g, insideTrim, bodyFont, fallbackFont);
            g.setColor(textDim);
            drawStringWithFallback(g, "]", insideX + insideW, y, bodyFont, fallbackFont);
        } else {
            g.setColor(stC);
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
        String name = line.substring(close + 1).trim();

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
        g.setColor(text);
        drawTrimmed(g, name, nx, y, Math.max(0, maxWidth - (nx - x)));
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

        record Entry(String track, int pos, UUID id, String name) {}
        List<Entry> entries = new ArrayList<>();

        try {
            for (RaceManager rm : raceService.allRaces()) {
                if (rm == null || !rm.isRunning()) continue;
                String track = "(không rõ)";
                try {
                    String n = rm.getTrackConfig() != null ? rm.getTrackConfig().getCurrentName() : null;
                    if (n != null && !n.isBlank()) track = n;
                } catch (Throwable ignored) {}

                List<UUID> order = rm.getLiveOrder();
                int limit = Math.min(5, order.size());
                for (int i = 0; i < limit; i++) {
                    UUID id = order.get(i);
                    if (id == null) continue;
                    var st = rm.getParticipantState(id);
                    if (st != null && st.finished) continue;
                    String name = nameOf(id);
                    entries.add(new Entry(track, i + 1, id, name));
                }
            }
        } catch (Throwable ignored) {}

        if (entries.isEmpty()) {
            out.add("(Chưa có cuộc đua nào đang chạy)");
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
                    racer = stripLegacyColors(profileManager.formatRacerLegacy(e.id, e.name));
                }
            } catch (Throwable ignored) {}
            out.add(e.pos + ") " + racer);
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

            wide = clamp(wide, 1, 16);
            high = clamp(high, 1, 16);
            return new int[] { wide, high };
        }
    }
}
