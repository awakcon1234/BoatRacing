package es.jaie55.boatracing;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
// No TabExecutor needed: JavaPlugin already handles CommandExecutor and TabCompleter when overriding methods
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import es.jaie55.boatracing.team.TeamManager;
import es.jaie55.boatracing.ui.TeamGUI;
import es.jaie55.boatracing.util.Text;
import es.jaie55.boatracing.update.UpdateChecker;
import es.jaie55.boatracing.update.UpdateNotifier;
import es.jaie55.boatracing.track.TrackConfig;
import es.jaie55.boatracing.track.TrackLibrary;
import es.jaie55.boatracing.track.Region;
import es.jaie55.boatracing.track.SelectionUtils;
import es.jaie55.boatracing.race.RaceManager;
import es.jaie55.boatracing.setup.SetupWizard;

public class BoatRacingPlugin extends JavaPlugin {
    private static BoatRacingPlugin instance;
    private TeamManager teamManager;
    private TeamGUI teamGUI;
    private es.jaie55.boatracing.ui.AdminGUI adminGUI;
    private String prefix;
    private UpdateChecker updateChecker;
    private TrackConfig trackConfig;
    private TrackLibrary trackLibrary;
    private RaceManager raceManager;
    private SetupWizard setupWizard;
    private es.jaie55.boatracing.ui.AdminTracksGUI tracksGUI;
    // Track pending disband confirmations per player
    private final java.util.Set<java.util.UUID> pendingDisband = new java.util.HashSet<>();
    private final java.util.Map<java.util.UUID, java.util.UUID> pendingTransfer = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, java.util.UUID> pendingKick = new java.util.HashMap<>();

    public static BoatRacingPlugin getInstance() { return instance; }
    public TeamManager getTeamManager() { return teamManager; }
    public String pref() { return prefix; }
    public es.jaie55.boatracing.ui.AdminGUI getAdminGUI() { return adminGUI; }
    public es.jaie55.boatracing.ui.TeamGUI getTeamGUI() { return teamGUI; }
    public RaceManager getRaceManager() { return raceManager; }
    public TrackConfig getTrackConfig() { return trackConfig; }
    public TrackLibrary getTrackLibrary() { return trackLibrary; }
    public es.jaie55.boatracing.ui.AdminTracksGUI getTracksGUI() { return tracksGUI; }
    

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
        this.teamManager = new TeamManager(this);
    this.teamGUI = new TeamGUI(this);
    this.adminGUI = new es.jaie55.boatracing.ui.AdminGUI(this);
    this.trackConfig = new TrackConfig(getDataFolder());
    this.trackLibrary = new TrackLibrary(getDataFolder(), trackConfig);
    this.raceManager = new RaceManager(this, trackConfig);
    this.setupWizard = new SetupWizard(this);
    this.tracksGUI = new es.jaie55.boatracing.ui.AdminTracksGUI(this, trackLibrary);
    Bukkit.getPluginManager().registerEvents(teamGUI, this);
    Bukkit.getPluginManager().registerEvents(adminGUI, this);
    Bukkit.getPluginManager().registerEvents(tracksGUI, this);
    
    es.jaie55.boatracing.track.SelectionManager.init(this);
    Bukkit.getPluginManager().registerEvents(new es.jaie55.boatracing.track.WandListener(this), this);
    // Movement listener for race tracking
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
                if (raceManager == null || !raceManager.isRunning()) return;
                if (e.getTo() == null) return;
                raceManager.tickPlayer(e.getPlayer(), e.getTo());
            }
        }, this);
    
        try {
            boolean metricsEnabled = getConfig().getBoolean("bstats.enabled", true);
            if (metricsEnabled) {
                final int pluginId = 26881; // fixed bStats plugin id
                new org.bstats.bukkit.Metrics(this, pluginId);
                getLogger().info("Starting Metrics. Opt-out using the global bStats config.");
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to initialize bStats metrics: " + t.getMessage());
        }

    // Updates
    if (getConfig().getBoolean("updates.enabled", true)) {
            String currentVersion = getPluginMeta().getVersion();
            updateChecker = new UpdateChecker(this, "Jaie55", "BoatRacing", currentVersion);
            updateChecker.checkAsync();
            // Post-result console notice (delayed)
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (updateChecker.isChecked() && updateChecker.isOutdated()) {
                    int behind = updateChecker.getBehindCount();
                    String current = currentVersion;
                    String latest = updateChecker.getLatestVersion() != null ? updateChecker.getLatestVersion() : "latest";
                    if (getConfig().getBoolean("updates.console-warn", true)) {
                        Bukkit.getLogger().warning("[" + getName() + "] An update is available. You are " + behind + " version(s) out of date.");
                        Bukkit.getLogger().warning("[" + getName() + "] You are running " + current + ", the latest version is " + latest + ".");
                        Bukkit.getLogger().warning("[" + getName() + "] Update at " + updateChecker.getLatestUrl());
                    }
                }
            }, 20L * 5); // ~5s after enable
            // In-game notifications for admins
            if (getConfig().getBoolean("updates.notify-admins", true)) {
                Bukkit.getPluginManager().registerEvents(new UpdateNotifier(this, updateChecker, prefix), this);
            }
            // Periodic update checks every 5 minutes
            long period = 20L * 60L * 5L; // 5 minutes
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    if (getConfig().getBoolean("updates.enabled", true)) {
                        updateChecker.checkAsync();
                    }
                } catch (Throwable ignored) {}
            }, period, period);
        }

    if (getCommand("boatracing") != null) {
            getCommand("boatracing").setExecutor(this);
            getCommand("boatracing").setTabCompleter(this);
        }
    getLogger().info("BoatRacing enabled");
    }

    @Override
    public void onDisable() {
        if (teamManager != null) teamManager.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Text.colorize(prefix + "&cPlayers only."));
            return true;
        }
        Player p = (Player) sender;
        if (command.getName().equalsIgnoreCase("boatracing")) {
            if (args.length == 0) {
                p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " teams|setup|reload|version"));
                return true;
            }
            // /boatracing version
            if (args[0].equalsIgnoreCase("version")) {
                if (!p.hasPermission("boatracing.version")) {
                    p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                String current = getPluginMeta().getVersion();
                java.util.List<String> authors = getPluginMeta().getAuthors();
                p.sendMessage(Text.colorize(prefix + "&e" + getName() + "-" + current));
                if (!authors.isEmpty()) {
                    p.sendMessage(Text.colorize(prefix + "&eAuthors: &f" + String.join(", ", authors)));
                }
                

                boolean updatesEnabled = getConfig().getBoolean("updates.enabled", true);
                if (!updatesEnabled) {
                    p.sendMessage(Text.colorize(prefix + "&7Update checks are disabled in the config."));
                    return true;
                }

                // Ensure we have a checker and run one if needed
                if (updateChecker == null) {
                    updateChecker = new UpdateChecker(this, "Jaie55", "BoatRacing", current);
                }
                if (!updateChecker.isChecked()) {
                    p.sendMessage(Text.colorize(prefix + "&7Checking for updates..."));
                    updateChecker.checkAsync();
                    // Poll a couple of times to deliver result to the user shortly after
                    Bukkit.getScheduler().runTaskLater(this, () -> sendUpdateStatus(p), 40L);
                    Bukkit.getScheduler().runTaskLater(this, () -> sendUpdateStatus(p), 100L);
                    return true;
                }
                // Already have a result
                sendUpdateStatus(p);
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                if (!p.hasPermission("boatracing.reload")) {
                    p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                // Persist current state, reload config and data
                if (teamManager != null) teamManager.save();
                reloadConfig();
                this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
                // Recreate team manager to re-read data and settings
                this.teamManager = new TeamManager(this);
                p.sendMessage(Text.colorize(prefix + "&aPlugin reloaded."));
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return true;
            }
            // /boatracing race
            if (args[0].equalsIgnoreCase("race")) {
                if (!p.hasPermission("boatracing.setup")) {
                    p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
                    p.sendMessage(Text.colorize(prefix + "&eRace commands:"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race open [laps] &7(Open registration and broadcast)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race join &7(Join the registration; team required)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race leave &7(Leave the registration)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race force &7(Force start immediately with registered)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race start [laps] &7(Start now with registered players; if none, all online players in a team)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race stop &7(Stop and announce results)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race status &7(Show race status)"));
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "open" -> {
                        if (!trackConfig.isReady()) {
                            p.sendMessage(Text.colorize(prefix + "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements())));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        int laps = raceManager.getTotalLaps();
                        if (args.length >= 3 && args[2].matches("\\d+")) laps = Math.max(1, Integer.parseInt(args[2]));
                        boolean ok = raceManager.openRegistration(laps, null);
                        if (!ok) p.sendMessage(Text.colorize(prefix + "&cCannot open registration right now."));
                        return true;
                    }
                    case "join" -> {
                        if (!trackConfig.isReady()) {
                            p.sendMessage(Text.colorize(prefix + "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements())));
                            return true;
                        }
                        // Must be in a team
                        if (teamManager.getTeamByMember(p.getUniqueId()).isEmpty()) {
                            p.sendMessage(Text.colorize(prefix + "&cYou must be in a team to join the race."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (!raceManager.join(p)) {
                            p.sendMessage(Text.colorize(prefix + "&cRegistration is not open."));
                        }
                        return true;
                    }
                    case "leave" -> { raceManager.leave(p); return true; }
                    case "force" -> { raceManager.forceStart(); return true; }
                    case "start" -> {
                        if (!trackConfig.isReady()) {
                            p.sendMessage(Text.colorize(prefix + "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements())));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (raceManager.isRunning()) { p.sendMessage(Text.colorize(prefix + "&cRace already running.")); return true; }
                        int laps = raceManager.getTotalLaps();
                        if (args.length >= 3 && args[2].matches("\\d+")) {
                            laps = Math.max(1, Integer.parseInt(args[2]));
                            raceManager.setTotalLaps(laps);
                        }
                        // Build participants: prefer registered; otherwise only players who are in a team
                        java.util.List<org.bukkit.entity.Player> participants = new java.util.ArrayList<>();
                        java.util.Set<java.util.UUID> regs = new java.util.LinkedHashSet<>(raceManager.getRegistered());
                        if (!regs.isEmpty()) {
                            for (java.util.UUID id : regs) {
                                org.bukkit.entity.Player rp = Bukkit.getPlayer(id);
                                if (rp != null && rp.isOnline()) participants.add(rp);
                            }
                        } else {
                            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                                if (teamManager.getTeamByMember(online.getUniqueId()).isPresent()) participants.add(online);
                            }
                        }
                        if (participants.isEmpty()) {
                            p.sendMessage(Text.colorize(prefix + "&cNo eligible participants. &7Ask players to join a team or register."));
                            return true;
                        }
                        // Place with boats and start
                        raceManager.placeAtStartsWithBoats(participants);
                        raceManager.startRace(participants);
                        return true;
                    }
                    case "stop" -> { raceManager.stopRace(true); return true; }
                    case "status" -> {
                        String tname = (getTrackLibrary() != null && getTrackLibrary().getCurrent() != null) ? getTrackLibrary().getCurrent() : "(unsaved)";
                        p.sendMessage(Text.colorize(prefix + (raceManager.isRunning()?"&aRace running.":"&7No race running.")));
                        p.sendMessage(Text.colorize("&7Track: &f" + tname));
                        return true;
                    }
                    default -> { p.sendMessage(Text.colorize(prefix + "&cUnknown race subcommand. Use /" + label + " race help")); return true; }
                }
            }
            // /boatracing setup
            if (args[0].equalsIgnoreCase("setup")) {
                if (!p.hasPermission("boatracing.setup")) {
                    p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
                    p.sendMessage(Text.colorize(prefix + "&eSetup commands:"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup addstart &7(Add your current position as a start slot)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup clearstarts &7(Remove all start slots)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup setfinish &7(Use your BoatRacing selection for the finish line region)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup setpit &7(Use your BoatRacing selection for the pit lane region)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup addcheckpoint &7(Add a checkpoint from your selection; order matters)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup clearcheckpoints &7(Remove all checkpoints)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup show &7(Summary of current track config)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup selinfo &7(Selection debug: current BoatRacing selection)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup wand &7(Give the BoatRacing selection tool)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup wizard start &7(Launch guided setup assistant)"));
                    return true;
                }
                String sub = args[1].toLowerCase();
                switch (sub) {
                    case "wand" -> {
                        es.jaie55.boatracing.track.SelectionManager.giveWand(p);
                        p.sendMessage(Text.colorize(prefix + "&aSelector ready. &7Left-click marks &fCorner A&7; right-click marks &fCorner B&7."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.9f, 1.2f);
                        return true;
                    }
                    case "wizard" -> {
                        if (args.length < 3) {
                            p.sendMessage(Text.colorize(prefix + "&eSetup Wizard:"));
                            p.sendMessage(Text.colorize("&7Use &f/" + label + " setup wizard &7<start|back|status|cancel>"));
                            p.sendMessage(Text.colorize("&7The wizard auto-advances when each step is completed."));
                            return true;
                        }
                        String wop = args[2].toLowerCase();
                        switch (wop) {
                            case "start" -> { setupWizard.start(p); return true; }
                            case "next" -> { setupWizard.next(p); return true; }
                            case "back" -> { setupWizard.back(p); return true; }
                            case "status" -> { setupWizard.status(p); return true; }
                            case "cancel" -> { setupWizard.cancel(p); return true; }
                            default -> { p.sendMessage(Text.colorize(prefix + "&cUnknown wizard action. Use start|next|back|status|cancel")); return true; }
                        }
                    }
                    case "addstart" -> {
                        org.bukkit.Location loc = p.getLocation();
                        trackConfig.addStart(loc);
                        p.sendMessage(Text.colorize(prefix + "&aAdded start slot at &f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + " &7(" + loc.getWorld().getName() + ")"));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "clearstarts" -> {
                        trackConfig.clearStarts();
                        p.sendMessage(Text.colorize(prefix + "&aCleared all start slots."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "setfinish" -> {
                        var sel = SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            p.sendMessage(Text.colorize(prefix + "&cNo selection detected. Use the selection tool to mark &fCorner A&c (left-click) and &fCorner B&c (right-click)."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        Region r = new Region(sel.worldName, sel.box);
                        trackConfig.setFinish(r);
                        p.sendMessage(Text.colorize(prefix + "&aFinish region set (" + fmtBox(sel.box) + ")"));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.3f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "setpit" -> {
                        var sel = SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            p.sendMessage(Text.colorize(prefix + "&cNo selection detected. Use the selection tool to mark &fCorner A&c (left-click) and &fCorner B&c (right-click)."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        Region r = new Region(sel.worldName, sel.box);
                        trackConfig.setPitlane(r);
                        p.sendMessage(Text.colorize(prefix + "&aPit lane region set (" + fmtBox(sel.box) + ")"));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "addcheckpoint" -> {
                        var sel = SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            p.sendMessage(Text.colorize(prefix + "&cNo selection detected. Use the selection tool to mark &fCorner A&c (left-click) and &fCorner B&c (right-click)."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        Region r = new Region(sel.worldName, sel.box);
                        trackConfig.addCheckpoint(r);
                        p.sendMessage(Text.colorize(prefix + "&aAdded checkpoint #&f" + trackConfig.getCheckpoints().size() + " &7(" + fmtBox(sel.box) + ")"));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "clearcheckpoints" -> {
                        trackConfig.clearCheckpoints();
                        p.sendMessage(Text.colorize(prefix + "&aCleared all checkpoints."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "show" -> {
                        int starts = trackConfig.getStarts().size();
                        int cps = trackConfig.getCheckpoints().size();
                        boolean hasFinish = trackConfig.getFinish() != null;
                        boolean hasPit = trackConfig.getPitlane() != null;
                        p.sendMessage(Text.colorize(prefix + "&eTrack config:"));
                        String tname = (getTrackLibrary() != null && getTrackLibrary().getCurrent() != null) ? getTrackLibrary().getCurrent() : "(unsaved)";
                        p.sendMessage(Text.colorize("&7 - &fTrack: &e" + tname));
                        p.sendMessage(Text.colorize("&7 - &fStarts: &e" + starts));
                        p.sendMessage(Text.colorize("&7 - &fFinish: &e" + (hasFinish ? "yes" : "no")));
                        p.sendMessage(Text.colorize("&7 - &fPit lane: &e" + (hasPit ? "yes" : "no")));
                        p.sendMessage(Text.colorize("&7 - &fCheckpoints: &e" + cps));
                    }
                    case "selinfo" -> {
                        java.util.List<String> dump = SelectionUtils.debugSelection(p);
                        p.sendMessage(Text.colorize(prefix + "&eSelection info:"));
                        for (String line : dump) p.sendMessage(Text.colorize("&7 - &f" + line));
                    }
                    default -> p.sendMessage(Text.colorize(prefix + "&cUnknown setup subcommand. Use /" + label + " setup help"));
                }
                return true;
            }
            // /boatracing admin
            if (args[0].equalsIgnoreCase("admin")) {
                if (!p.hasPermission("boatracing.admin")) {
                    p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                    return true;
                }
                if (args.length == 1) {
                    // Open Admin GUI by default
                    adminGUI.openMain(p);
                    return true;
                }
                if (args[1].equalsIgnoreCase("help")) {
                    p.sendMessage(Text.colorize(prefix + "&eAdmin commands:"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin team create <name> [color] [firstMember]"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin team delete <name>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin team rename <old> <new>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin team color <name> <DyeColor>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin team add <name> <player>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin team remove <name> <player>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin player setteam <player> <team|none>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin player setnumber <player> <1-99>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin player setboat <player> <BoatType>"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " admin tracks &7(Manage named tracks via GUI)"));
                    return true;
                }
                if (args[1].equalsIgnoreCase("tracks")) {
                    if (!p.hasPermission("boatracing.setup")) { p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that.")); return true; }
                    tracksGUI.open(p);
                    return true;
                }
                // admin team ...
                if (args[1].equalsIgnoreCase("team")) {
                    if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin team <create|delete|rename|color|add|remove>")); return true; }
                    String op = args[2].toLowerCase();
                    switch (op) {
                        case "create" -> {
                            if (args.length < 4) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin team create <name> [color] [firstMember]")); return true; }
                            String name = args[3];
                            org.bukkit.DyeColor color = org.bukkit.DyeColor.WHITE;
                            if (args.length >= 5) {
                                try { color = org.bukkit.DyeColor.valueOf(args[4].toUpperCase()); } catch (Exception ex) { p.sendMessage(Text.colorize(prefix + "&cInvalid color.")); return true; }
                            }
                            java.util.UUID firstMemberId = p.getUniqueId();
                            if (args.length >= 6) {
                                org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[5]);
                                if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                                firstMemberId = off.getUniqueId();
                            }
                            if (teamManager.findByName(name).isPresent()) { p.sendMessage(Text.colorize(prefix + "&cA team with that name already exists.")); return true; }
                            teamManager.createTeam(firstMemberId, name, color);
                            p.sendMessage(Text.colorize(prefix + "&aTeam created: &f" + name + " &7(color: " + color.name() + ")"));
                            return true;
                        }
                        case "delete" -> {
                            if (args.length < 4) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin team delete <name>")); return true; }
                            String name = args[3];
                            var ot = teamManager.findByName(name);
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                            // Notify members before deletion
                            java.util.List<java.util.UUID> members = new java.util.ArrayList<>(ot.get().getMembers());
                            teamManager.removeTeam(ot.get());
                            p.sendMessage(Text.colorize(prefix + "&aTeam deleted: &f" + name));
                            for (java.util.UUID m : members) {
                                org.bukkit.OfflinePlayer memOp = Bukkit.getOfflinePlayer(m);
                                if (memOp.isOnline() && memOp.getPlayer() != null) {
                                    memOp.getPlayer().sendMessage(Text.colorize(prefix + "&eYour team has been deleted."));
                                    memOp.getPlayer().playSound(memOp.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.6f, 0.9f);
                                }
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "rename" -> {
                            if (args.length < 5) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin team rename <old> <new>")); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                            String newName = args[4];
                            if (teamManager.findByName(newName).isPresent()) { p.sendMessage(Text.colorize(prefix + "&cA team with that name already exists.")); return true; }
                            ot.get().setName(newName);
                            teamManager.save();
                            p.sendMessage(Text.colorize(prefix + "&aTeam renamed to &f" + newName));
                            for (java.util.UUID m : ot.get().getMembers()) {
                                org.bukkit.OfflinePlayer memOp = Bukkit.getOfflinePlayer(m);
                                if (memOp.isOnline() && memOp.getPlayer() != null) {
                                    memOp.getPlayer().sendMessage(Text.colorize(prefix + "&eAn administrator has renamed your team to &f" + newName + "&e."));
                                    memOp.getPlayer().playSound(memOp.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                                }
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "color" -> {
                            if (args.length < 5) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin team color <name> <DyeColor>")); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                            org.bukkit.DyeColor color;
                            try { color = org.bukkit.DyeColor.valueOf(args[4].toUpperCase()); } catch (Exception ex) { p.sendMessage(Text.colorize(prefix + "&cInvalid color.")); return true; }
                            ot.get().setColor(color);
                            teamManager.save();
                            p.sendMessage(Text.colorize(prefix + "&aTeam color updated: &f" + color.name()));
                            for (java.util.UUID m : ot.get().getMembers()) {
                                org.bukkit.OfflinePlayer memOp = Bukkit.getOfflinePlayer(m);
                                if (memOp.isOnline() && memOp.getPlayer() != null) {
                                    memOp.getPlayer().sendMessage(Text.colorize(prefix + "&eAn administrator has changed your team color to &f" + color.name() + "&e."));
                                    memOp.getPlayer().playSound(memOp.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                                }
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "add" -> {
                            if (args.length < 5) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin team add <name> <player>")); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[4]);
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                            // remove from previous team if any
                            teamManager.getTeamByMember(off.getUniqueId()).ifPresent(prev -> { prev.removeMember(off.getUniqueId()); });
                            boolean ok = teamManager.addMember(ot.get(), off.getUniqueId());
                            if (!ok) { p.sendMessage(Text.colorize(prefix + "&cTeam is full (max " + teamManager.getMaxMembers() + ")")); return true; }
                            teamManager.save();
                            p.sendMessage(Text.colorize(prefix + "&aAdded &f" + off.getName() + " &ato team &f" + ot.get().getName()));
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eAn administrator has added you to team &f" + ot.get().getName() + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "remove" -> {
                            if (args.length < 5) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin team remove <name> <player>")); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[4]);
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                            boolean ok = teamManager.removeMember(ot.get(), off.getUniqueId());
                            if (!ok) { p.sendMessage(Text.colorize(prefix + "&cPlayer is not a member of that team.")); return true; }
                            p.sendMessage(Text.colorize(prefix + "&aRemoved &f" + off.getName() + " &afrom team &f" + ot.get().getName()));
                            teamManager.save();
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eYou have been removed from team &f" + ot.get().getName() + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        default -> { p.sendMessage(Text.colorize(prefix + "&cUnknown team op.")); return true; }
                    }
                }
                // admin player ...
                if (args[1].equalsIgnoreCase("player")) {
                    if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin player <setteam|setnumber|setboat>")); return true; }
                    String op = args[2].toLowerCase();
                    switch (op) {
                        case "setteam" -> {
                            if (args.length < 5) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin player setteam <player> <team|none>")); return true; }
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[3]);
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                            String teamName = args[4];
                            teamManager.getTeamByMember(off.getUniqueId()).ifPresent(prev -> prev.removeMember(off.getUniqueId()));
                            if (!teamName.equalsIgnoreCase("none")) {
                                var ot = teamManager.findByName(teamName);
                                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                                if (!teamManager.addMember(ot.get(), off.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cTeam is full (max " + teamManager.getMaxMembers() + ")")); return true; }
                            }
                            teamManager.save();
                            p.sendMessage(Text.colorize(prefix + "&aPlayer &f" + off.getName() + " &aassigned to team &f" + (teamName.equalsIgnoreCase("none")?"none":teamName)));
                            if (off.isOnline() && off.getPlayer() != null) {
                                if (teamName.equalsIgnoreCase("none")) {
                                    off.getPlayer().sendMessage(Text.colorize(prefix + "&eYou have been removed from your team."));
                                    off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                                } else {
                                    off.getPlayer().sendMessage(Text.colorize(prefix + "&eAn administrator has assigned you to team &f" + teamName + "&e."));
                                    off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                                }
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "setnumber" -> {
                            if (args.length < 5) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin player setnumber <player> <1-99>")); return true; }
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[3]);
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                            int num;
                            try { num = Integer.parseInt(args[4]); } catch (Exception ex) { p.sendMessage(Text.colorize(prefix + "&cInvalid number.")); return true; }
                            if (num < 1 || num > 99) { p.sendMessage(Text.colorize(prefix + "&cNumber must be 1-99.")); return true; }
                            var ot = teamManager.getTeamByMember(off.getUniqueId());
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cPlayer is not in a team.")); return true; }
                            // Optional: global uniqueness check could go here
                            ot.get().setRacerNumber(off.getUniqueId(), num);
                            teamManager.save();
                            p.sendMessage(Text.colorize(prefix + "&aSet racer number for &f" + off.getName() + " &ato &f" + num));
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eAn administrator has changed your racer number to &f" + num + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "setboat" -> {
                            if (args.length < 5) { p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin player setboat <player> <BoatType>")); return true; }
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[3]);
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                            String type = args[4].toUpperCase();
                            // Validate against allowed boats (vanilla boats and chest boats)
                            java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
                            for (org.bukkit.Material m : org.bukkit.Material.values()) {
                                String n = m.name();
                                if (n.endsWith("_BOAT") || n.endsWith("_CHEST_BOAT")) allowed.add(n);
                            }
                            if (!allowed.contains(type)) { p.sendMessage(Text.colorize(prefix + "&cInvalid boat type.")); return true; }
                            var ot = teamManager.getTeamByMember(off.getUniqueId());
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cPlayer is not in a team.")); return true; }
                            ot.get().setBoatType(off.getUniqueId(), type);
                            teamManager.save();
                            p.sendMessage(Text.colorize(prefix + "&aSet boat type for &f" + off.getName() + " &ato &f" + type));
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eAn administrator has changed your boat to &f" + type + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        default -> { p.sendMessage(Text.colorize(prefix + "&cUnknown player op.")); return true; }
                    }
                }
                p.sendMessage(Text.colorize(prefix + "&cUsage: /"+label+" admin help"));
                return true;
            }
            if (!args[0].equalsIgnoreCase("teams")) {
                p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " teams|setup|reload|version"));
                return true;
            }
            // /boatracing teams
            if (args.length == 1) {
                if (!p.hasPermission("boatracing.teams")) {
                    p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                teamGUI.openMain(p);
                return true;
            }
            // /boatracing teams create <name>
            if (!p.hasPermission("boatracing.teams")) {
                p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("create")) {
                boolean allowCreate = getConfig().getBoolean("player-actions.allow-team-create", true);
                if (!allowCreate) { p.sendMessage(Text.colorize(prefix + "&cThis server has restricted team creation. Only an administrator can create teams.")); return true; }
                if (teamManager.getTeamByMember(p.getUniqueId()).isPresent()) {
                    p.sendMessage(Text.colorize(prefix + "&cYou are already in a team. Leave it first."));
                    return true;
                }
                if (args.length < 3) {
                    p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams create <name>"));
                    return true;
                }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                String err = es.jaie55.boatracing.ui.TeamGUI.validateNameMessage(name);
                if (err != null) {
                    p.sendMessage(Text.colorize(prefix + "&c" + err));
                    return true;
                }
                boolean exists = teamManager.getTeams().stream().anyMatch(t -> t.getName().equalsIgnoreCase(name));
                if (exists) {
                    p.sendMessage(Text.colorize(prefix + "&cA team with that name already exists."));
                    return true;
                }
                teamManager.createTeam(p, name, org.bukkit.DyeColor.WHITE);
                return true;
            }
            // /boatracing teams rename <new name>
            if (args.length >= 2 && args[1].equalsIgnoreCase("rename")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                es.jaie55.boatracing.team.Team t = ot.get();
                boolean allowRename = getConfig().getBoolean("player-actions.allow-team-rename", false);
                if (!allowRename && !p.hasPermission("boatracing.admin")) { p.sendMessage(Text.colorize(prefix + "&cThis server has restricted team renaming. Only an administrator can rename teams.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams rename <new name>")); return true; }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                String err = es.jaie55.boatracing.ui.TeamGUI.validateNameMessage(name);
                if (err != null) { p.sendMessage(Text.colorize(prefix + "&c" + err)); return true; }
                boolean exists = teamManager.getTeams().stream().anyMatch(tt -> tt != t && tt.getName().equalsIgnoreCase(name));
                if (exists) { p.sendMessage(Text.colorize(prefix + "&cA team with that name already exists.")); return true; }
                t.setName(name); teamManager.save();
                p.sendMessage(Text.colorize(prefix + "&aTeam renamed to &e" + name + "&a."));
                return true;
            }
            // /boatracing teams color <dyeColor>
            if (args.length >= 2 && args[1].equalsIgnoreCase("color")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                es.jaie55.boatracing.team.Team t = ot.get();
                boolean allowColor = getConfig().getBoolean("player-actions.allow-team-color", false);
                if (!allowColor && !p.hasPermission("boatracing.admin")) { p.sendMessage(Text.colorize(prefix + "&cThis server has restricted team colors. Only an administrator can change team colors.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams color <dyeColor>")); return true; }
                try {
                    org.bukkit.DyeColor dc = org.bukkit.DyeColor.valueOf(args[2].toUpperCase());
                    t.setColor(dc); teamManager.save();
                    p.sendMessage(Text.colorize(prefix + "&aTeam color set to &e" + dc.name() + "&a."));
                } catch (IllegalArgumentException ex) {
                    p.sendMessage(Text.colorize(prefix + "&cInvalid color."));
                }
                return true;
            }
            // /boatracing teams join <team name>
            if (args.length >= 2 && args[1].equalsIgnoreCase("join")) {
                if (teamManager.getTeamByMember(p.getUniqueId()).isPresent()) {
                    p.sendMessage(Text.colorize(prefix + "&cYou are already in a team. Leave it first."));
                    return true;
                }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams join <team name>")); return true; }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                es.jaie55.boatracing.team.Team target = teamManager.getTeams().stream()
                    .filter(t -> t.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
                if (target == null) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                if (target.getMembers().size() >= teamManager.getMaxMembers()) { p.sendMessage(Text.colorize(prefix + "&cThis team is full.")); return true; }
                target.addMember(p.getUniqueId()); teamManager.save();
                p.sendMessage(Text.colorize(prefix + "&aYou joined &e" + target.getName() + "&a."));
                for (java.util.UUID m : target.getMembers()) {
                    if (m.equals(p.getUniqueId())) continue;
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline() && op.getPlayer() != null) {
                        op.getPlayer().sendMessage(Text.colorize(prefix + "&e" + p.getName() + " joined the team."));
                    }
                }
                return true;
            }
            // /boatracing teams leave
            if (args.length >= 2 && args[1].equalsIgnoreCase("leave")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                es.jaie55.boatracing.team.Team t = ot.get();
                // Allow leaving even if last member; delete team if it becomes empty
                t.removeMember(p.getUniqueId());
                if (t.getMembers().isEmpty()) {
                    teamManager.deleteTeam(t);
                    p.sendMessage(Text.colorize(prefix + "&aYou left and your team was deleted (no members left)."));
                } else {
                    teamManager.save();
                    p.sendMessage(Text.colorize(prefix + "&aYou left the team."));
                    for (java.util.UUID m : t.getMembers()) {
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                        if (op.isOnline() && op.getPlayer() != null) {
                            op.getPlayer().sendMessage(Text.colorize(prefix + "&e" + p.getName() + " left the team."));
                        }
                    }
                }
                return true;
            }
            // /boatracing teams kick <playerName> (with confirm)
            if (args.length >= 2 && args[1].equalsIgnoreCase("kick")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                p.sendMessage(Text.colorize(prefix + "&cThis action is admin-only. Use /"+label+" admin team remove <team> <player>."));
                return true;
            }
            // /boatracing teams transfer <playerName>
            if (args.length >= 2 && args[1].equalsIgnoreCase("transfer")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                p.sendMessage(Text.colorize(prefix + "&cLeader system has been removed. Use admin commands if you need to manage teams."));
                return true;
            }
            // /boatracing teams boat <type>
            if (args.length >= 2 && args[1].equalsIgnoreCase("boat")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams boat <type>")); return true; }
                boolean allowBoat = getConfig().getBoolean("player-actions.allow-set-boat", true);
                if (!allowBoat) { p.sendMessage(Text.colorize(prefix + "&cThis server has restricted boat changes. Only an administrator can set your boat.")); return true; }
                String type = args[2].toUpperCase();
                try {
                    org.bukkit.Material m = org.bukkit.Material.valueOf(type);
                    if (!m.name().endsWith("BOAT")) throw new IllegalArgumentException();
                    ot.get().setBoatType(p.getUniqueId(), m.name());
                    teamManager.save();
                    p.sendMessage(Text.colorize(prefix + "&aYour boat set to &e" + type.toLowerCase() + "&a."));
                } catch (IllegalArgumentException ex) {
                    p.sendMessage(Text.colorize(prefix + "&cInvalid boat type."));
                }
                return true;
            }
            // /boatracing teams number <1-99>
            if (args.length >= 2 && args[1].equalsIgnoreCase("number")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams number <1-99>")); return true; }
                boolean allowNumber = getConfig().getBoolean("player-actions.allow-set-number", true);
                if (!allowNumber) { p.sendMessage(Text.colorize(prefix + "&cThis server has restricted racer numbers. Only an administrator can set your racer number.")); return true; }
                String s = args[2];
                if (!s.matches("\\d+")) { p.sendMessage(Text.colorize(prefix + "&cPlease enter digits only.")); return true; }
                int n = Integer.parseInt(s);
                if (n < 1 || n > 99) { p.sendMessage(Text.colorize(prefix + "&cNumber must be between 1 and 99.")); return true; }
                ot.get().setRacerNumber(p.getUniqueId(), n); teamManager.save();
                p.sendMessage(Text.colorize(prefix + "&aYour racer # set to " + n + "."));
                return true;
            }
            // /boatracing teams disband y /boatracing teams confirm
            if (args.length >= 2 && args[1].equalsIgnoreCase("disband")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                p.sendMessage(Text.colorize(prefix + "&cThis action is admin-only. Use /"+label+" admin team delete <team>."));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
                p.sendMessage(Text.colorize(prefix + "&cNothing to confirm."));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
                boolean any = false;
                if (pendingDisband.remove(p.getUniqueId())) { any = true; }
                if (pendingTransfer.remove(p.getUniqueId()) != null) { any = true; }
                if (pendingKick.remove(p.getUniqueId()) != null) { any = true; }
                if (!any) {
                    p.sendMessage(Text.colorize(prefix + "&cNothing to cancel."));
                    return true;
                }
                        p.sendMessage(Text.colorize(prefix + "&aCancelled."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
                return true;
            }
            // default fallback
            p.sendMessage(Text.colorize(prefix + "&cUnknown subcommand. Use: /boatracing version|reload|setup or /boatracing teams [create|rename|color|join|leave|kick|boat|number|transfer|disband|confirm|cancel]"));
            return true;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("boatracing")) {
            // Root suggestions (handle no-arg and first arg prefix)
            if (args.length == 0 || (args.length == 1 && (args[0] == null || args[0].isEmpty()))) {
                java.util.List<String> root = new java.util.ArrayList<>();
                if (sender.hasPermission("boatracing.teams")) root.add("teams");
                if (sender.hasPermission("boatracing.setup")) root.add("race");
                if (sender.hasPermission("boatracing.setup")) root.add("setup");
                if (sender.hasPermission("boatracing.admin")) root.add("admin");
                if (sender.hasPermission("boatracing.reload")) root.add("reload");
                if (sender.hasPermission("boatracing.version")) root.add("version");
                return root;
            }
            if (args.length == 1) {
                String pref = args[0].toLowerCase();
                java.util.List<String> root = new java.util.ArrayList<>();
                if (sender.hasPermission("boatracing.teams")) root.add("teams");
                if (sender.hasPermission("boatracing.setup")) root.add("race");
                if (sender.hasPermission("boatracing.setup")) root.add("setup");
                if (sender.hasPermission("boatracing.admin")) root.add("admin");
                if (sender.hasPermission("boatracing.reload")) root.add("reload");
                if (sender.hasPermission("boatracing.version")) root.add("version");
                return root.stream().filter(s -> s.startsWith(pref)).toList();
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
                if (!sender.hasPermission("boatracing.admin")) return java.util.Collections.emptyList();
                if (args.length == 2) return java.util.Arrays.asList("help","team","player");
                if (args.length == 3 && args[1].equalsIgnoreCase("team")) return java.util.Arrays.asList("create","delete","rename","color","add","remove");
                if (args.length == 3 && args[1].equalsIgnoreCase("player")) return java.util.Arrays.asList("setteam","setnumber","setboat");
                return java.util.Collections.emptyList();
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("race")) {
                if (!sender.hasPermission("boatracing.setup")) return java.util.Collections.emptyList();
                if (args.length == 2) return java.util.Arrays.asList("help","open","join","leave","force","start","stop","status");
                if (args.length == 3 && (args[1].equalsIgnoreCase("open") || args[1].equalsIgnoreCase("start"))) return java.util.Collections.singletonList("3");
                return java.util.Collections.emptyList();
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("setup")) {
                if (!sender.hasPermission("boatracing.setup")) return Collections.emptyList();
                if (args.length == 2) return Arrays.asList("help","addstart","clearstarts","setfinish","setpit","addcheckpoint","clearcheckpoints","show","selinfo","wand","wizard");
                if (args.length == 3 && args[1].equalsIgnoreCase("wizard")) return Arrays.asList("start","next","back","status","cancel");
                return Collections.emptyList();
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("teams")) {
                if (!sender.hasPermission("boatracing.teams")) return Collections.emptyList();
                if (args.length == 2) return Arrays.asList("create", "rename", "color", "join", "leave", "boat", "number", "confirm", "cancel");
                // Autocomplete team names for 'join'
                if (args.length >= 3 && args[1].equalsIgnoreCase("join")) {
                    String prefix = args[2].toLowerCase();
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (es.jaie55.boatracing.team.Team t : teamManager.getTeams()) {
                        String name = t.getName();
                        if (name != null && name.toLowerCase().startsWith(prefix)) names.add(name);
                    }
                    return names;
                }
                if (args.length >= 3 && args[1].equalsIgnoreCase("create")) return Collections.emptyList();
                if (args.length >= 3 && args[1].equalsIgnoreCase("color")) return java.util.Arrays.stream(org.bukkit.DyeColor.values()).map(Enum::name).map(String::toLowerCase).toList();
                if (args.length >= 3 && args[1].equalsIgnoreCase("boat")) return Arrays.asList(
                    // Normal boats first
                    "oak_boat","spruce_boat","birch_boat","jungle_boat","acacia_boat","dark_oak_boat","mangrove_boat","cherry_boat","pale_oak_boat",
                    // Then chest-boat variants
                    "oak_chest_boat","spruce_chest_boat","birch_chest_boat","jungle_chest_boat","acacia_chest_boat","dark_oak_chest_boat","mangrove_chest_boat","cherry_chest_boat","pale_oak_chest_boat"
                );
            }
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private void sendUpdateStatus(Player p) {
        if (updateChecker == null) return;
        String current = getPluginMeta().getVersion();
        if (!updateChecker.isChecked()) return;
        if (updateChecker.hasError()) {
            p.sendMessage(Text.colorize(prefix + "&7Update check failed. See console for details."));
            p.sendMessage(Text.colorize(prefix + "&7Releases: &f" + updateChecker.getLatestUrl()));
            return;
        }
        if (updateChecker.isOutdated()) {
            int behind = updateChecker.getBehindCount();
            String latest = updateChecker.getLatestVersion() != null ? updateChecker.getLatestVersion() : "latest";
            p.sendMessage(Text.colorize(prefix + "&eAn update for " + getName() + " is available. You are " + behind + " version(s) behind."));
            p.sendMessage(Text.colorize(prefix + "&eYou are running &6" + current + "&e, the latest version is &6" + latest + "&e."));
            p.sendMessage(Text.colorize(prefix + "&eUpdate at &f" + updateChecker.getLatestUrl()));
        } else {
            String latest = updateChecker.getLatestVersion() != null ? updateChecker.getLatestVersion() : current;
            p.sendMessage(Text.colorize(prefix + "&aYou're running the latest version (&f" + current + "&a)."));
            p.sendMessage(Text.colorize(prefix + "&7Latest: &f" + latest + " &7| Releases: &f" + updateChecker.getLatestUrl()));
        }
    }

    private static String fmtBox(org.bukkit.util.BoundingBox b) {
        return String.format("min(%d,%d,%d) max(%d,%d,%d)",
                (int) Math.floor(b.getMinX()), (int) Math.floor(b.getMinY()), (int) Math.floor(b.getMinZ()),
                (int) Math.floor(b.getMaxX()), (int) Math.floor(b.getMaxY()), (int) Math.floor(b.getMaxZ()));
    }
}
