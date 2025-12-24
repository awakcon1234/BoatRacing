package es.jaie55.boatracing.ui;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.profile.PlayerProfileManager;
import es.jaie55.boatracing.race.RaceManager;
import es.jaie55.boatracing.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.megavex.scoreboardlibrary.api.ScoreboardLibrary;
import net.megavex.scoreboardlibrary.api.sidebar.Sidebar;

import java.util.List;
import java.util.UUID;

public class ScoreboardService {
    private final BoatRacingPlugin plugin;
    private final RaceManager rm;
    private final PlayerProfileManager pm;
    private int taskId = -1;
    private ScoreboardLibrary lib;
    private final java.util.Map<java.util.UUID, Sidebar> sidebars = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> lastCounts = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, String> lastState = new java.util.HashMap<>();
    private volatile boolean debug = false;

    public ScoreboardService(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.rm = plugin.getRaceManager();
        this.pm = plugin.getProfileManager();
    }

    public void start() {
        if (taskId != -1) return;
        try {
            lib = ScoreboardLibrary.loadScoreboardLibrary(plugin);
            log("ScoreboardLibrary loaded: " + (lib != null));
        } catch (Throwable t) {
            plugin.getLogger().warning("ScoreboardLibrary not available: " + t.getMessage());
            lib = null;
        }
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L).getTaskId();
        log("ScoreboardService started. taskId=" + taskId);
        // Always print a one-time info so admins know how to enable debug
        plugin.getLogger().info("[SB] ScoreboardService started (libLoaded=" + (lib != null) + "). Toggle debug: /boatracing sb debug on");
    }

    public void stop() {
        if (taskId != -1) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
            taskId = -1;
        }
        // Close all sidebars and library
        for (Sidebar s : sidebars.values()) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        sidebars.clear();
        lastCounts.clear();
        try { if (lib != null) lib.close(); } catch (Throwable ignored) {}
        log("ScoreboardService stopped and cleaned up.");
    }

    public void forceTick() { tick(); }

    public void setDebug(boolean enabled) {
        this.debug = enabled;
        log("Debug set to " + enabled);
    }

    private void tick() {
        if (rm == null) { log("tick: rm=null, waiting for initialization"); return; }
        log("tick: online=" + Bukkit.getOnlinePlayers().size() + ", running=" + rm.isRunning() + ", registering=" + rm.isRegistering() + ", libLoaded=" + (lib != null));
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { updateFor(p); } catch (Throwable ignored) {}
        }
    }

    private void updateFor(Player p) {
        if (rm.isRunning()) { setState(p, "RACING"); applyRacingBoard(p); return; }
        if (rm.isRegistering()) { setState(p, "WAITING"); applyWaitingBoard(p); return; }
        boolean anyFinished = rm.getStandings().stream().anyMatch(s -> s.finished);
        if (anyFinished) { setState(p, "COMPLETED"); applyCompletedBoard(p); return; }
        setState(p, "LOBBY");
        applyLobbyBoard(p);
    }

    private void applyLobbyBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = parse(p, cfgString("scoreboard.templates.lobby.title", "<gold>BoatRacing"), java.util.Map.of());
        PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
        java.util.Map<String,String> ph = new java.util.HashMap<>();
        ph.put("racer_name", p.getName()); ph.put("racer_color", colorTagFor(prof.color)); ph.put("icon", empty(prof.icon)?"-":prof.icon);
        ph.put("number", prof.number>0?String.valueOf(prof.number):"-"); ph.put("completed", String.valueOf(prof.completed)); ph.put("wins", String.valueOf(prof.wins));
        java.util.List<Component> lines = parseLines(p, cfgStringList("scoreboard.templates.lobby.lines", java.util.List.of(
            "<yellow>Hồ sơ của bạn",
            "<gray>Tên: %racer_color%%racer_name%",
            "<gray>Màu: <white>%racer_color%",
            "<gray>Biểu tượng: <white>%icon%",
            "<gray>Số đua: <white>%number%",
            "<yellow>Thành tích",
            "<gray>Hoàn thành: <white>%completed%",
            "<gray>Chiến thắng: <white>%wins%")), ph);
        applySidebarComponents(p, sb, title, lines);
    }

    private void applyWaitingBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = parse(p, cfgString("scoreboard.templates.waiting.title", "<gold>Đang chờ"), java.util.Map.of());
        String track = plugin.getTrackLibrary().getCurrent() != null ? plugin.getTrackLibrary().getCurrent() : "(unsaved)";
        int joined = rm.getRegistered().size();
        int max = plugin.getTrackConfig().getStarts().size();
        int laps = rm.getTotalLaps();
        PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
        java.util.Map<String,String> ph = new java.util.HashMap<>();
        ph.put("racer_name", p.getName()); ph.put("racer_color", colorTagFor(prof.color)); ph.put("icon", empty(prof.icon)?"-":prof.icon);
        ph.put("number", prof.number>0?String.valueOf(prof.number):"-"); ph.put("track", track); ph.put("laps", String.valueOf(laps));
        ph.put("joined", String.valueOf(joined)); ph.put("max", String.valueOf(max));
        java.util.List<Component> lines = parseLines(p, cfgStringList("scoreboard.templates.waiting.lines", java.util.List.of()), ph);
        if (lines.isEmpty()) {
            lines = parseLines(p, java.util.List.of(
                "<yellow>Thông tin đường",
                "<gray>Đường: <white>%track%",
                "<gray>Vòng: <white>%laps%",
                "<gray>Người chơi: <white>%joined%/%max%",
                    "<yellow>Tay đua",
                "<gray>Tên: %racer_color%%racer_name%",
                "<gray>Màu: <white>%racer_color%",
                "<gray>Biểu tượng: <white>%icon%",
                "<gray>Số đua: <white>%number%",
                    "<gray>Bắt đầu: <white>đang chờ..."), ph);
        }
        applySidebarComponents(p, sb, title, lines);
    }

    private void applyRacingBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = parse(p, cfgString("scoreboard.templates.racing.title", "<gold>Đang đua"), java.util.Map.of());
        String track = plugin.getTrackLibrary().getCurrent() != null ? plugin.getTrackLibrary().getCurrent() : "(unsaved)";
        int laps = rm.getTotalLaps();
        long ms = rm.getRaceElapsedMillis();
        java.util.Map<String,String> ph = new java.util.HashMap<>();
        ph.put("track", track); ph.put("timer", fmt(ms));
        var st = rm.getParticipantState(p.getUniqueId());
        java.util.List<Component> lines;
        if (st != null) {
            java.util.List<java.util.UUID> order = rm.getLiveOrder();
            int pos = Math.max(1, order.indexOf(p.getUniqueId()) + 1);
            int percent = (int) Math.round(rm.getLapProgressRatio(p.getUniqueId()) * 100.0);
            ph.put("lap_current", String.valueOf(st.currentLap+1)); ph.put("lap_total", String.valueOf(laps));
            ph.put("position", String.valueOf(pos)); ph.put("joined", String.valueOf(order.size())); ph.put("progress", String.valueOf(percent));
            lines = parseLines(p, cfgStringList("scoreboard.templates.racing.lines", java.util.List.of()), ph);
            if (lines.isEmpty()) {
                lines = parseLines(p, java.util.List.of(
                        "<gray>Đường: <white>%track%",
                        "<gray>Thời gian: <white>%timer%",
                        "<gray>Vòng: <white>%lap_current%/%lap_total%",
                        "<gray>Vị trí: <white>%position%/%joined%",
                        "<gray>Tiến độ: <white>%progress%%"), ph);
            }
        } else {
            lines = parseLines(p, cfgStringList("scoreboard.templates.racing.lines", java.util.List.of("<gray>Đường: <white>%track%", "<gray>Thời gian: <white>%timer%")), ph);
        }
        applySidebarComponents(p, sb, title, lines);
    }

    private void applyCompletedBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = parse(p, cfgString("scoreboard.templates.completed.title", "<gold>Kết quả"), java.util.Map.of());
        java.util.List<RaceManager.ParticipantState> standings = rm.getStandings();
        java.util.List<Component> lines = new java.util.ArrayList<>();
        // Header
        for (String line : cfgStringList("scoreboard.templates.completed.header", java.util.List.of("<yellow>Về đích"))) {
            lines.add(parse(p, line, java.util.Map.of()));
        }
        int shown = 0;
        int maxFinished = cfgInt("scoreboard.templates.completed.max_finished_lines", 8);
        for (RaceManager.ParticipantState s : standings) {
            if (!s.finished) continue;
            String name = nameOf(s.id);
            long t = Math.max(0L, s.finishTimeMillis - rm.getRaceStartMillis()) + s.penaltySeconds*1000L;
            java.util.Map<String,String> ph = new java.util.HashMap<>();
            ph.put("racer_name", name); ph.put("finish_pos", String.valueOf(s.finishPosition)); ph.put("finish_time", fmt(t));
            lines.add(parse(p, cfgString("scoreboard.templates.completed.finished_line", "<white>%finish_pos%) %racer_name%"), ph));
            lines.add(parse(p, cfgString("scoreboard.templates.completed.finished_time_line", "<gray>  thời gian: <white>%finish_time%"), ph));
            shown += 2;
            if (shown >= maxFinished) break; // avoid overflow
        }
        // Unfinished racers: live position
        String unfinishedHeader = cfgString("scoreboard.templates.completed.unfinished_header", "<yellow>Đang đua");
        lines.add(parse(p, unfinishedHeader, java.util.Map.of()));
        List<UUID> order = rm.getLiveOrder();
        int limit = cfgInt("scoreboard.templates.completed.max_unfinished_lines", 6);
        int count = 0;
        for (UUID id : order) {
            RaceManager.ParticipantState s = rm.getParticipantState(id);
            if (s == null || s.finished) continue;
            int pos = order.indexOf(id) + 1;
            String name = nameOf(id);
            int percent = (int) Math.round(rm.getLapProgressRatio(id) * 100.0);
            java.util.Map<String,String> ph = new java.util.HashMap<>();
            ph.put("racer_name", name); ph.put("position", String.valueOf(pos)); ph.put("progress", String.valueOf(percent));
            lines.add(parse(p, cfgString("scoreboard.templates.completed.unfinished_line", "<white>%position%) %racer_name%"), ph));
            lines.add(parse(p, cfgString("scoreboard.templates.completed.unfinished_progress_line", "<gray>  tiến độ: <white>%progress%%"), ph));
            count += 2;
            if (count >= limit) break;
        }
        applySidebarComponents(p, sb, title, lines);
    }

    private Sidebar ensureSidebar(Player p) {
        if (lib == null) return null;
        return sidebars.computeIfAbsent(p.getUniqueId(), id -> {
            Sidebar s = lib.createSidebar();
            s.addPlayer(p);
            log("Created sidebar for " + p.getName());
            return s;
        });
    }

    private void applySidebarComponents(Player p, Sidebar sidebar, Component title, java.util.List<Component> lines) {
        if (sidebar == null) return;
        sidebar.title(title);
        for (int idx = 0; idx < lines.size(); idx++) sidebar.line(idx, lines.get(idx));
        int last = lastCounts.getOrDefault(p.getUniqueId(), 0);
        for (int idx = lines.size(); idx < last; idx++) sidebar.line(idx, Component.empty());
        lastCounts.put(p.getUniqueId(), lines.size());
        log("Applied sidebar to " + p.getName() + " title='" + Text.plain(title) + "' lines=" + lines.size());
    }

    private java.util.List<Component> parseLines(Player p, java.util.List<String> lines, java.util.Map<String,String> placeholders) {
        java.util.List<Component> out = new java.util.ArrayList<>(lines.size());
        for (String s : lines) out.add(parse(p, s, placeholders));
        return out;
    }

    private Component parse(Player p, String raw, java.util.Map<String,String> placeholders) {
        if (raw == null) return Component.empty();
        String s = raw;
        // plugin placeholders (%key%)
        if (placeholders != null) for (var e : placeholders.entrySet()) s = s.replace("%" + e.getKey() + "%", e.getValue());
        // PlaceholderAPI if present
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s); } catch (Throwable ignored) {}
        }
        try { return MiniMessage.miniMessage().deserialize(s); } catch (Throwable ignored) { return Text.c(s); }
    }

    private String cfgString(String path, String def) { return plugin.getConfig().getString(path, def); }
    private java.util.List<String> cfgStringList(String path, java.util.List<String> def) {
        java.util.List<String> out = plugin.getConfig().getStringList(path);
        return (out == null || out.isEmpty()) ? def : out;
    }
    private int cfgInt(String path, int def) { return plugin.getConfig().getInt(path, def); }

    private static String colorTagFor(org.bukkit.DyeColor dc) {
        String n = (dc == null ? "white" : dc.name().toLowerCase(java.util.Locale.ROOT));
        return "<" + n + ">";
    }
    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    private static String fmt(long ms) {
        long totalSec = ms / 1000L;
        long m = totalSec / 60L;
        long s = totalSec % 60L;
        long msPart = ms % 1000L;
        return String.format("%02d:%02d.%03d", m, s, msPart);
    }

    private static String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        if (op != null && op.getName() != null) return op.getName();
        String s = id.toString();
        return s.substring(0, 8);
    }

    private void setState(Player p, String state) {
        if (!debug) return;
        String prev = lastState.put(p.getUniqueId(), state);
        if (prev == null || !prev.equals(state)) log("State for " + p.getName() + " -> " + state);
    }

    private void log(String msg) { if (debug) plugin.getLogger().info("[SB] " + msg); }
}
