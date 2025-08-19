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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class BoatRacingPlugin extends JavaPlugin {
    private static BoatRacingPlugin instance;
    private TeamManager teamManager;
    private TeamGUI teamGUI;
    private es.jaie55.boatracing.ui.AdminGUI adminGUI;
    private es.jaie55.boatracing.ui.AdminRaceGUI adminRaceGUI;
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
    public es.jaie55.boatracing.ui.AdminRaceGUI getAdminRaceGUI() { return adminRaceGUI; }
    public es.jaie55.boatracing.ui.TeamGUI getTeamGUI() { return teamGUI; }
    public RaceManager getRaceManager() { return raceManager; }
    public TrackConfig getTrackConfig() { return trackConfig; }
    public TrackLibrary getTrackLibrary() { return trackLibrary; }
    public es.jaie55.boatracing.ui.AdminTracksGUI getTracksGUI() { return tracksGUI; }
    

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        // Ensure new default keys are merged into existing config.yml on updates
        try {
            mergeConfigDefaults();
        } catch (Throwable t) {
            getLogger().warning("Failed to merge default config values: " + t.getMessage());
        }
        this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
        this.teamManager = new TeamManager(this);
    this.teamGUI = new TeamGUI(this);
    this.adminGUI = new es.jaie55.boatracing.ui.AdminGUI(this);
    this.adminRaceGUI = new es.jaie55.boatracing.ui.AdminRaceGUI(this);
    this.trackConfig = new TrackConfig(getDataFolder());
    this.trackLibrary = new TrackLibrary(getDataFolder(), trackConfig);
    this.raceManager = new RaceManager(this, trackConfig);
    this.setupWizard = new SetupWizard(this);
    this.tracksGUI = new es.jaie55.boatracing.ui.AdminTracksGUI(this, trackLibrary);
    Bukkit.getPluginManager().registerEvents(teamGUI, this);
    Bukkit.getPluginManager().registerEvents(adminGUI, this);
    Bukkit.getPluginManager().registerEvents(tracksGUI, this);
    Bukkit.getPluginManager().registerEvents(adminRaceGUI, this);
    
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

    // ViaVersion integration and internal scoreboard number hiding removed by request

    // Updates
    if (getConfig().getBoolean("updates.enabled", true)) {
            String currentVersion = getDescription().getVersion();
            updateChecker = new UpdateChecker(this, "boatracing", currentVersion);
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
            // Periodic silent update checks every 5 minutes (no console spam)
            long period = 20L * 60L * 5L; // 5 minutes
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    if (getConfig().getBoolean("updates.enabled", true)) {
                        updateChecker.checkAsync();
                    }
                } catch (Throwable ignored) {}
            }, period, period);
            // Console reminder every hour: run a fresh check and then warn once when outdated
            long hourly = 20L * 60L * 60L; // 1 hour
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!getConfig().getBoolean("updates.enabled", true)) return;
                if (!getConfig().getBoolean("updates.console-warn", true)) return;
                try {
                    updateChecker.checkAsync();
                } catch (Throwable ignored) {}
                // give the async call a moment to complete, then log if outdated
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (updateChecker.isChecked() && updateChecker.isOutdated()) {
                        int behind = updateChecker.getBehindCount();
                        String current = getDescription().getVersion();
                        String latest = updateChecker.getLatestVersion() != null ? updateChecker.getLatestVersion() : "latest";
                        Bukkit.getLogger().warning("[" + getName() + "] An update is available. You are " + behind + " version(s) out of date.");
                        Bukkit.getLogger().warning("[" + getName() + "] You are running " + current + ", the latest version is " + latest + ".");
                        Bukkit.getLogger().warning("[" + getName() + "] Update at " + updateChecker.getLatestUrl());
                    }
                }, 20L * 8L); // ~8s grace period after triggering the check
            }, hourly, hourly);
        }

    if (getCommand("boatracing") != null) {
            getCommand("boatracing").setExecutor(this);
            getCommand("boatracing").setTabCompleter(this);
        }
    getLogger().info("BoatRacing enabled");
    }

    // Merge default config.yml values into the existing config without overwriting user changes
    private void mergeConfigDefaults() {
        InputStream is = getResource("config.yml");
        if (is == null) return;
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
        FileConfiguration cfg = getConfig();
        cfg.addDefaults(def);
        cfg.options().copyDefaults(true);
        saveConfig();
    }

    // Scoreboard number hiding removed by request

    // Resolve an OfflinePlayer without remote lookups: prefer online, then cache, or UUID literal
    private org.bukkit.OfflinePlayer resolveOffline(String token) {
        if (token == null || token.isEmpty()) return null;
        // 1) Exact online match
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(token);
        if (online != null) return online;
        // 2) Try UUID literal
        try {
            java.util.UUID uid = java.util.UUID.fromString(token);
            return Bukkit.getOfflinePlayer(uid);
        } catch (IllegalArgumentException ignored) {}
        // 3) Try offline cache entries by name (case-insensitive)
        for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(token)) return op;
        }
        // Not found locally
        return null;
    }

    @Override
    public void onDisable() {
        if (teamManager != null) teamManager.save();
    }

    // ViaVersion integration removed by request

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
                String current = getDescription().getVersion();
                java.util.List<String> authors = getDescription().getAuthors();
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
                    updateChecker = new UpdateChecker(this, "boatracing", current);
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
                // After reload, also merge any new defaults into config.yml
                try { mergeConfigDefaults(); } catch (Throwable ignored) {}
                this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
                // Recreate team manager to re-read data and settings
                this.teamManager = new TeamManager(this);
                // ViaVersion integration removed; nothing to re-apply
                p.sendMessage(Text.colorize(prefix + "&aPlugin reloaded."));
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return true;
            }
            // /boatracing race
            if (args[0].equalsIgnoreCase("race")) {
                if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
                    p.sendMessage(Text.colorize(prefix + "&eRace commands:"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race join <track> &7(Join the registration on that track; team required)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race leave <track> &7(Leave the registration on that track)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " race status <track> &7(Show race status for the track)"));
                    if (p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup")) {
                        p.sendMessage(Text.colorize("&8Admin:&7 /" + label + " race open|start|force|stop <track>"));
                    }
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "open" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " race open <track>")); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { p.sendMessage(Text.colorize(prefix + "&cTrack not found: &f" + tname)); return true; }
                        if (!trackLibrary.select(tname)) { p.sendMessage(Text.colorize(prefix + "&cFailed to load track: &f" + tname)); return true; }
                        if (!trackConfig.isReady()) {
                            p.sendMessage(Text.colorize(prefix + "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements())));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        int laps = raceManager.getTotalLaps();
                        boolean ok = raceManager.openRegistration(laps, null);
                        if (!ok) p.sendMessage(Text.colorize(prefix + "&cCannot open registration right now."));
                        return true;
                    }
                    case "join" -> {
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " race join <track>")); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { p.sendMessage(Text.colorize(prefix + "&cTrack not found: &f" + tname)); return true; }
                        if (!trackLibrary.select(tname)) { p.sendMessage(Text.colorize(prefix + "&cFailed to load track: &f" + tname)); return true; }
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
                    case "leave" -> {
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " race leave <track>")); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { p.sendMessage(Text.colorize(prefix + "&cTrack not found: &f" + tname)); return true; }
                        if (!trackLibrary.select(tname)) { p.sendMessage(Text.colorize(prefix + "&cFailed to load track: &f" + tname)); return true; }
                        boolean removed = raceManager.leave(p);
                        if (!removed) {
                            if (!raceManager.isRegistering()) {
                                p.sendMessage(Text.colorize(prefix + "&cRegistration is not open."));
                            } else {
                                p.sendMessage(Text.colorize(prefix + "&7You are not registered."));
                            }
                        }
                        return true;
                    }
                    case "force" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " race force <track>")); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { p.sendMessage(Text.colorize(prefix + "&cTrack not found: &f" + tname)); return true; }
                        if (!trackLibrary.select(tname)) { p.sendMessage(Text.colorize(prefix + "&cFailed to load track: &f" + tname)); return true; }
                        if (raceManager.getRegistered().isEmpty()) {
                            p.sendMessage(Text.colorize(prefix + "&cNo registered participants. &7Open registration first: &f/" + label + " race open"));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        raceManager.forceStart();
                        return true;
                    }
                    case "start" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " race start <track>")); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { p.sendMessage(Text.colorize(prefix + "&cTrack not found: &f" + tname)); return true; }
                        if (!trackLibrary.select(tname)) { p.sendMessage(Text.colorize(prefix + "&cFailed to load track: &f" + tname)); return true; }
                        if (!trackConfig.isReady()) {
                            p.sendMessage(Text.colorize(prefix + "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements())));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (raceManager.isRunning()) { p.sendMessage(Text.colorize(prefix + "&cRace already running.")); return true; }
                        // Build participants: strictly registered participants only
                        java.util.List<org.bukkit.entity.Player> participants = new java.util.ArrayList<>();
                        java.util.Set<java.util.UUID> regs = new java.util.LinkedHashSet<>(raceManager.getRegistered());
                        for (java.util.UUID id : regs) {
                            org.bukkit.entity.Player rp = Bukkit.getPlayer(id);
                            if (rp != null && rp.isOnline()) participants.add(rp);
                        }
                        if (participants.isEmpty()) {
                            p.sendMessage(Text.colorize(prefix + "&cNo registered participants. &7Use &f/" + label + " race open &7to start registration."));
                            return true;
                        }
                        // Place with boats and start
                        java.util.List<org.bukkit.entity.Player> placed = raceManager.placeAtStartsWithBoats(participants);
                        if (placed.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cNo free start slots on this track.")); return true; }
                        if (placed.size() < participants.size()) { p.sendMessage(Text.colorize(prefix + "&7Some registered players could not be placed due to lack of start slots.")); }
                        // Use start lights countdown if configured
                        raceManager.startRaceWithCountdown(placed);
                        return true;
                    }
                    case "stop" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            p.sendMessage(Text.colorize(prefix + "&cYou don't have permission to do that."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " race stop <track>")); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { p.sendMessage(Text.colorize(prefix + "&cTrack not found: &f" + tname)); return true; }
                        if (!trackLibrary.select(tname)) { p.sendMessage(Text.colorize(prefix + "&cFailed to load track: &f" + tname)); return true; }
                        boolean any = false;
                        if (raceManager.isRegistering()) {
                            any |= raceManager.cancelRegistration(true);
                        }
                        if (raceManager.isRunning()) {
                            any |= raceManager.cancelRace();
                        }
                        if (!any) {
                            p.sendMessage(Text.colorize(prefix + "&7Nothing to stop."));
                        }
                        return true;
                    }
                    case "status" -> {
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " race status <track>")); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { p.sendMessage(Text.colorize(prefix + "&cTrack not found: &f" + tname)); return true; }
                        if (!trackLibrary.select(tname)) { p.sendMessage(Text.colorize(prefix + "&cFailed to load track: &f" + tname)); return true; }
                        String cur = (getTrackLibrary() != null && getTrackLibrary().getCurrent() != null) ? getTrackLibrary().getCurrent() : "(unsaved)";
                        boolean running = raceManager.isRunning();
                        boolean registering = raceManager.isRegistering();
                        int regs = raceManager.getRegistered().size();
                        int laps = raceManager.getTotalLaps();
                        int participants = running ? raceManager.getParticipants().size() : 0;
                        int starts = trackConfig.getStarts().size();
                        int lights = trackConfig.getLights().size();
                        int cps = trackConfig.getCheckpoints().size();
                        boolean hasFinish = trackConfig.getFinish() != null;
                        boolean hasPit = trackConfig.getPitlane() != null;
                        boolean ready = trackConfig.isReady();
                        java.util.List<String> missing = ready ? java.util.Collections.emptyList() : trackConfig.missingRequirements();

                        p.sendMessage(Text.colorize(prefix + "&eRace status:"));
                        p.sendMessage(Text.colorize("&7Track: &f" + cur));
                        p.sendMessage(Text.colorize(running ? "&aRace running &7(Participants: &f" + participants + "&7)" : "&7No race running."));
                        p.sendMessage(Text.colorize(registering ? "&eRegistration open &7(Registered: &f" + regs + "&7)" : "&7Registration closed."));
                        p.sendMessage(Text.colorize("&7Laps: &f" + laps));
                        p.sendMessage(Text.colorize("&7Starts: &f" + starts + " &8• &7Start lights: &f" + lights + "/5 &8• &7Finish: &f" + (hasFinish?"yes":"no") + " &8• &7Pit area: &f" + (hasPit?"yes":"no")));
                        p.sendMessage(Text.colorize("&7Checkpoints: &f" + cps));
                        p.sendMessage(Text.colorize("&7Mandatory pit stops: &f" + raceManager.getMandatoryPitstops()));
                        if (ready) {
                            p.sendMessage(Text.colorize("&aTrack is ready."));
                        } else {
                            p.sendMessage(Text.colorize("&cTrack is not ready: &7" + String.join(", ", missing)));
                        }
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
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup addstart &7(Add your current position as a start slot; repeat to add multiple)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup clearstarts &7(Remove all start slots)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup setfinish &7(Use your BoatRacing selection for the finish line region)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup setpit [team] &7(Set default pit from your selection, or team-specific pit if a team is provided)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup addcheckpoint &7(Add a checkpoint from your selection; you can add multiple. Order matters)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup addlight &7(Add the redstone lamp you're looking at as a start light; max 5, left-to-right order)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup clearlights &7(Remove all configured start lights)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup setlaps <n> &7(Set the number of laps for the race)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup setpitstops <n> &7(Set mandatory pit stops required to finish; 0 to disable)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup setpos <player> <slot|auto> &7(Bind a player to a specific start slot, 1-based; auto removes binding)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup clearpos <player> &7(Remove a player's custom start slot)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup clearcheckpoints &7(Remove all checkpoints)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup show &7(Summary of current track config)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup selinfo &7(Selection debug: current BoatRacing selection)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup wand &7(Give the BoatRacing selection tool)"));
                    p.sendMessage(Text.colorize("&7 - &f/" + label + " setup wizard &7(Launch guided setup assistant)"));
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
                        // Guided setup assistant with simple sub-actions
                        if (args.length >= 3) {
                            String action = args[2].toLowerCase();
                            switch (action) {
                                case "finish" -> setupWizard.finish(p);
                                case "back" -> setupWizard.back(p);
                                case "status" -> setupWizard.status(p);
                                case "cancel" -> setupWizard.cancel(p);
                                case "skip" -> setupWizard.skip(p); // only works on optional steps
                                case "next" -> setupWizard.next(p);
                                default -> setupWizard.start(p);
                            }
                        } else if (setupWizard.isActive(p)) {
                            setupWizard.status(p);
                        } else {
                            setupWizard.start(p);
                        }
                        return true;
                    }
                    case "addstart" -> {
                        org.bukkit.Location loc = p.getLocation();
                        trackConfig.addStart(loc);
                        p.sendMessage(Text.colorize(prefix + "&aAdded start slot at &f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + " &7(" + loc.getWorld().getName() + ")"));
                        p.sendMessage(Text.colorize("&7Tip: You can add multiple start slots. Run the command again to add more."));
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
                        if (args.length >= 3) {
                            // Join the rest of tokens to support names with spaces; allow quoted names
                            String raw = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
                            String teamName = raw;
                            if ((teamName.startsWith("\"") && teamName.endsWith("\"")) || (teamName.startsWith("'") && teamName.endsWith("'"))) {
                                teamName = teamName.substring(1, teamName.length()-1);
                            }
                            var ot = teamManager.findByName(teamName);
                            if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cTeam not found.")); return true; }
                            trackConfig.setTeamPit(ot.get().getId(), r);
                            p.sendMessage(Text.colorize(prefix + "&aSet pit area for team &f" + ot.get().getName() + " &7(" + fmtBox(sel.box) + ")"));
                        } else {
                            trackConfig.setPitlane(r);
                            p.sendMessage(Text.colorize(prefix + "&aDefault pit area set (" + fmtBox(sel.box) + ")"));
                        }
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
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
                        p.sendMessage(Text.colorize("&7Tip: You can add multiple checkpoints. Order matters."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "addlight" -> {
                        org.bukkit.block.Block target = p.getTargetBlockExact(6);
                        if (target == null) {
                            p.sendMessage(Text.colorize(prefix + "&cLook at a Redstone Lamp within 6 blocks."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        boolean ok = trackConfig.addLight(target);
                        if (!ok) {
                            p.sendMessage(Text.colorize(prefix + "&cCould not add light. Use a Redstone Lamp, avoid duplicates, and max 5."));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        p.sendMessage(Text.colorize(prefix + "&aAdded start light &7(" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")"));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "clearlights" -> {
                        trackConfig.clearLights();
                        p.sendMessage(Text.colorize(prefix + "&aCleared all start lights."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "setlaps" -> {
                        if (args.length < 3 || !args[2].matches("\\d+")) {
                            p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " setup setlaps <number>"));
                            return true;
                        }
                        int laps = Math.max(1, Integer.parseInt(args[2]));
                        raceManager.setTotalLaps(laps);
                        p.sendMessage(Text.colorize(prefix + "&aLaps set to &f" + laps));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
                    }
                    case "setpitstops" -> {
                        if (args.length < 3 || !args[2].matches("\\d+")) {
                            p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " setup setpitstops <number>"));
                            return true;
                        }
                        int req = Math.max(0, Integer.parseInt(args[2]));
                        raceManager.setMandatoryPitstops(req);
                        // Persist to config as the global default
                        getConfig().set("racing.mandatory-pitstops", req);
                        saveConfig();
                        p.sendMessage(Text.colorize(prefix + "&aMandatory pit stops set to &f" + req));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
                    }
                    case "setpos" -> {
                        if (args.length < 4) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " setup setpos <player> <slot|auto>")); return true; }
                        org.bukkit.OfflinePlayer off = resolveOffline(args[2]);
                        if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                        String slotArg = args[3];
                        if (slotArg.equalsIgnoreCase("auto")) {
                            trackConfig.clearCustomStartSlot(off.getUniqueId());
                            p.sendMessage(Text.colorize(prefix + "&aRemoved custom start position for &f" + (off.getName()!=null?off.getName():off.getUniqueId().toString())));
                        } else if (slotArg.matches("\\d+")) {
                            int oneBased = Integer.parseInt(slotArg);
                            if (oneBased < 1 || oneBased > trackConfig.getStarts().size()) { p.sendMessage(Text.colorize(prefix + "&cInvalid slot. Range: 1-" + trackConfig.getStarts().size())); return true; }
                            trackConfig.setCustomStartSlot(off.getUniqueId(), oneBased - 1);
                            p.sendMessage(Text.colorize(prefix + "&aSet custom start position for &f" + (off.getName()!=null?off.getName():off.getUniqueId().toString()) + " &7to slot &f#" + oneBased));
                        } else {
                            p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " setup setpos <player> <slot|auto>"));
                        }
                        return true;
                    }
                    case "clearpos" -> {
                        if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " setup clearpos <player>")); return true; }
                        org.bukkit.OfflinePlayer off = resolveOffline(args[2]);
                        if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                        trackConfig.clearCustomStartSlot(off.getUniqueId());
                        p.sendMessage(Text.colorize(prefix + "&aRemoved custom start position for &f" + (off.getName()!=null?off.getName():off.getUniqueId().toString())));
                        return true;
                    }
                    case "clearcheckpoints" -> {
                        trackConfig.clearCheckpoints();
                        p.sendMessage(Text.colorize(prefix + "&aCleared all checkpoints."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "show" -> {
                        int starts = trackConfig.getStarts().size();
                        int lights = trackConfig.getLights().size();
                        int cps = trackConfig.getCheckpoints().size();
                        boolean hasFinish = trackConfig.getFinish() != null;
                        boolean hasPit = trackConfig.getPitlane() != null;
                        int teamPitCount = trackConfig.getTeamPits().size();
                        int customStarts = trackConfig.getCustomStartSlots().size();
                        p.sendMessage(Text.colorize(prefix + "&eTrack config:"));
                        String tname = (getTrackLibrary() != null && getTrackLibrary().getCurrent() != null) ? getTrackLibrary().getCurrent() : "(unsaved)";
                        p.sendMessage(Text.colorize("&7 - &fTrack: &e" + tname));
                        p.sendMessage(Text.colorize("&7 - &fStarts: &e" + starts));
                        p.sendMessage(Text.colorize("&7 - &fStart lights: &e" + lights + "/5"));
                        p.sendMessage(Text.colorize("&7 - &fFinish: &e" + (hasFinish ? "yes" : "no")));
                        p.sendMessage(Text.colorize("&7 - &fPit area (default): &e" + (hasPit ? "yes" : "no")));
                        p.sendMessage(Text.colorize("&7 - &fTeam-specific pits: &e" + (teamPitCount > 0 ? (teamPitCount + " configured") : "none")));
                        p.sendMessage(Text.colorize("&7 - &fCustom start positions: &e" + (customStarts > 0 ? (customStarts + " player(s)") : "none")));
                        p.sendMessage(Text.colorize("&7 - &fCheckpoints: &e" + cps));
                        p.sendMessage(Text.colorize("&7 - &fMandatory pit stops: &e" + raceManager.getMandatoryPitstops()));
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
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found locally. Use UUID or ask the player to join once.")); return true; }
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
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found locally. Use UUID or ask the player to join once.")); return true; }
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
                            org.bukkit.OfflinePlayer off = resolveOffline(args[3]);
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found locally. Use UUID or ask the player to join once.")); return true; }
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
                            org.bukkit.OfflinePlayer off = resolveOffline(args[3]);
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
                            org.bukkit.OfflinePlayer off = resolveOffline(args[3]);
                            if (off == null || off.getUniqueId() == null) { p.sendMessage(Text.colorize(prefix + "&cPlayer not found.")); return true; }
                            String type = args[4].toUpperCase();
                            // Validate against allowed boats: boats, chest boats, and raft names
                            java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
                            for (org.bukkit.Material m : org.bukkit.Material.values()) {
                                String n = m.name();
                                if (n.endsWith("_BOAT") || n.endsWith("_CHEST_BOAT")) allowed.add(n);
                            }
                            // Also accept RAFT/CHEST_RAFT tokens even if not present as Materials
                            allowed.add("RAFT");
                            allowed.add("CHEST_RAFT");
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
            // /boatracing teams leave (with confirm if team would be empty)
            if (args.length >= 2 && args[1].equalsIgnoreCase("leave")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                es.jaie55.boatracing.team.Team t = ot.get();
                if (t.getMembers().size() <= 1) {
                    pendingDisband.add(p.getUniqueId());
                    p.sendMessage(Text.colorize(prefix + "&eYou are the last member. Leaving will delete the team."));
                    p.sendMessage(Text.colorize(prefix + "&7Type &b/"+label+" teams confirm &7to proceed or &b/"+label+" teams cancel &7to abort."));
                    return true;
                }
                t.removeMember(p.getUniqueId());
                teamManager.save();
                p.sendMessage(Text.colorize(prefix + "&aYou left the team."));
                for (java.util.UUID m : t.getMembers()) {
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline() && op.getPlayer() != null) {
                        op.getPlayer().sendMessage(Text.colorize(prefix + "&e" + p.getName() + " left the team."));
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
                // Accept RAFT tokens directly, otherwise require a valid BOAT material
                if (type.equals("RAFT") || type.equals("CHEST_RAFT")) {
                    ot.get().setBoatType(p.getUniqueId(), type);
                    teamManager.save();
                    p.sendMessage(Text.colorize(prefix + "&aYour boat set to &e" + type.toLowerCase() + "&a."));
                } else {
                    try {
                        org.bukkit.Material m = org.bukkit.Material.valueOf(type);
                        if (!m.name().endsWith("BOAT")) throw new IllegalArgumentException();
                        ot.get().setBoatType(p.getUniqueId(), m.name());
                        teamManager.save();
                        p.sendMessage(Text.colorize(prefix + "&aYour boat set to &e" + type.toLowerCase() + "&a."));
                    } catch (IllegalArgumentException ex) {
                        p.sendMessage(Text.colorize(prefix + "&cInvalid boat type."));
                    }
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
                // Confirm pending dangerous actions (last-member leave -> disband)
                if (pendingDisband.remove(p.getUniqueId())) {
                    java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                    if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                    es.jaie55.boatracing.team.Team t = ot.get();
                    // Proceed: remove member (self) and delete team since no members left
                    t.removeMember(p.getUniqueId());
                    teamManager.deleteTeam(t);
                    p.sendMessage(Text.colorize(prefix + "&aYou left and the team was deleted (no members left)."));
                    return true;
                }
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
                // Expose 'race' root to all users for join/leave/status discoverability
                root.add("race");
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
                root.add("race");
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
                if (args.length == 5 && args[2].equalsIgnoreCase("setboat")) {
                    return java.util.Arrays.asList(
                        "oak_boat","spruce_boat","birch_boat","jungle_boat","acacia_boat","dark_oak_boat","mangrove_boat","cherry_boat","pale_oak_boat",
                        "oak_chest_boat","spruce_chest_boat","birch_chest_boat","jungle_chest_boat","acacia_chest_boat","dark_oak_chest_boat","mangrove_chest_boat","cherry_chest_boat","pale_oak_chest_boat",
                        "bamboo_raft","bamboo_chest_raft"
                    );
                }
                return java.util.Collections.emptyList();
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("race")) {
                // Admin subcommands guarded; expose join/leave/status to everyone
                if (args.length == 2) {
                    java.util.List<String> subs = new java.util.ArrayList<>();
                    subs.add("help");
                    subs.add("join"); subs.add("leave"); subs.add("status");
                    if (sender.hasPermission("boatracing.race.admin") || sender.hasPermission("boatracing.setup")) {
                        subs.add("open"); subs.add("start"); subs.add("force"); subs.add("stop");
                    }
                    String pref = args[1] == null ? "" : args[1].toLowerCase();
                    return subs.stream().filter(s -> s.startsWith(pref)).toList();
                }
                // For subcommands that take <track>, suggest track names from library
                if (args.length == 3 && java.util.Arrays.asList("open","join","leave","force","start","stop","status").contains(args[1].toLowerCase())) {
                    String prefix = args[2] == null ? "" : args[2].toLowerCase();
                    java.util.List<String> names = new java.util.ArrayList<>();
                    if (trackLibrary != null) {
                        for (String n : trackLibrary.list()) if (n.toLowerCase().startsWith(prefix)) names.add(n);
                    }
                    return names;
                }
                return java.util.Collections.emptyList();
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("setup")) {
                if (!sender.hasPermission("boatracing.setup")) return Collections.emptyList();
                if (args.length == 2) return Arrays.asList("help","addstart","clearstarts","setfinish","setpit","addcheckpoint","clearcheckpoints","addlight","clearlights","setpos","clearpos","show","selinfo","wand","wizard");
                if (args.length >= 3 && args[1].equalsIgnoreCase("setpit")) {
                    // Build current partial input (join tokens from index 2)
                    String partial = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).toLowerCase();
                    boolean startedQuote = args[2] != null && (args[2].startsWith("\"") || args[2].startsWith("'"));
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (es.jaie55.boatracing.team.Team t : teamManager.getTeams()) {
                        String name = t.getName();
                        if (name == null) continue;
                        String quoted = '"' + name + '"';
                        String cand = startedQuote ? quoted : name;
                        if (cand.toLowerCase().startsWith(partial)) names.add(cand);
                    }
                    return names;
                }
                if (args.length >= 3 && (args[1].equalsIgnoreCase("setpos") || args[1].equalsIgnoreCase("clearpos"))) {
                    // Suggest player names (online + known offline)
                    String prefName = args[2] == null ? "" : args[2].toLowerCase();
                    java.util.Set<String> names = new java.util.LinkedHashSet<>();
                    for (org.bukkit.entity.Player op : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (op.getName() != null && op.getName().toLowerCase().startsWith(prefName)) names.add(op.getName());
                    }
                    for (org.bukkit.OfflinePlayer op : org.bukkit.Bukkit.getOfflinePlayers()) {
                        if (op.getName() != null && op.getName().toLowerCase().startsWith(prefName)) names.add(op.getName());
                    }
                    if (args.length == 3) return new java.util.ArrayList<>(names);
                    if (args.length == 4 && args[1].equalsIgnoreCase("setpos")) {
                        // Suggest slot numbers and keyword 'auto'
                        java.util.List<String> opts = new java.util.ArrayList<>();
                        opts.add("auto");
                        int max = trackConfig.getStarts().size();
                        for (int i = 1; i <= Math.min(max, 20); i++) opts.add(String.valueOf(i));
                        String pref = args[3] == null ? "" : args[3].toLowerCase();
                        return opts.stream().filter(s -> s.startsWith(pref)).toList();
                    }
                    return java.util.Collections.emptyList();
                }
                // Do not expose wizard subcommands in tab-completion; single entrypoint UX
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
                    "oak_chest_boat","spruce_chest_boat","birch_chest_boat","jungle_chest_boat","acacia_chest_boat","dark_oak_chest_boat","mangrove_chest_boat","cherry_chest_boat","pale_oak_chest_boat",
                    // Rafts (bamboo)
                    "bamboo_raft","bamboo_chest_raft"
                );
            }
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private void sendUpdateStatus(Player p) {
        if (updateChecker == null) return;
                        String current = getDescription().getVersion();
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
