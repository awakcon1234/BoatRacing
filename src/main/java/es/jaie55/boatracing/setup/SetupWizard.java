package es.jaie55.boatracing.setup;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.track.TrackConfig;
import es.jaie55.boatracing.track.SelectionManager;
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
        p.sendMessage(plugin.pref() + "§aSetup started. First, choose a track name using: /boatracing setup <name>");
    }

    public void status(Player p) {
        Step s = active.get(p.getUniqueId());
        if (s == null) p.sendMessage(plugin.pref() + "§7No active setup.");
        else p.sendMessage(plugin.pref() + "§eCurrent step: §f" + s.name());
    }

    public void cancel(Player p) {
        active.remove(p.getUniqueId());
        workingName.remove(p.getUniqueId());
        p.sendMessage(plugin.pref() + "§cSetup canceled.");
    }

    public void finish(Player p) {
        UUID uid = p.getUniqueId();
        String name = workingName.get(uid);
        if (name == null || name.isEmpty()) {
            p.sendMessage(plugin.pref() + "§cNo track name set. Use /boatracing setup <name>");
            return;
        }
        boolean ok = trackConfig.save(name);
        if (ok) p.sendMessage(plugin.pref() + "§aSaved track " + name);
        else p.sendMessage(plugin.pref() + "§cFailed to save track " + name);
        active.remove(uid);
        workingName.remove(uid);
    }

    public void back(Player p) {
        UUID uid = p.getUniqueId();
        Step s = active.get(uid);
        if (s == null) { p.sendMessage(plugin.pref() + "§7No active setup."); return; }
        if (s == Step.SELECT_TRACK) { cancel(p); return; }
        if (s == Step.ADD_STARTS) active.put(uid, Step.SELECT_TRACK);
        else if (s == Step.ADD_CHECKPOINTS) active.put(uid, Step.ADD_STARTS);
        else if (s == Step.SET_FINISH) active.put(uid, Step.ADD_CHECKPOINTS);
        p.sendMessage(plugin.pref() + "§aMoved back to " + active.get(uid));
    }

    public void skip(Player p) {
        // skip optional step (checkpoints can be skipped)
        UUID uid = p.getUniqueId();
        Step s = active.get(uid);
        if (s == null) { p.sendMessage(plugin.pref() + "§7No active setup."); return; }
        if (s == Step.ADD_CHECKPOINTS) {
            active.put(uid, Step.SET_FINISH);
            p.sendMessage(plugin.pref() + "§aSkipped checkpoints. Next: set finish.");
        } else p.sendMessage(plugin.pref() + "§7Nothing to skip here.");
    }

    public void next(Player p) {
        UUID uid = p.getUniqueId();
        Step s = active.get(uid);
        if (s == null) { p.sendMessage(plugin.pref() + "§7No active setup."); return; }
        if (s == Step.SELECT_TRACK) active.put(uid, Step.ADD_STARTS);
        else if (s == Step.ADD_STARTS) active.put(uid, Step.ADD_CHECKPOINTS);
        else if (s == Step.ADD_CHECKPOINTS) active.put(uid, Step.SET_FINISH);
        else if (s == Step.SET_FINISH) active.put(uid, Step.COMPLETE);
        p.sendMessage(plugin.pref() + "§aMoved to " + active.get(uid));
    }

    public boolean isActive(Player p) { return active.containsKey(p.getUniqueId()); }

    public void setWorkingName(Player p, String name) {
        workingName.put(p.getUniqueId(), name);
        p.sendMessage(plugin.pref() + "§aWorking track name: §f" + name);
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
                    p.sendMessage(plugin.pref() + "§aAdded start slot at selection corner A.");
                } else p.sendMessage(plugin.pref() + "§cSelect a location first using the wand.");
            }
            case ADD_CHECKPOINTS -> {
                var sel = SelectionManager.getSelection(p);
                if (sel != null && sel.a != null && sel.b != null) {
                    var box = new org.bukkit.util.BoundingBox(Math.min(sel.a.getX(), sel.b.getX()), Math.min(sel.a.getY(), sel.b.getY()), Math.min(sel.a.getZ(), sel.b.getZ()), Math.max(sel.a.getX(), sel.b.getX()), Math.max(sel.a.getY(), sel.b.getY()), Math.max(sel.a.getZ(), sel.b.getZ()));
                    plugin.getTrackConfig().addCheckpoint(new es.jaie55.boatracing.track.Region(sel.a.getWorld().getName(), box));
                    p.sendMessage(plugin.pref() + "§aAdded checkpoint region.");
                } else p.sendMessage(plugin.pref() + "§cSelect two corners with the wand to create a checkpoint.");
            }
            case SET_FINISH -> {
                var sel = SelectionManager.getSelection(p);
                if (sel != null && sel.a != null && sel.b != null) {
                    var box = new org.bukkit.util.BoundingBox(Math.min(sel.a.getX(), sel.b.getX()), Math.min(sel.a.getY(), sel.b.getY()), Math.min(sel.a.getZ(), sel.b.getZ()), Math.max(sel.a.getX(), sel.b.getX()), Math.max(sel.a.getY(), sel.b.getY()), Math.max(sel.a.getZ(), sel.b.getZ()));
                    plugin.getTrackConfig().setFinish(new es.jaie55.boatracing.track.Region(sel.a.getWorld().getName(), box));
                    p.sendMessage(plugin.pref() + "§aFinish region set.");
                } else p.sendMessage(plugin.pref() + "§cSelect two corners with the wand to set the finish region.");
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

