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
import java.util.Locale;
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
    // Per-player last locations used to compute speed
    private final java.util.Map<java.util.UUID, org.bukkit.Location> lastLocations = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> lastLocationTimes = new java.util.HashMap<>();
    // Store last computed blocks-per-second (bps). Derive km/h or bph when needed.
    private final java.util.Map<java.util.UUID, Double> lastBps = new java.util.HashMap<>();

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
        int period = cfgIntAny(
            java.util.List.of("racing.ui.update-ticks", "scoreboard.update-ticks"),
            5
        );
        period = Math.max(1, period);
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, period).getTaskId();
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
        boolean running = rm.isRunning();
        boolean registering = rm.isRegistering();
        long nowMs = System.currentTimeMillis();
        log("tick: online=" + Bukkit.getOnlinePlayers().size() + ", running=" + running + ", registering=" + registering + ", libLoaded=" + (lib != null));

        TickContext ctx = new TickContext();
        ctx.running = running;
        ctx.registering = registering;
        if (running) {
            ctx.liveOrder = rm.getLiveOrder();
            ctx.positionById = new java.util.HashMap<>();
            for (int i = 0; i < ctx.liveOrder.size(); i++) ctx.positionById.put(ctx.liveOrder.get(i), i + 1);
        } else {
            ctx.liveOrder = java.util.List.of();
            ctx.positionById = java.util.Map.of();
        }
        if (!running && !registering) {
            ctx.standings = rm.getStandings();
            ctx.anyFinished = ctx.standings.stream().anyMatch(s -> s.finished);
        } else {
            ctx.standings = java.util.List.of();
            ctx.anyFinished = false;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                // compute speed (blocks per second) based on movement since last tick (variable rate)
                org.bukkit.Location now = p.getLocation();
                org.bukkit.Location prev = lastLocations.get(p.getUniqueId());
                Long prevT = lastLocationTimes.get(p.getUniqueId());
                double bps = 0.0;
                if (prev != null && prevT != null && prev.getWorld() != null && now.getWorld() != null && prev.getWorld().equals(now.getWorld())) {
                    long dtMs = Math.max(0L, nowMs - prevT);
                    if (dtMs > 0L) {
                        double dist = now.distance(prev); // blocks
                        bps = dist / (dtMs / 1000.0);
                    }
                }
                lastBps.put(p.getUniqueId(), bps);
                lastLocations.put(p.getUniqueId(), now);
                lastLocationTimes.put(p.getUniqueId(), nowMs);

                updateFor(p, ctx);
            } catch (Throwable ignored) {}
        }
    }

    private void updateFor(Player p, TickContext ctx) {
        if (ctx.running) { setState(p, "RACING"); applyRacingBoard(p, ctx); applyActionBarForRacing(p, ctx); return; }
        if (ctx.registering) { setState(p, "WAITING"); applyWaitingBoard(p); applyActionBarForWaiting(p); return; }
        if (ctx.anyFinished) { setState(p, "COMPLETED"); applyCompletedBoard(p, ctx); clearActionBar(p); return; }
        setState(p, "LOBBY");
        applyLobbyBoard(p); clearActionBar(p);
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
        ph.put("countdown", formatCountdownSeconds(rm.getCountdownRemainingSeconds()));
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
                    "<gray>Bắt đầu: <white>%countdown%"), ph);
        }
        applySidebarComponents(p, sb, title, lines);
    }

    private void applyRacingBoard(Player p, TickContext ctx) {
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
            int pos = Math.max(1, ctx.positionById.getOrDefault(p.getUniqueId(), 1));
            double progressPct = rm.getLapProgressRatio(p.getUniqueId()) * 100.0;
            ph.put("lap_current", String.valueOf(st.currentLap+1)); ph.put("lap_total", String.valueOf(laps));
            ph.put("position", String.valueOf(pos)); ph.put("joined", String.valueOf(ctx.liveOrder.size()));
            ph.put("progress", fmt2(progressPct));
            ph.put("progress_int", String.valueOf((int) Math.round(progressPct)));
            int totalCp = plugin.getTrackConfig().getCheckpoints().size();
            int passedCp = (st.nextCheckpointIndex);
            ph.put("checkpoint_passed", String.valueOf(passedCp));
            ph.put("checkpoint_total", String.valueOf(totalCp));
            lines = parseLines(p, cfgStringList("scoreboard.templates.racing.lines", java.util.List.of()), ph);
            if (lines.isEmpty()) {
                lines = parseLines(p, java.util.List.of(
                        "<gray>Đường: <white>%track%",
                        "<gray>Thời gian: <white>%timer%",
                        "<gray>Vòng: <white>%lap_current%/%lap_total%",
                        "<gray>Checkpoint: <white>%checkpoint_passed%/%checkpoint_total%",
                        "<gray>Vị trí: <white>%position%/%joined%",
                        "<gray>Tiến độ: <white>%progress%%"), ph);
            }
        } else {
            lines = parseLines(p, cfgStringList("scoreboard.templates.racing.lines", java.util.List.of("<gray>Đường: <white>%track%", "<gray>Thời gian: <white>%timer%")), ph);
        }
        applySidebarComponents(p, sb, title, lines);
    }

    private void applyCompletedBoard(Player p, TickContext ctx) {
        Sidebar sb = ensureSidebar(p);
        Component title = parse(p, cfgString("scoreboard.templates.completed.title", "<gold>Kết quả"), java.util.Map.of());
        java.util.List<RaceManager.ParticipantState> standings = ctx.standings;
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
        int posCounter = 0;
        for (UUID id : order) {
            RaceManager.ParticipantState s = rm.getParticipantState(id);
            if (s == null || s.finished) continue;
            posCounter++;
            int pos = posCounter;
            String name = nameOf(id);
            double progressPct = rm.getLapProgressRatio(id) * 100.0;
            java.util.Map<String,String> ph = new java.util.HashMap<>();
            ph.put("racer_name", name); ph.put("position", String.valueOf(pos)); ph.put("progress", fmt2(progressPct));
            ph.put("progress_int", String.valueOf((int) Math.round(progressPct)));
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

    // --- ActionBar support ---
    private void applyActionBarForWaiting(Player p) {
        if (!cfgBool("scoreboard.actionbar.enabled", true)) return;
        String tpl = cfgString("scoreboard.actionbar.waiting", "<yellow>Start in <white>%countdown%</white> • <gray>%joined%/%max%</gray>");
        int countdown = rm.getCountdownRemainingSeconds();
        int joined = rm.getRegistered().size();
        int max = plugin.getTrackConfig().getStarts().size();
        java.util.Map<String,String> ph = new java.util.HashMap<>();
        ph.put("countdown", formatCountdownSeconds(countdown)); ph.put("joined", String.valueOf(joined)); ph.put("max", String.valueOf(max));
        Component c = parse(p, tpl, ph);
        sendActionBar(p, c);
        log("Applied waiting actionbar to " + p.getName() + " tpl='" + tpl + "'");
    }

    private void applyActionBarForRacing(Player p, TickContext ctx) {
        if (!cfgBool("scoreboard.actionbar.enabled", true)) return;
        // Default template uses the dynamic %speed_color% placeholder as a MiniMessage tag name
        // so that servers can get auto-colored speed without touching config
        String tpl = cfgString("scoreboard.actionbar.racing", "<gray>#%position% %racer_name% <white>%lap_current%/%lap_total%</white> <yellow>%progress%%</yellow> <%speed_color%>%speed% %speed_unit%</%speed_color%>");
        java.util.Map<String,String> ph = new java.util.HashMap<>();
        var st = rm.getParticipantState(p.getUniqueId());
        int pos = Math.max(1, ctx.positionById.getOrDefault(p.getUniqueId(), 1));
        int lapCurrent = st == null ? 0 : (st.currentLap + 1);
        int lapTotal = rm.getTotalLaps();
        double progressPct = rm.getLapProgressRatio(p.getUniqueId()) * 100.0;
        int nextCp = (st == null ? 0 : st.nextCheckpointIndex + 1);
        int totalCp = plugin.getTrackConfig().getCheckpoints().size();
        int passedCp = (st == null ? 0 : st.nextCheckpointIndex);
        double bps = lastBps.getOrDefault(p.getUniqueId(), 0.0);
        double kmh = bps * 3.6;
        double bph = bps * 3600.0;
        // Per-player preferred unit overrides global
        String unitPref = pm != null ? pm.get(p.getUniqueId()).speedUnit : "";
        String unit = (unitPref != null && !unitPref.isEmpty()) ? unitPref.toLowerCase() : cfgString("scoreboard.speed.unit", "kmh").toLowerCase();
        String speedVal;
        String speedUnit;
        if ("bps".equals(unit)) { speedVal = fmt2(bps); speedUnit = "bps"; }
        else if ("bph".equals(unit)) { speedVal = fmt2(bph); speedUnit = "bph"; }
        else { speedVal = fmt2(kmh); speedUnit = "km/h"; unit = "kmh"; }
        String speedColor = resolveSpeedColorByUnit(bps, unit);
        ph.put("position", String.valueOf(pos)); ph.put("racer_name", p.getName()); ph.put("lap_current", String.valueOf(lapCurrent)); ph.put("lap_total", String.valueOf(lapTotal));
        ph.put("progress", fmt2(progressPct));
        ph.put("progress_int", String.valueOf((int) Math.round(progressPct)));
        ph.put("next_checkpoint", String.valueOf(nextCp)); ph.put("checkpoint_total", String.valueOf(totalCp));
        ph.put("checkpoint_passed", String.valueOf(passedCp));
        // Speed placeholders (configurable unit + back-compat)
        ph.put("speed", speedVal);
        ph.put("speed_unit", speedUnit);
        ph.put("speed_bps", fmt2(bps));
        ph.put("speed_kmh", fmt2(kmh));
        ph.put("speed_bph", fmt2(bph));
        // color placeholder
        ph.put("speed_color", speedColor);
        Component c = parse(p, tpl, ph);
        sendActionBar(p, c);
        log("Applied racing actionbar to " + p.getName() + " tpl='" + tpl + "' pos=" + pos + " lap=" + lapCurrent + "/" + lapTotal + " speed(bps)=" + fmt2(bps) + " unit=" + unit);
    }

    private static String fmt2(double v) {
        if (!Double.isFinite(v)) return "0.00";
        return String.format(Locale.US, "%.2f", v);
    }

    private int cfgIntAny(java.util.List<String> paths, int def) {
        if (paths != null) {
            for (String p : paths) {
                try {
                    if (p != null && plugin.getConfig().contains(p)) return plugin.getConfig().getInt(p, def);
                } catch (Throwable ignored) {}
            }
        }
        return def;
    }

    private static class TickContext {
        boolean running;
        boolean registering;
        boolean anyFinished;
        java.util.List<java.util.UUID> liveOrder = java.util.List.of();
        java.util.Map<java.util.UUID, Integer> positionById = java.util.Map.of();
        java.util.List<RaceManager.ParticipantState> standings = java.util.List.of();
    }

    private String resolveSpeedColorByUnit(double bps, String unit) {
        double yellow;
        double green;
        if ("bps".equals(unit)) {
            int y = cfgInt("scoreboard.speed.yellow_bps", -1);
            int g = cfgInt("scoreboard.speed.green_bps", -1);
            if (y >= 0 && g >= 0) { yellow = y; green = g; }
            else {
                int yb = cfgInt("scoreboard.speed.yellow_bph", 5000);
                int gb = cfgInt("scoreboard.speed.green_bph", 20000);
                yellow = yb / 3600.0; green = gb / 3600.0;
            }
            double v = bps;
            if (green < yellow) { double t = green; green = yellow; yellow = t; }
            String low = cfgString("scoreboard.speed.colors.bps.low", cfgString("scoreboard.speed.colors.low", "red"));
            String mid = cfgString("scoreboard.speed.colors.bps.mid", cfgString("scoreboard.speed.colors.mid", "yellow"));
            String high = cfgString("scoreboard.speed.colors.bps.high", cfgString("scoreboard.speed.colors.high", "green"));
            if (v < yellow) return low;
            if (v < green) return mid;
            return high;
        } else if ("kmh".equals(unit)) { // km/h
            int y = cfgInt("scoreboard.speed.yellow_kmh", -1);
            int g = cfgInt("scoreboard.speed.green_kmh", -1);
            if (y >= 0 && g >= 0) { yellow = y; green = g; }
            else {
                int yb = cfgInt("scoreboard.speed.yellow_bph", 5000);
                int gb = cfgInt("scoreboard.speed.green_bph", 20000);
                yellow = yb / 1000.0; green = gb / 1000.0;
            }
            double v = bps * 3.6;
            if (green < yellow) { double t = green; green = yellow; yellow = t; }
            String low = cfgString("scoreboard.speed.colors.kmh.low", cfgString("scoreboard.speed.colors.low", "red"));
            String mid = cfgString("scoreboard.speed.colors.kmh.mid", cfgString("scoreboard.speed.colors.mid", "yellow"));
            String high = cfgString("scoreboard.speed.colors.kmh.high", cfgString("scoreboard.speed.colors.high", "green"));
            if (v < yellow) return low;
            if (v < green) return mid;
            return high;
        } else { // bph
            int yb = cfgInt("scoreboard.speed.yellow_bph", 5000);
            int gb = cfgInt("scoreboard.speed.green_bph", 20000);
            double v = bps * 3600.0;
            yellow = yb; green = gb;
            if (green < yellow) { double t = green; green = yellow; yellow = t; }
            String low = cfgString("scoreboard.speed.colors.bph.low", cfgString("scoreboard.speed.colors.low", "red"));
            String mid = cfgString("scoreboard.speed.colors.bph.mid", cfgString("scoreboard.speed.colors.mid", "yellow"));
            String high = cfgString("scoreboard.speed.colors.bph.high", cfgString("scoreboard.speed.colors.high", "green"));
            if (v < yellow) return low;
            if (v < green) return mid;
            return high;
        }
    }

    private void clearActionBar(Player p) {
        try { sendActionBar(p, Component.empty()); } catch (Throwable ignored) {}
    }

    private void sendActionBar(Player p, Component c) {
        try {
            // Preferred: Adventure-friendly API if available
            p.sendActionBar(c);
        } catch (Throwable t) {
            try {
                // Fallback to legacy spigot action bar
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(Text.plain(c)));
            } catch (Throwable ignored) {}
        }
    }

    private static String formatCountdownSeconds(int sec) {
        if (sec <= 0) return "0s";
        if (sec >= 60) {
            int m = sec / 60; int s = sec % 60; return String.format("%d:%02d", m, s);
        }
        return sec + "s";
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

    private String cfgString(String path, String def) {
        String v = plugin.getConfig().getString(path);
        if (v != null) return v;
        String alt = altUiPath(path);
        if (alt != null) {
            String av = plugin.getConfig().getString(alt);
            if (av != null) return av;
        }
        return def;
    }

    private java.util.List<String> cfgStringList(String path, java.util.List<String> def) {
        java.util.List<String> out = plugin.getConfig().getStringList(path);
        if (out != null && !out.isEmpty()) return out;
        String alt = altUiPath(path);
        if (alt != null) {
            java.util.List<String> ao = plugin.getConfig().getStringList(alt);
            if (ao != null && !ao.isEmpty()) return ao;
        }
        return def;
    }

    private int cfgInt(String path, int def) { return plugin.getConfig().getInt(path, def); }

    private boolean cfgBool(String path, boolean def) {
        if (plugin.getConfig().contains(path)) return plugin.getConfig().getBoolean(path, def);
        String alt = altUiPath(path);
        if (alt != null && plugin.getConfig().contains(alt)) return plugin.getConfig().getBoolean(alt, def);
        return def;
    }

    // Support both legacy keys (scoreboard.templates/actionbar.*) and current keys (racing.ui.templates.*)
    private static String altUiPath(String path) {
        if (path == null) return null;
        if (path.startsWith("scoreboard.templates.")) {
            return "racing.ui.templates." + path.substring("scoreboard.templates.".length());
        }
        if (path.startsWith("scoreboard.actionbar.")) {
            return "racing.ui.templates.actionbar." + path.substring("scoreboard.actionbar.".length());
        }
        return null;
    }

    private static String colorTagFor(org.bukkit.DyeColor dc) {
        // MiniMessage supports a specific set of color names (e.g. gold, red, light_purple, dark_aqua, etc.)
        // Map Bukkit DyeColor to the closest MiniMessage-supported name (fallback to white).
        if (dc == null) return "<white>";
        switch (dc) {
            case WHITE: return "<white>";
            case ORANGE: return "<gold>"; // orange -> gold
            case MAGENTA: return "<light_purple>";
            case LIGHT_BLUE: return "<blue>";
            case YELLOW: return "<yellow>";
            case LIME: return "<green>";
            case PINK: return "<light_purple>";
            case GRAY: return "<gray>";
            case LIGHT_GRAY: return "<gray>";
            case CYAN: return "<aqua>";
            case PURPLE: return "<dark_purple>";
            case BLUE: return "<blue>";
            case BROWN: return "<gold>";
            case GREEN: return "<green>";
            case RED: return "<red>";
            case BLACK: return "<black>";
            default: return "<white>";
        }
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
