package es.jaie55.boatracing.ui;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.profile.PlayerProfileManager;
import es.jaie55.boatracing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;
import java.util.UUID;

public class ScoreboardService {
    private final BoatRacingPlugin plugin;
    private final RaceManager rm;
    private final PlayerProfileManager pm;
    private int taskId = -1;

    public ScoreboardService(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.rm = plugin.getRaceManager();
        this.pm = plugin.getProfileManager();
    }

    public void start() {
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
            taskId = -1;
        }
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
        Scoreboard sb = newBoard();
        Objective obj = sidebar(sb, ChatColor.GOLD + "BoatRacing");
        PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
        int i = 100;
        add(obj, i--, ChatColor.YELLOW + "Hồ sơ của bạn");
        add(obj, i--, gray("Tên: ") + ChatColor.WHITE + p.getName());
        add(obj, i--, gray("Màu: ") + ChatColor.WHITE + prof.color.name());
        add(obj, i--, gray("Biểu tượng: ") + ChatColor.WHITE + (empty(prof.icon)?"-":prof.icon));
        add(obj, i--, gray("Số đua: ") + ChatColor.WHITE + (prof.number>0?prof.number:"-"));
        add(obj, i--, ChatColor.YELLOW + "Thành tích");
        add(obj, i--, gray("Hoàn thành: ") + ChatColor.WHITE + prof.completed);
        add(obj, i--, gray("Chiến thắng: ") + ChatColor.WHITE + prof.wins);
        p.setScoreboard(sb);
    }

    private void applyWaitingBoard(Player p) {
        Scoreboard sb = newBoard();
        Objective obj = sidebar(sb, ChatColor.GOLD + "Đang chờ");
        String track = plugin.getTrackLibrary().getCurrent() != null ? plugin.getTrackLibrary().getCurrent() : "(unsaved)";
        int joined = rm.getRegistered().size();
        int max = plugin.getTrackConfig().getStarts().size();
        int laps = rm.getTotalLaps();
        PlayerProfileManager.Profile prof = pm.get(p.getUniqueId());
        int i = 100;
        add(obj, i--, ChatColor.YELLOW + "Thông tin đường");
        add(obj, i--, gray("Đường: ") + ChatColor.WHITE + track);
        add(obj, i--, gray("Vòng: ") + ChatColor.WHITE + laps);
        add(obj, i--, gray("Người chơi: ") + ChatColor.WHITE + joined + "/" + max);
        add(obj, i--, ChatColor.YELLOW + "Tay đua");
        add(obj, i--, gray("Tên: ") + ChatColor.WHITE + p.getName());
        add(obj, i--, gray("Màu: ") + ChatColor.WHITE + prof.color.name());
        add(obj, i--, gray("Biểu tượng: ") + ChatColor.WHITE + (empty(prof.icon)?"-":prof.icon));
        add(obj, i--, gray("Số đua: ") + ChatColor.WHITE + (prof.number>0?prof.number:"-"));
        add(obj, i--, gray("Bắt đầu: ") + ChatColor.WHITE + "đang chờ...");
        p.setScoreboard(sb);
    }

    private void applyRacingBoard(Player p) {
        Scoreboard sb = newBoard();
        Objective obj = sidebar(sb, ChatColor.GOLD + "Đang đua");
        String track = plugin.getTrackLibrary().getCurrent() != null ? plugin.getTrackLibrary().getCurrent() : "(unsaved)";
        int laps = rm.getTotalLaps();
        long ms = rm.getRaceElapsedMillis();
        int i = 100;
        add(obj, i--, gray("Đường: ") + ChatColor.WHITE + track);
        add(obj, i--, gray("Thời gian: ") + ChatColor.WHITE + fmt(ms));
        var st = rm.getParticipantState(p.getUniqueId());
        if (st != null) {
            add(obj, i--, gray("Vòng: ") + ChatColor.WHITE + (st.currentLap+1) + "/" + laps);
            java.util.List<java.util.UUID> order = rm.getLiveOrder();
            int pos = Math.max(1, order.indexOf(p.getUniqueId()) + 1);
            add(obj, i--, gray("Vị trí: ") + ChatColor.WHITE + pos + "/" + order.size());
            int percent = (int) Math.round(rm.getLapProgressRatio(p.getUniqueId()) * 100.0);
            add(obj, i--, gray("Tiến độ: ") + ChatColor.WHITE + percent + "%");
        }
        p.setScoreboard(sb);
    }

    private void applyCompletedBoard(Player p) {
        Scoreboard sb = newBoard();
        Objective obj = sidebar(sb, ChatColor.GOLD + "Kết quả");
        java.util.List<RaceManager.ParticipantState> standings = rm.getStandings();
        int i = 100;
        add(obj, i--, ChatColor.YELLOW + "Về đích");
        int shown = 0;
        for (RaceManager.ParticipantState s : standings) {
            if (!s.finished) continue;
            String name = nameOf(s.id);
            long t = Math.max(0L, s.finishTimeMillis - rm.getRaceStartMillis()) + s.penaltySeconds*1000L;
            add(obj, i--, ChatColor.WHITE.toString() + s.finishPosition + ") " + name);
            add(obj, i--, gray("  thời gian: ") + ChatColor.WHITE + fmt(t));
            shown += 2;
            if (shown >= 8) break; // avoid overflow
        }
        // Unfinished racers: live position
        add(obj, i--, ChatColor.YELLOW + "Đang đua");
        List<UUID> order = rm.getLiveOrder();
        int limit = 6;
        int count = 0;
        for (UUID id : order) {
            RaceManager.ParticipantState s = rm.getParticipantState(id);
            if (s == null || s.finished) continue;
            int pos = order.indexOf(id) + 1;
            String name = nameOf(id);
            add(obj, i--, ChatColor.WHITE.toString() + pos + ") " + name);
            int percent = (int) Math.round(rm.getLapProgressRatio(id) * 100.0);
            add(obj, i--, gray("  tiến độ: ") + ChatColor.WHITE + percent + "%");
            count += 2;
            if (count >= limit) break;
        }
        p.setScoreboard(sb);
    }

    private static Scoreboard newBoard() {
        return Bukkit.getScoreboardManager().getNewScoreboard();
    }

    private static Objective sidebar(Scoreboard sb, String title) {
        Objective prev = sb.getObjective("br");
        if (prev != null) prev.unregister();
        Objective obj = sb.registerNewObjective("br", "dummy", title);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        return obj;
    }

    private static void add(Objective obj, int score, String text) {
        String entry = text + ChatColor.values()[Math.min(Math.max(0, score % ChatColor.values().length), ChatColor.values().length-1)];
        obj.getScore(entry).setScore(score);
    }

    private static String gray(String s) { return ChatColor.GRAY + s; }
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
