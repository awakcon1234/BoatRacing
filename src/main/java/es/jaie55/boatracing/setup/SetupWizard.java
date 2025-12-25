package dev.belikhun.boatracing.setup;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.track.SelectionManager;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * A small guided setup wizard for creating a Track. This is intentionally minimal —
 * it tracks per-player progress through a few steps and reacts to selection actions.
 */
public class SetupWizard {
    public enum Step { SELECT_TRACK, ADD_STARTS, ADD_CHECKPOINTS, SET_FINISH, COMPLETE }

    private final BoatRacingPlugin plugin;
    private final TrackConfig trackConfig;

    // per-player active step and working trackName
    private final Map<UUID, Step> active = new HashMap<>();
    private final Map<UUID, String> workingName = new HashMap<>();

    public SetupWizard(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        this.trackConfig = plugin.getTrackConfig();
    }

    // test-only constructor
    public SetupWizard(TrackConfig tc) {
        this.plugin = null;
        this.trackConfig = tc;
    }

    public void start(Player p) {
        active.put(p.getUniqueId(), Step.SELECT_TRACK);
        dev.belikhun.boatracing.util.Text.msg(p, "&aBắt đầu thiết lập. Trước tiên, chọn tên đường đua: /boatracing setup <name>");
    }

    public void status(Player p) {
        Step s = active.get(p.getUniqueId());
        if (s == null) dev.belikhun.boatracing.util.Text.msg(p, "&7Không có phiên thiết lập nào đang hoạt động.");
        else dev.belikhun.boatracing.util.Text.msg(p, "&eBước hiện tại: &f" + s.name());
    }

    public void cancel(Player p) {
        active.remove(p.getUniqueId());
        workingName.remove(p.getUniqueId());
        dev.belikhun.boatracing.util.Text.msg(p, "&cĐã hủy thiết lập.");
    }

    public void finish(Player p) {
        UUID uid = p.getUniqueId();
        String name = workingName.get(uid);
        if (name == null || name.isEmpty()) {
            dev.belikhun.boatracing.util.Text.msg(p, "&cChưa đặt tên đường đua. Dùng /boatracing setup <name>");
            return;
        }
        boolean ok = trackConfig.save(name);
        if (ok) dev.belikhun.boatracing.util.Text.msg(p, "&aĐã lưu đường đua " + name);
        else dev.belikhun.boatracing.util.Text.msg(p, "&cLưu đường đua thất bại: " + name);
        active.remove(uid);
        workingName.remove(uid);
    }

    public void back(Player p) {
        UUID uid = p.getUniqueId();
        Step s = active.get(uid);
        if (s == null) { dev.belikhun.boatracing.util.Text.msg(p, "&7Không có phiên thiết lập nào đang hoạt động."); return; }
        if (s == Step.SELECT_TRACK) { cancel(p); return; }
        if (s == Step.ADD_STARTS) active.put(uid, Step.SELECT_TRACK);
        else if (s == Step.ADD_CHECKPOINTS) active.put(uid, Step.ADD_STARTS);
        else if (s == Step.SET_FINISH) active.put(uid, Step.ADD_CHECKPOINTS);
        dev.belikhun.boatracing.util.Text.msg(p, "&aĐã quay lại: " + active.get(uid));
    }

    public void skip(Player p) {
        // skip optional step (checkpoints can be skipped)
        UUID uid = p.getUniqueId();
        Step s = active.get(uid);
        if (s == null) { dev.belikhun.boatracing.util.Text.msg(p, "&7Không có phiên thiết lập nào đang hoạt động."); return; }
        if (s == Step.ADD_CHECKPOINTS) {
            active.put(uid, Step.SET_FINISH);
            dev.belikhun.boatracing.util.Text.msg(p, "&aĐã bỏ qua checkpoint. Tiếp theo: đặt vạch đích.");
        } else dev.belikhun.boatracing.util.Text.msg(p, "&7Không có gì để bỏ qua ở đây.");
    }

    public void next(Player p) {
        UUID uid = p.getUniqueId();
        Step s = active.get(uid);
        if (s == null) { dev.belikhun.boatracing.util.Text.msg(p, "&7Không có phiên thiết lập nào đang hoạt động."); return; }
        if (s == Step.SELECT_TRACK) active.put(uid, Step.ADD_STARTS);
        else if (s == Step.ADD_STARTS) active.put(uid, Step.ADD_CHECKPOINTS);
        else if (s == Step.ADD_CHECKPOINTS) active.put(uid, Step.SET_FINISH);
        else if (s == Step.SET_FINISH) active.put(uid, Step.COMPLETE);
        dev.belikhun.boatracing.util.Text.msg(p, "&aĐã chuyển sang " + active.get(uid));
    }

    public boolean isActive(Player p) { return active.containsKey(p.getUniqueId()); }

    public void setWorkingName(Player p, String name) {
        workingName.put(p.getUniqueId(), name);
        dev.belikhun.boatracing.util.Text.msg(p, "&aTên đường đua đang làm việc: &f" + name);
    }

    public void afterAction(Player p) {
        // Called after player actions like setting selection corners or placing starts
        UUID uid = p.getUniqueId();
        Step s = active.get(uid);
        if (s == null) return;
        switch (s) {
            case ADD_STARTS -> {
                var sel = SelectionManager.getSelection(p);
                if (sel != null && sel.a != null) {
                    // use corner A as a start location
                    plugin.getTrackConfig().addStart(sel.a);
                    dev.belikhun.boatracing.util.Text.msg(p, "&aĐã thêm vị trí xuất phát tại &f" + dev.belikhun.boatracing.util.Text.fmtPos(sel.a));
                } else dev.belikhun.boatracing.util.Text.msg(p, "&cHãy chọn vị trí trước bằng gậy chọn.");
            }
            case ADD_CHECKPOINTS -> {
                var sel = SelectionManager.getSelection(p);
                if (sel != null && sel.a != null && sel.b != null) {
                    var box = new org.bukkit.util.BoundingBox(Math.min(sel.a.getX(), sel.b.getX()), Math.min(sel.a.getY(), sel.b.getY()), Math.min(sel.a.getZ(), sel.b.getZ()), Math.max(sel.a.getX(), sel.b.getX()), Math.max(sel.a.getY(), sel.b.getY()), Math.max(sel.a.getZ(), sel.b.getZ()));
                    plugin.getTrackConfig().addCheckpoint(new dev.belikhun.boatracing.track.Region(sel.a.getWorld().getName(), box));
                    dev.belikhun.boatracing.util.Text.msg(p, "&aĐã thêm vùng checkpoint: &f" + dev.belikhun.boatracing.util.Text.fmtArea(sel.a.getWorld().getName(), box));
                } else dev.belikhun.boatracing.util.Text.msg(p, "&cHãy chọn hai góc bằng gậy để tạo checkpoint.");
            }
            case SET_FINISH -> {
                var sel = SelectionManager.getSelection(p);
                if (sel != null && sel.a != null && sel.b != null) {
                    var box = new org.bukkit.util.BoundingBox(Math.min(sel.a.getX(), sel.b.getX()), Math.min(sel.a.getY(), sel.b.getY()), Math.min(sel.a.getZ(), sel.b.getZ()), Math.max(sel.a.getX(), sel.b.getX()), Math.max(sel.a.getY(), sel.b.getY()), Math.max(sel.a.getZ(), sel.b.getZ()));
                    plugin.getTrackConfig().setFinish(new dev.belikhun.boatracing.track.Region(sel.a.getWorld().getName(), box));
                    dev.belikhun.boatracing.util.Text.msg(p, "&aĐã đặt vùng vạch đích: &f" + dev.belikhun.boatracing.util.Text.fmtArea(sel.a.getWorld().getName(), box));
                } else dev.belikhun.boatracing.util.Text.msg(p, "&cHãy chọn hai góc bằng gậy để đặt vùng vạch đích.");
            }
            default -> {}
        }
    }

    // test helpers
    void startFor(UUID uid) { active.put(uid, Step.SELECT_TRACK); }
    void setWorkingNameFor(UUID uid, String name) { workingName.put(uid, name); }
    Step activeStep(UUID uid) { return active.get(uid); }

    // Test-only: finish without a Player
    boolean finishFor(UUID uid) {
        String name = workingName.get(uid);
        if (name == null || name.isEmpty()) return false;
        boolean ok = trackConfig.save(name);
        if (ok) { active.remove(uid); workingName.remove(uid); }
        return ok;
    }
}


