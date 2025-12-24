package es.jaie55.boatracing.ui;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.profile.PlayerProfileManager;
import es.jaie55.boatracing.race.RaceManager;
import es.jaie55.boatracing.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
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

    public ScoreboardService(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.rm = plugin.getRaceManager();
        this.pm = plugin.getProfileManager();
    }

    public void start() {
        if (taskId != -1) return;
        try {
            lib = ScoreboardLibrary.loadScoreboardLibrary(plugin);
        } catch (Throwable t) {
            plugin.getLogger().warning("ScoreboardLibrary not available: " + t.getMessage());
            lib = null;
        }
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L).getTaskId();
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
    }

    public void forceTick() { tick(); }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { updateFor(p); } catch (Throwable ignored) {}
        }
    }

    private void updateFor(Player p) {
        if (rm.isRunning()) { applyRacingBoard(p); return; }
        if (rm.isRegistering()) { applyWaitingBoard(p); return; }
        boolean anyFinished = rm.getStandings().stream().anyMatch(s -> s.finished);
        if (anyFinished) { applyCompletedBoard(p); return; }
        applyLobbyBoard(p);
    }

    private void applyLobbyBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = Text.c("&6BoatRacing");
        PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("&eHồ sơ của bạn");
        lines.add("&7Tên: &f" + p.getName());
        lines.add("&7Màu: &f" + prof.color.name());
        lines.add("&7Biểu tượng: &f" + (empty(prof.icon)?"-":prof.icon));
        lines.add("&7Số đua: &f" + (prof.number>0?prof.number:"-"));
        lines.add("&eThành tích");
        lines.add("&7Hoàn thành: &f" + prof.completed);
        lines.add("&7Chiến thắng: &f" + prof.wins);
        applySidebar(p, sb, title, lines);
    }

    private void applyWaitingBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = Text.c("&6Đang chờ");
        String track = plugin.getTrackLibrary().getCurrent() != null ? plugin.getTrackLibrary().getCurrent() : "(unsaved)";
        int joined = rm.getRegistered().size();
        int max = plugin.getTrackConfig().getStarts().size();
        int laps = rm.getTotalLaps();
        PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("&eThông tin đường");
        lines.add("&7Đường: &f" + track);
        lines.add("&7Vòng: &f" + laps);
        lines.add("&7Người chơi: &f" + joined + "/" + max);
        lines.add("&eTay đua");
        lines.add("&7Tên: &f" + p.getName());
        lines.add("&7Màu: &f" + prof.color.name());
        lines.add("&7Biểu tượng: &f" + (empty(prof.icon)?"-":prof.icon));
        lines.add("&7Số đua: &f" + (prof.number>0?prof.number:"-"));
        lines.add("&7Bắt đầu: &fđang chờ...");
        applySidebar(p, sb, title, lines);
    }

    private void applyRacingBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = Text.c("&6Đang đua");
        String track = plugin.getTrackLibrary().getCurrent() != null ? plugin.getTrackLibrary().getCurrent() : "(unsaved)";
        int laps = rm.getTotalLaps();
        long ms = rm.getRaceElapsedMillis();
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("&7Đường: &f" + track);
        lines.add("&7Thời gian: &f" + fmt(ms));
        var st = rm.getParticipantState(p.getUniqueId());
        if (st != null) {
            lines.add("&7Vòng: &f" + (st.currentLap+1) + "/" + laps);
            java.util.List<java.util.UUID> order = rm.getLiveOrder();
            int pos = Math.max(1, order.indexOf(p.getUniqueId()) + 1);
            lines.add("&7Vị trí: &f" + pos + "/" + order.size());
            int percent = (int) Math.round(rm.getLapProgressRatio(p.getUniqueId()) * 100.0);
            lines.add("&7Tiến độ: &f" + percent + "%");
        }
        applySidebar(p, sb, title, lines);
    }

    private void applyCompletedBoard(Player p) {
        Sidebar sb = ensureSidebar(p);
        Component title = Text.c("&6Kết quả");
        java.util.List<RaceManager.ParticipantState> standings = rm.getStandings();
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("&eVề đích");
        int shown = 0;
        for (RaceManager.ParticipantState s : standings) {
            if (!s.finished) continue;
            String name = nameOf(s.id);
            long t = Math.max(0L, s.finishTimeMillis - rm.getRaceStartMillis()) + s.penaltySeconds*1000L;
            lines.add("&f" + s.finishPosition + ") " + name);
            lines.add("&7  thời gian: &f" + fmt(t));
            shown += 2;
            if (shown >= 8) break; // avoid overflow
        }
        // Unfinished racers: live position
        lines.add("&eĐang đua");
        List<UUID> order = rm.getLiveOrder();
        int limit = 6;
        int count = 0;
        for (UUID id : order) {
            RaceManager.ParticipantState s = rm.getParticipantState(id);
            if (s == null || s.finished) continue;
            int pos = order.indexOf(id) + 1;
            String name = nameOf(id);
            lines.add("&f" + pos + ") " + name);
            int percent = (int) Math.round(rm.getLapProgressRatio(id) * 100.0);
            lines.add("&7  tiến độ: &f" + percent + "%");
            count += 2;
            if (count >= limit) break;
        }
        applySidebar(p, sb, title, lines);
    }

    private Sidebar ensureSidebar(Player p) {
        if (lib == null) return null;
        return sidebars.computeIfAbsent(p.getUniqueId(), id -> {
            Sidebar s = lib.createSidebar();
            s.addPlayer(p);
            return s;
        });
    }

    private void applySidebar(Player p, Sidebar sidebar, Component title, java.util.List<String> legacyLines) {
        if (sidebar == null) return;
        sidebar.title(title);
        java.util.List<Component> lines = new java.util.ArrayList<>(legacyLines.size());
        for (String s : legacyLines) lines.add(Text.c(s));
        for (int idx = 0; idx < lines.size(); idx++) sidebar.line(idx, lines.get(idx));
        int last = lastCounts.getOrDefault(p.getUniqueId(), 0);
        for (int idx = lines.size(); idx < last; idx++) sidebar.line(idx, Component.empty());
        lastCounts.put(p.getUniqueId(), lines.size());
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
}
