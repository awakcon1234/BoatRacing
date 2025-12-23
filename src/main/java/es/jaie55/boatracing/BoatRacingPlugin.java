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
    // Last latest-version announced in console due to 5-minute silent checks (to avoid duplicate prints)
    private volatile String lastConsoleAnnouncedVersion = null;
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
                        // Record which latest was announced
                        lastConsoleAnnouncedVersion = updateChecker.getLatestVersion();
                    }
                }
            }, 20L * 5); // ~5s after enable
            // In-game notifications for admins
            if (getConfig().getBoolean("updates.notify-admins", true)) {
                Bukkit.getPluginManager().registerEvents(new UpdateNotifier(this, updateChecker), this);
            }
            // Periodic silent update checks every 5 minutes. If a NEW update is first detected here,
            // print a console WARN immediately (single time per version); hourly reminders handle repetition.
            long period = 20L * 60L * 5L; // 5 minutes
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    if (!getConfig().getBoolean("updates.enabled", true)) return;
                    updateChecker.checkAsync();
                    // Evaluate result shortly after on the main thread to avoid race conditions
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (!getConfig().getBoolean("updates.enabled", true)) return;
                        if (!getConfig().getBoolean("updates.console-warn", true)) return;
                        if (updateChecker.isChecked() && updateChecker.isOutdated()) {
                            String latest = updateChecker.getLatestVersion();
                            if (latest != null && (lastConsoleAnnouncedVersion == null || !latest.equals(lastConsoleAnnouncedVersion))) {
                                int behind = updateChecker.getBehindCount();
                                String current = getDescription().getVersion();
                                Bukkit.getLogger().warning("[" + getName() + "] An update is available. You are " + behind + " version(s) out of date.");
                                Bukkit.getLogger().warning("[" + getName() + "] You are running " + current + ", the latest version is " + latest + ".");
                                Bukkit.getLogger().warning("[" + getName() + "] Update at " + updateChecker.getLatestUrl());
                                lastConsoleAnnouncedVersion = latest; // avoid duplicate console prints for the same version here
                            }
                        }
                    }, 20L * 8L);
                } catch (Throwable ignored) {}
            }, period, period);
            // Console reminder every hour:
            // - Warn immediately if we already know we're outdated
            // - Trigger a fresh async check and then warn once when the result arrives (with retries to cover latency)
            // Hourly console reminder aligned to the top of each local hour (00:00, 01:00, 02:00, ...)
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
            java.time.ZonedDateTime nextHour = now.withMinute(0).withSecond(0).withNano(0).plusHours(1);
            long delayTicks = Math.max(1L, java.time.Duration.between(now, nextHour).toMillis() / 50L);
            long hourly = 20L * 60L * 60L; // 1 hour
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!getConfig().getBoolean("updates.enabled", true)) return;
                if (!getConfig().getBoolean("updates.console-warn", true)) return;
                try { updateChecker.checkAsync(); } catch (Throwable ignored) {}
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!getConfig().getBoolean("updates.enabled", true)) return;
                    if (!getConfig().getBoolean("updates.console-warn", true)) return;
                    if (updateChecker.isChecked() && updateChecker.isOutdated()) {
                        int behind = updateChecker.getBehindCount();
                        String current = getDescription().getVersion();
                        String latest = updateChecker.getLatestVersion() != null ? updateChecker.getLatestVersion() : "latest";
                        Bukkit.getLogger().warning("[" + getName() + "] An update is available. You are " + behind + " version(s) out of date.");
                        Bukkit.getLogger().warning("[" + getName() + "] You are running " + current + ", the latest version is " + latest + ".");
                        Bukkit.getLogger().warning("[" + getName() + "] Update at " + updateChecker.getLatestUrl());
                    }
                }, 20L * 10L);
            }, delayTicks, hourly);
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.msg(sender, "&cChỉ dành cho người chơi.");
            return true;
        }
        Player p = (Player) sender;
        if (command.getName().equalsIgnoreCase("boatracing")) {
            if (args.length == 0) {
                Text.msg(p, "&cCách dùng: /" + label + " teams|setup|reload|version");
                return true;
            }
            // /boatracing version
            if (args[0].equalsIgnoreCase("version")) {
                if (!p.hasPermission("boatracing.version")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                String current = getDescription().getVersion();
                java.util.List<String> authors = getDescription().getAuthors();
                Text.msg(p, "&e" + getName() + "-" + current);
                if (!authors.isEmpty()) {
                    Text.msg(p, "&eAuthors: &f" + String.join(", ", authors));
                }
                

                boolean updatesEnabled = getConfig().getBoolean("updates.enabled", true);
                if (!updatesEnabled) {
                    Text.msg(p, "&7Kiểm tra cập nhật bị tắt trong cấu hình.");
                    return true;
                }

                // Ensure we have a checker and run one if needed
                if (updateChecker == null) {
                    updateChecker = new UpdateChecker(this, "boatracing", current);
                }
                if (!updateChecker.isChecked()) {
                    Text.msg(p, "&7Đang kiểm tra cập nhật...");
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
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
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
                Text.msg(p, "&aĐã tải lại plugin.");
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return true;
            }
            // /boatracing race
            if (args[0].equalsIgnoreCase("race")) {
                if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
                    Text.msg(p, "&eLệnh đua:");
                    Text.tell(p, "&7 - &f/" + label + " race join <track> &7(Tham gia đăng ký cho đường đua; cần có đội)");
                    Text.tell(p, "&7 - &f/" + label + " race leave <track> &7(Rời khỏi đăng ký cho đường đua)");
                    Text.tell(p, "&7 - &f/" + label + " race status <track> &7(Hiển thị trạng thái cuộc đua cho đường đua)");
                    if (p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup")) {
                        Text.tell(p, "&8Quản trị:&7 /" + label + " race open|start|force|stop <track>");
                    }
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "open" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race open <track>"); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { Text.msg(p, "&cTrack not found: &f" + tname); return true; }
                        if (!trackLibrary.select(tname)) { Text.msg(p, "&cFailed to load track: &f" + tname); return true; }
                        if (!trackConfig.isReady()) {
                            Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements()));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        int laps = raceManager.getTotalLaps();
                        boolean ok = raceManager.openRegistration(laps, null);
                        if (!ok) Text.msg(p, "&cKhông thể mở đăng ký lúc này.");
                        return true;
                    }
                    case "join" -> {
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race join <track>"); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { Text.msg(p, "&cTrack not found: &f" + tname); return true; }
                        if (!trackLibrary.select(tname)) { Text.msg(p, "&cFailed to load track: &f" + tname); return true; }
                        if (!trackConfig.isReady()) {
                            Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements()));
                            return true;
                        }
                        // Must be in a team
                        if (teamManager.getTeamByMember(p.getUniqueId()).isEmpty()) {
                            Text.msg(p, "&cBạn phải ở trong một đội để tham gia cuộc đua.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (!raceManager.join(p)) {
                            Text.msg(p, "&cĐăng ký chưa mở.");
                        }
                        return true;
                    }
                    case "leave" -> {
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race leave <track>"); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { Text.msg(p, "&cTrack not found: &f" + tname); return true; }
                        if (!trackLibrary.select(tname)) { Text.msg(p, "&cFailed to load track: &f" + tname); return true; }
                        boolean removed = raceManager.leave(p);
                        if (!removed) {
                            if (!raceManager.isRegistering()) {
                                Text.msg(p, "&cĐăng ký chưa mở.");
                            } else {
                                Text.msg(p, "&7Bạn chưa đăng ký.");
                            }
                        }
                        return true;
                    }
                    case "force" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race force <track>"); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { Text.msg(p, "&cTrack not found: &f" + tname); return true; }
                        if (!trackLibrary.select(tname)) { Text.msg(p, "&cFailed to load track: &f" + tname); return true; }
                        if (raceManager.getRegistered().isEmpty()) {
                            Text.msg(p, "&cKhông có người tham gia đã đăng ký. &7Mở đăng ký trước: &f/" + label + " race open");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        raceManager.forceStart();
                        return true;
                    }
                    case "start" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race start <track>"); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { Text.msg(p, "&cTrack not found: &f" + tname); return true; }
                        if (!trackLibrary.select(tname)) { Text.msg(p, "&cFailed to load track: &f" + tname); return true; }
                        if (!trackConfig.isReady()) {
                            Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", trackConfig.missingRequirements()));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (raceManager.isRunning()) { Text.msg(p, "&cCuộc đua đang diễn ra."); return true; }
                        // Build participants: strictly registered participants only
                        java.util.List<org.bukkit.entity.Player> participants = new java.util.ArrayList<>();
                        java.util.Set<java.util.UUID> regs = new java.util.LinkedHashSet<>(raceManager.getRegistered());
                        for (java.util.UUID id : regs) {
                            org.bukkit.entity.Player rp = Bukkit.getPlayer(id);
                            if (rp != null && rp.isOnline()) participants.add(rp);
                        }
                        if (participants.isEmpty()) {
                            Text.msg(p, "&cKhông có người tham gia đã đăng ký. Sử dụng &f/" + label + " race open &7để mở đăng ký.");
                            return true;
                        }
                        // Place with boats and start
                        java.util.List<org.bukkit.entity.Player> placed = raceManager.placeAtStartsWithBoats(participants);
                        if (placed.isEmpty()) { Text.msg(p, "&cKhông còn vị trí bắt đầu trống trên đường đua này."); return true; }
                        if (placed.size() < participants.size()) { Text.msg(p, "&7Một số người chơi đăng ký không thể được đặt do thiếu vị trí bắt đầu."); }
                        // Use start lights countdown if configured
                        raceManager.startRaceWithCountdown(placed);
                        return true;
                    }
                    case "stop" -> {
                        if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
                            Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race stop <track>"); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { Text.msg(p, "&cTrack not found: &f" + tname); return true; }
                        if (!trackLibrary.select(tname)) { Text.msg(p, "&cFailed to load track: &f" + tname); return true; }
                        boolean any = false;
                        if (raceManager.isRegistering()) {
                            any |= raceManager.cancelRegistration(true);
                        }
                        if (raceManager.isRunning()) {
                            any |= raceManager.cancelRace();
                        }
                        if (!any) {
                            Text.msg(p, "&7Không có gì để dừng.");
                        }
                        return true;
                    }
                    case "status" -> {
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race status <track>"); return true; }
                        String tname = args[2];
                        if (!trackLibrary.exists(tname)) { Text.msg(p, "&cTrack not found: &f" + tname); return true; }
                        if (!trackLibrary.select(tname)) { Text.msg(p, "&cFailed to load track: &f" + tname); return true; }
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

                        Text.msg(p, "&eTrạng thái cuộc đua:");
                        Text.tell(p, "&7Đường đua: &f" + cur);
                        Text.tell(p, running ? "&aĐang chạy &7(Tham gia: &f" + participants + "&7)" : "&7Không có cuộc đua đang chạy.");
                        Text.tell(p, registering ? "&eĐăng ký mở &7(Đã đăng ký: &f" + regs + "&7)" : "&7Đăng ký đóng.");
                        Text.tell(p, "&7Số vòng: &f" + laps);
                        Text.tell(p, "&7Vị trí bắt đầu: &f" + starts + " &8• &7Đèn xuất phát: &f" + lights + "/5 &8• &7Vạch kết thúc: &f" + (hasFinish?"có":"không") + " &8• &7Khu pit: &f" + (hasPit?"có":"không"));
                        Text.tell(p, "&7Điểm checkpoint: &f" + cps);
                        Text.tell(p, "&7Số dừng pit bắt buộc: &f" + raceManager.getMandatoryPitstops());
                        if (ready) {
                            Text.tell(p, "&aĐường đua sẵn sàng.");
                        } else {
                            Text.tell(p, "&cĐường đua chưa sẵn sàng: &7" + String.join(", ", missing));
                        }
                        return true;
                    }
                    default -> { Text.msg(p, "&cKhông rõ lệnh con đua. Sử dụng /" + label + " race help"); return true; }
                }
            }
            // /boatracing setup
            if (args[0].equalsIgnoreCase("setup")) {
                if (!p.hasPermission("boatracing.setup")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
                    Text.msg(p, "&eLệnh cấu hình:");
                    Text.tell(p, "&7 - &f/" + label + " setup addstart &7(Thêm vị trí hiện tại làm vị trí bắt đầu; lặp lại để thêm nhiều)");
                    Text.tell(p, "&7 - &f/" + label + " setup clearstarts &7(Xóa tất cả vị trí bắt đầu)");
                    Text.tell(p, "&7 - &f/" + label + " setup setfinish &7(Sử dụng selection của bạn để đặt vùng vạch đích)");
                    Text.tell(p, "&7 - &f/" + label + " setup setpit [team] &7(Đặt vùng pit mặc định từ selection, hoặc pit theo đội nếu cung cấp tên đội)");
                    Text.tell(p, "&7 - &f/" + label + " setup addcheckpoint &7(Thêm checkpoint từ selection; có thể thêm nhiều. Thứ tự quan trọng)");
                    Text.tell(p, "&7 - &f/" + label + " setup addlight &7(Thêm Đèn Redstone đang nhìn thành đèn xuất phát; tối đa 5, từ trái sang phải)");
                    Text.tell(p, "&7 - &f/" + label + " setup clearlights &7(Xóa tất cả đèn xuất phát đã cấu hình)");
                    Text.tell(p, "&7 - &f/" + label + " setup setlaps <n> &7(Đặt số vòng cho cuộc đua)");
                    Text.tell(p, "&7 - &f/" + label + " setup setpitstops <n> &7(Đặt số lần dừng pit bắt buộc để hoàn thành; 0 để vô hiệu)");
                    Text.tell(p, "&7 - &f/" + label + " setup setpos <player> <slot|auto> &7(Gán người chơi vào vị trí bắt đầu cụ thể, 1-based; auto để xóa)");
                    Text.tell(p, "&7 - &f/" + label + " setup clearpos <player> &7(Xóa vị trí bắt đầu tùy chỉnh của người chơi)");
                    Text.tell(p, "&7 - &f/" + label + " setup clearcheckpoints &7(Xóa tất cả checkpoint)");
                    Text.tell(p, "&7 - &f/" + label + " setup show &7(Tóm tắt cấu hình đường đua hiện tại)");
                    Text.tell(p, "&7 - &f/" + label + " setup selinfo &7(Debug selection: selection hiện tại)");
                    Text.tell(p, "&7 - &f/" + label + " setup wand &7(Phát công cụ chọn BoatRacing)");
                    Text.tell(p, "&7 - &f/" + label + " setup wizard &7(Khởi chạy trợ lý thiết lập)");
                    return true;
                }
                String sub = args[1].toLowerCase();
                switch (sub) {
                    case "wand" -> {
                        es.jaie55.boatracing.track.SelectionManager.giveWand(p);
                        Text.msg(p, "&aCông cụ chọn đã sẵn sàng. &7Nhấp trái đánh dấu &fGóc A&7; nhấp phải đánh dấu &fGóc B&7.");
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
                        Text.msg(p, "&aĐã thêm vị trí bắt đầu tại &f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + " &7(" + loc.getWorld().getName() + ")");
                        Text.tell(p, "&7Mẹo: Bạn có thể thêm nhiều vị trí bắt đầu. Chạy lệnh một lần nữa để thêm.");
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "clearstarts" -> {
                        trackConfig.clearStarts();
                        Text.msg(p, "&aĐã xóa tất cả vị trí bắt đầu.");
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "setfinish" -> {
                        var sel = SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            Text.msg(p, "&cKhông phát hiện selection. Dùng công cụ chọn để đánh dấu &fGóc A&c (nhấp trái) và &fGóc B&c (nhấp phải).");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        Region r = new Region(sel.worldName, sel.box);
                        trackConfig.setFinish(r);
                        Text.msg(p, "&aĐã đặt vùng đích (" + fmtBox(sel.box) + ")");
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.3f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "setpit" -> {
                        var sel = SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            Text.msg(p, "&cKhông phát hiện selection. Dùng công cụ chọn để đánh dấu &fGóc A&c (nhấp trái) và &fGóc B&c (nhấp phải).");
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
                            if (ot.isEmpty()) { Text.msg(p, "&cKhông tìm thấy đội."); return true; }
                            trackConfig.setTeamPit(ot.get().getId(), r);
                            Text.msg(p, "&aĐã đặt vùng pit cho đội &f" + ot.get().getName() + " &7(" + fmtBox(sel.box) + ")");
                        } else {
                            trackConfig.setPitlane(r);
                            Text.msg(p, "&aĐã đặt vùng pit mặc định (" + fmtBox(sel.box) + ")");
                        }
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
                    }
                    case "addcheckpoint" -> {
                        var sel = SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            Text.msg(p, "&cKhông phát hiện selection. Dùng công cụ chọn để đánh dấu &fGóc A&c (nhấp trái) và &fGóc B&c (nhấp phải).");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        Region r = new Region(sel.worldName, sel.box);
                        trackConfig.addCheckpoint(r);
                        Text.msg(p, "&aĐã thêm checkpoint #&f" + trackConfig.getCheckpoints().size() + " &7(" + fmtBox(sel.box) + ")");
                        Text.tell(p, "&7Mẹo: Có thể thêm nhiều checkpoint. Thứ tự quan trọng.");
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "addlight" -> {
                        org.bukkit.block.Block target = p.getTargetBlockExact(6);
                        if (target == null) {
                            Text.msg(p, "&cHãy nhìn vào Đèn Redstone trong bán kính 6 block.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        boolean ok = trackConfig.addLight(target);
                        if (!ok) {
                            Text.msg(p, "&cKhông thể thêm đèn. Dùng Đèn Redstone, tránh trùng lặp, tối đa 5.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        Text.msg(p, "&aĐã thêm đèn xuất phát &7(" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")");
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "clearlights" -> {
                        trackConfig.clearLights();
                        p.sendMessage(Text.colorize(prefix + "&aĐã xóa tất cả đèn xuất phát."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "setlaps" -> {
                        if (args.length < 3 || !args[2].matches("\\d+")) {
                            Text.msg(p, "&cCách dùng: /" + label + " setup setlaps <số>");
                            return true;
                        }
                        int laps = Math.max(1, Integer.parseInt(args[2]));
                        raceManager.setTotalLaps(laps);
                        Text.msg(p, "&aĐã đặt số vòng là &f" + laps);
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
                    }
                    case "setpitstops" -> {
                        if (args.length < 3 || !args[2].matches("\\d+")) {
                            Text.msg(p, "&cCách dùng: /" + label + " setup setpitstops <số>");
                            return true;
                        }
                        int req = Math.max(0, Integer.parseInt(args[2]));
                        raceManager.setMandatoryPitstops(req);
                        // Persist to config as the global default
                        getConfig().set("racing.mandatory-pitstops", req);
                        saveConfig();
                        Text.msg(p, "&aĐã đặt số dừng pit bắt buộc là &f" + req);
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
                    }
                    case "setpos" -> {
                        if (args.length < 4) { Text.msg(p, "&cCách dùng: /" + label + " setup setpos <player> <slot|auto>"); return true; }
                        org.bukkit.OfflinePlayer off = resolveOffline(args[2]);
                        if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cKhông tìm thấy người chơi."); return true; }
                        String slotArg = args[3];
                        if (slotArg.equalsIgnoreCase("auto")) {
                            trackConfig.clearCustomStartSlot(off.getUniqueId());
                            Text.msg(p, "&aĐã xóa vị trí bắt đầu tùy chỉnh cho &f" + (off.getName()!=null?off.getName():off.getUniqueId().toString()));
                        } else if (slotArg.matches("\\d+")) {
                            int oneBased = Integer.parseInt(slotArg);
                            if (oneBased < 1 || oneBased > trackConfig.getStarts().size()) { Text.msg(p, "&cVị trí không hợp lệ. Phạm vi: 1-" + trackConfig.getStarts().size()); return true; }
                            trackConfig.setCustomStartSlot(off.getUniqueId(), oneBased - 1);
                            Text.msg(p, "&aĐã gán vị trí bắt đầu tùy chỉnh cho &f" + (off.getName()!=null?off.getName():off.getUniqueId().toString()) + " &7vào vị trí &f#" + oneBased);
                        } else {
                            Text.msg(p, "&cCách dùng: /" + label + " setup setpos <player> <slot|auto>");
                        }
                        return true;
                    }
                    case "clearpos" -> {
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " setup clearpos <player>"); return true; }
                        org.bukkit.OfflinePlayer off = resolveOffline(args[2]);
                        if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cKhông tìm thấy người chơi."); return true; }
                        trackConfig.clearCustomStartSlot(off.getUniqueId());
                        Text.msg(p, "&aĐã xóa vị trí bắt đầu tùy chỉnh cho &f" + (off.getName()!=null?off.getName():off.getUniqueId().toString()));
                        return true;
                    }
                    case "clearcheckpoints" -> {
                        trackConfig.clearCheckpoints();
                        Text.msg(p, "&aĐã xóa tất cả checkpoint.");
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
                        Text.msg(p, "&eCấu hình đường đua:");
                        String tname = (getTrackLibrary() != null && getTrackLibrary().getCurrent() != null) ? getTrackLibrary().getCurrent() : "(unsaved)";
                        Text.tell(p, "&7 - &fĐường đua: &e" + tname);
                        Text.tell(p, "&7 - &fVị trí bắt đầu: &e" + starts);
                        Text.tell(p, "&7 - &fĐèn bắt đầu: &e" + lights + "/5");
                        Text.tell(p, "&7 - &fVùng đích: &e" + (hasFinish ? "có" : "không"));
                        Text.tell(p, "&7 - &fKhu pit (mặc định): &e" + (hasPit ? "có" : "không"));
                        Text.tell(p, "&7 - &fKhu pit theo đội: &e" + (teamPitCount > 0 ? (teamPitCount + " đã cấu hình") : "không có"));
                        Text.tell(p, "&7 - &fVị trí bắt đầu tùy chỉnh: &e" + (customStarts > 0 ? (customStarts + " người") : "không có"));
                        Text.tell(p, "&7 - &fCheckpoints: &e" + cps);
                        Text.tell(p, "&7 - &fSố dừng pit bắt buộc: &e" + raceManager.getMandatoryPitstops());
                    }
                    case "selinfo" -> {
                        java.util.List<String> dump = SelectionUtils.debugSelection(p);
                        Text.msg(p, "&eSelection info:");
                        for (String line : dump) Text.tell(p, "&7 - &f" + line);
                    }
                    default -> Text.msg(p, "&cUnknown setup subcommand. Use /" + label + " setup help");
                }
                return true;
            }
            // /boatracing admin
            if (args[0].equalsIgnoreCase("admin")) {
                if (!p.hasPermission("boatracing.admin")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    return true;
                }
                if (args.length == 1) {
                    // Open Admin GUI by default
                    adminGUI.openMain(p);
                    return true;
                }
                if (args[1].equalsIgnoreCase("help")) {
                    Text.msg(p, "&eAdmin commands:");
                    Text.tell(p, "&7 - &f/" + label + " admin team create <name> [color] [firstMember]");
                    Text.tell(p, "&7 - &f/" + label + " admin team delete <name>");
                    Text.tell(p, "&7 - &f/" + label + " admin team rename <old> <new>");
                    Text.tell(p, "&7 - &f/" + label + " admin team color <name> <DyeColor>");
                    Text.tell(p, "&7 - &f/" + label + " admin team add <name> <player>");
                    Text.tell(p, "&7 - &f/" + label + " admin team remove <name> <player>");
                    Text.tell(p, "&7 - &f/" + label + " admin player setteam <player> <team|none>");
                    Text.tell(p, "&7 - &f/" + label + " admin player setnumber <player> <1-99>");
                    Text.tell(p, "&7 - &f/" + label + " admin player setboat <player> <BoatType>");
                    Text.tell(p, "&7 - &f/" + label + " admin tracks &7(Manage named tracks via GUI)");
                    return true;
                }
                if (args[1].equalsIgnoreCase("tracks")) {
                    if (!p.hasPermission("boatracing.setup")) { Text.msg(p, "&cBạn không có quyền thực hiện điều đó."); return true; }
                    tracksGUI.open(p);
                    return true;
                }
                // admin team ...
                if (args[1].equalsIgnoreCase("team")) {
                    if (args.length < 3) { Text.msg(p, "&cCách dùng: /"+label+" admin team <create|delete|rename|color|add|remove>"); return true; }
                    String op = args[2].toLowerCase();
                    switch (op) {
                        case "create" -> {
                            if (args.length < 4) { Text.msg(p, "&cCách dùng: /"+label+" admin team create <name> [color] [firstMember]"); return true; }
                            String name = args[3];
                            org.bukkit.DyeColor color = org.bukkit.DyeColor.WHITE;
                            if (args.length >= 5) {
                                try { color = org.bukkit.DyeColor.valueOf(args[4].toUpperCase()); } catch (Exception ex) { Text.msg(p, "&cMàu không hợp lệ."); return true; }
                            }
                            java.util.UUID firstMemberId = p.getUniqueId();
                            if (args.length >= 6) {
                                org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[5]);
                                if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cKhông tìm thấy người chơi."); return true; }
                                firstMemberId = off.getUniqueId();
                            }
                            if (teamManager.findByName(name).isPresent()) { Text.msg(p, "&cA team with that name already exists."); return true; }
                            teamManager.createTeam(firstMemberId, name, color);
                            Text.msg(p, "&aĐã tạo đội: &f" + name + " &7(màu: " + color.name() + ")");
                            return true;
                        }
                        case "delete" -> {
                            if (args.length < 4) { Text.msg(p, "&cCách dùng: /"+label+" admin team delete <name>"); return true; }
                            String name = args[3];
                            var ot = teamManager.findByName(name);
                            if (ot.isEmpty()) { Text.msg(p, "&cTeam not found."); return true; }
                            // Notify members before deletion
                            java.util.List<java.util.UUID> members = new java.util.ArrayList<>(ot.get().getMembers());
                            teamManager.removeTeam(ot.get());
                            Text.msg(p, "&aĐã xóa đội: &f" + name);
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
                            if (args.length < 5) { Text.msg(p, "&cCách dùng: /"+label+" admin team rename <old> <new>"); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { Text.msg(p, "&cTeam not found."); return true; }
                            String newName = args[4];
                            if (teamManager.findByName(newName).isPresent()) { Text.msg(p, "&cA team with that name already exists."); return true; }
                            ot.get().setName(newName);
                            teamManager.save();
                            Text.msg(p, "&aTeam renamed to &f" + newName);
                            for (java.util.UUID m : ot.get().getMembers()) {
                                org.bukkit.OfflinePlayer memOp = Bukkit.getOfflinePlayer(m);
                                if (memOp.isOnline() && memOp.getPlayer() != null) {
                                    memOp.getPlayer().sendMessage(Text.colorize(prefix + "&eQuản trị viên đã đổi tên đội của bạn thành &f" + newName + "&e."));
                                    memOp.getPlayer().playSound(memOp.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                                }
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "color" -> {
                            if (args.length < 5) { Text.msg(p, "&cCách dùng: /"+label+" admin team color <name> <DyeColor>"); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { Text.msg(p, "&cTeam not found."); return true; }
                            org.bukkit.DyeColor color;
                            try { color = org.bukkit.DyeColor.valueOf(args[4].toUpperCase()); } catch (Exception ex) { Text.msg(p, "&cMàu không hợp lệ."); return true; }
                            ot.get().setColor(color);
                            teamManager.save();
                            Text.msg(p, "&aTeam color updated: &f" + color.name());
                            for (java.util.UUID m : ot.get().getMembers()) {
                                org.bukkit.OfflinePlayer memOp = Bukkit.getOfflinePlayer(m);
                                if (memOp.isOnline() && memOp.getPlayer() != null) {
                                    memOp.getPlayer().sendMessage(Text.colorize(prefix + "&eQuản trị viên đã thay đổi màu đội của bạn thành &f" + color.name() + "&e."));
                                    memOp.getPlayer().playSound(memOp.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                                }
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "add" -> {
                            if (args.length < 5) { Text.msg(p, "&cCách dùng: /"+label+" admin team add <name> <player>"); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { Text.msg(p, "&cTeam not found."); return true; }
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[4]);
                            if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cPlayer not found locally. Use UUID or ask the player to join once."); return true; }
                            // remove from previous team if any
                            teamManager.getTeamByMember(off.getUniqueId()).ifPresent(prev -> { prev.removeMember(off.getUniqueId()); });
                            boolean ok = teamManager.addMember(ot.get(), off.getUniqueId());
                            if (!ok) { Text.msg(p, "&cTeam is full (max " + teamManager.getMaxMembers() + ")"); return true; }
                            teamManager.save();
                            Text.msg(p, "&aAdded &f" + off.getName() + " &ato team &f" + ot.get().getName());
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eQuản trị viên đã thêm bạn vào đội &f" + ot.get().getName() + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "remove" -> {
                            if (args.length < 5) { Text.msg(p, "&cCách dùng: /"+label+" admin team remove <name> <player>"); return true; }
                            var ot = teamManager.findByName(args[3]);
                            if (ot.isEmpty()) { Text.msg(p, "&cTeam not found."); return true; }
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[4]);
                            if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cPlayer not found locally. Use UUID or ask the player to join once."); return true; }
                            boolean ok = teamManager.removeMember(ot.get(), off.getUniqueId());
                            if (!ok) { Text.msg(p, "&cPlayer is not a member of that team."); return true; }
                            Text.msg(p, "&aRemoved &f" + off.getName() + " &afrom team &f" + ot.get().getName());
                            teamManager.save();
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eBạn đã bị xóa khỏi đội &f" + ot.get().getName() + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        default -> { Text.msg(p, "&cLệnh team không rõ."); return true; }
                    }
                }
                // admin player ...
                if (args[1].equalsIgnoreCase("player")) {
                    if (args.length < 3) { Text.msg(p, "&cCách dùng: /"+label+" admin player <setteam|setnumber|setboat>"); return true; }
                    String op = args[2].toLowerCase();
                    switch (op) {
                        case "setteam" -> {
                            if (args.length < 5) { Text.msg(p, "&cCách dùng: /"+label+" admin player setteam <player> <team|none>"); return true; }
                            org.bukkit.OfflinePlayer off = resolveOffline(args[3]);
                            if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cKhông tìm thấy người chơi cục bộ. Dùng UUID hoặc yêu cầu người chơi tham gia một lần."); return true; }
                            String teamName = args[4];
                            teamManager.getTeamByMember(off.getUniqueId()).ifPresent(prev -> prev.removeMember(off.getUniqueId()));
                            if (!teamName.equalsIgnoreCase("none")) {
                                var ot = teamManager.findByName(teamName);
                                if (ot.isEmpty()) { Text.msg(p, "&cTeam not found."); return true; }
                                if (!teamManager.addMember(ot.get(), off.getUniqueId())) { Text.msg(p, "&cTeam is full (max " + teamManager.getMaxMembers() + ")"); return true; }
                            }
                            teamManager.save();
                            Text.msg(p, "&aPlayer &f" + off.getName() + " &aassigned to team &f" + (teamName.equalsIgnoreCase("none")?"none":teamName));
                            if (off.isOnline() && off.getPlayer() != null) {
                                if (teamName.equalsIgnoreCase("none")) {
                                    off.getPlayer().sendMessage(Text.colorize(prefix + "&eBạn đã bị xóa khỏi đội của mình."));
                                    off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                                } else {
                                    off.getPlayer().sendMessage(Text.colorize(prefix + "&eQuản trị viên đã phân bạn vào đội &f" + teamName + "&e."));
                                    off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                                }
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "setnumber" -> {
                            if (args.length < 5) { Text.msg(p, "&cCách dùng: /"+label+" admin player setnumber <player> <1-99>"); return true; }
                            org.bukkit.OfflinePlayer off = resolveOffline(args[3]);
                            if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cKhông tìm thấy người chơi."); return true; }
                            int num;
                            try { num = Integer.parseInt(args[4]); } catch (Exception ex) { Text.msg(p, "&cSố không hợp lệ."); return true; }
                            if (num < 1 || num > 99) { Text.msg(p, "&cSố phải là 1-99."); return true; }
                            var ot = teamManager.getTeamByMember(off.getUniqueId());
                            if (ot.isEmpty()) { Text.msg(p, "&cNgười chơi chưa ở trong đội."); return true; }
                            // Optional: global uniqueness check could go here
                            ot.get().setRacerNumber(off.getUniqueId(), num);
                            teamManager.save();
                            Text.msg(p, "&aSet racer number for &f" + off.getName() + " &ato &f" + num);
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eQuản trị viên đã thay đổi số đua của bạn thành &f" + num + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        case "setboat" -> {
                            if (args.length < 5) { Text.msg(p, "&cCách dùng: /"+label+" admin player setboat <player> <BoatType>"); return true; }
                            org.bukkit.OfflinePlayer off = resolveOffline(args[3]);
                            if (off == null || off.getUniqueId() == null) { Text.msg(p, "&cKhông tìm thấy người chơi."); return true; }
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
                            if (!allowed.contains(type)) { Text.msg(p, "&cLoại tàu không hợp lệ."); return true; }
                            var ot = teamManager.getTeamByMember(off.getUniqueId());
                            if (ot.isEmpty()) { Text.msg(p, "&cNgười chơi chưa ở trong đội."); return true; }
                            ot.get().setBoatType(off.getUniqueId(), type);
                            teamManager.save();
                            Text.msg(p, "&aSet boat type for &f" + off.getName() + " &ato &f" + type);
                            if (off.isOnline() && off.getPlayer() != null) {
                                off.getPlayer().sendMessage(Text.colorize(prefix + "&eQuản trị viên đã thay đổi loại tàu của bạn thành &f" + type + "&e."));
                                off.getPlayer().playSound(off.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                            }
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                            return true;
                        }
                        default -> { Text.msg(p, "&cHành động player không hợp lệ."); return true; }
                    }
                }
                Text.msg(p, "&cCách dùng: /"+label+" admin help");
                return true;
            }
            if (!args[0].equalsIgnoreCase("teams")) {
                Text.msg(p, "&cCách dùng: /" + label + " teams|setup|reload|version");
                return true;
            }
            // /boatracing teams
            if (args.length == 1) {
                if (!p.hasPermission("boatracing.teams")) {
                    p.sendMessage(Text.colorize(prefix + "&cBạn không có quyền thực hiện điều đó."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                teamGUI.openMain(p);
                return true;
            }
            // /boatracing teams create <name>
            if (!p.hasPermission("boatracing.teams")) {
                p.sendMessage(Text.colorize(prefix + "&cBạn không có quyền thực hiện điều đó."));
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
                    p.sendMessage(Text.colorize(prefix + "&cCách dùng: /boatracing teams create <name>"));
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
                if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                es.jaie55.boatracing.team.Team t = ot.get();
                boolean allowRename = getConfig().getBoolean("player-actions.allow-team-rename", false);
                if (!allowRename && !p.hasPermission("boatracing.admin")) { p.sendMessage(Text.colorize(prefix + "&cThis server has restricted team renaming. Only an administrator can rename teams.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cCách dùng: /boatracing teams rename <new name>")); return true; }
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
                if (!allowColor && !p.hasPermission("boatracing.admin")) { Text.msg(p, "&cThis server has restricted team colors. Only an administrator can change team colors."); return true; }
                if (args.length < 3) { Text.msg(p, "&cUsage: /boatracing teams color <dyeColor>"); return true; }
                try {
                    org.bukkit.DyeColor dc = org.bukkit.DyeColor.valueOf(args[2].toUpperCase());
                    t.setColor(dc); teamManager.save();
                    Text.msg(p, "&aTeam color set to &e" + dc.name() + "&a.");
                } catch (IllegalArgumentException ex) {
                    Text.msg(p, "&cInvalid color.");
                }
                return true;
            }
            // /boatracing teams join <team name>
            if (args.length >= 2 && args[1].equalsIgnoreCase("join")) {
                if (teamManager.getTeamByMember(p.getUniqueId()).isPresent()) {
                    Text.msg(p, "&cYou are already in a team. Leave it first.");
                    return true;
                }
                if (args.length < 3) { Text.msg(p, "&cUsage: /boatracing teams join <team name>"); return true; }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                es.jaie55.boatracing.team.Team target = teamManager.getTeams().stream()
                    .filter(t -> t.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
                if (target == null) { Text.msg(p, "&cTeam not found."); return true; }
                if (target.getMembers().size() >= teamManager.getMaxMembers()) { Text.msg(p, "&cThis team is full."); return true; }
                target.addMember(p.getUniqueId()); teamManager.save();
                Text.msg(p, "&aYou joined &e" + target.getName() + "&a.");
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
                if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                es.jaie55.boatracing.team.Team t = ot.get();
                if (t.getMembers().size() <= 1) {
                    pendingDisband.add(p.getUniqueId());
                    Text.msg(p, "&eYou are the last member. Leaving will delete the team.");
                    Text.msg(p, "&7Type &b/"+label+" teams confirm &7to proceed or &b/"+label+" teams cancel &7to abort.");
                    return true;
                }
                t.removeMember(p.getUniqueId());
                teamManager.save();
                Text.msg(p, "&aYou left the team.");
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
                if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                Text.msg(p, "&cThis action is admin-only. Use /"+label+" admin team remove <team> <player>.");
                return true;
            }
            // /boatracing teams transfer <playerName>
            if (args.length >= 2 && args[1].equalsIgnoreCase("transfer")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                Text.msg(p, "&cLeader system has been removed. Use admin commands if you need to manage teams.");
                return true;
            }
            // /boatracing teams boat <type>
            if (args.length >= 2 && args[1].equalsIgnoreCase("boat")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                if (args.length < 3) { Text.msg(p, "&cUsage: /boatracing teams boat <type>"); return true; }
                boolean allowBoat = getConfig().getBoolean("player-actions.allow-set-boat", true);
                if (!allowBoat) { Text.msg(p, "&cThis server has restricted boat changes. Only an administrator can set your boat."); return true; }
                String type = args[2].toUpperCase();
                // Accept RAFT tokens directly, otherwise require a valid BOAT material
                if (type.equals("RAFT") || type.equals("CHEST_RAFT")) {
                    ot.get().setBoatType(p.getUniqueId(), type);
                    teamManager.save();
                    Text.msg(p, "&aYour boat set to &e" + type.toLowerCase() + "&a.");
                } else {
                    try {
                        org.bukkit.Material m = org.bukkit.Material.valueOf(type);
                        if (!m.name().endsWith("BOAT")) throw new IllegalArgumentException();
                        ot.get().setBoatType(p.getUniqueId(), m.name());
                        teamManager.save();
                        Text.msg(p, "&aYour boat set to &e" + type.toLowerCase() + "&a.");
                    } catch (IllegalArgumentException ex) {
                        Text.msg(p, "&cLoại tàu không hợp lệ.");
                    }
                }
                return true;
            }
            // /boatracing teams number <1-99>
            if (args.length >= 2 && args[1].equalsIgnoreCase("number")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                if (args.length < 3) { Text.msg(p, "&cUsage: /boatracing teams number <1-99>"); return true; }
                boolean allowNumber = getConfig().getBoolean("player-actions.allow-set-number", true);
                if (!allowNumber) { Text.msg(p, "&cThis server has restricted racer numbers. Only an administrator can set your racer number."); return true; }
                String s = args[2];
                if (!s.matches("\\d+")) { Text.msg(p, "&cPlease enter digits only."); return true; }
                int n = Integer.parseInt(s);
                if (n < 1 || n > 99) { Text.msg(p, "&cSố phải nằm trong khoảng 1 đến 99."); return true; }
                ot.get().setRacerNumber(p.getUniqueId(), n); teamManager.save();
                Text.msg(p, "&aYour racer # set to " + n + ".");
                return true;
            }
            // /boatracing teams disband y /boatracing teams confirm
            if (args.length >= 2 && args[1].equalsIgnoreCase("disband")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                Text.msg(p, "&cThis action is admin-only. Use /"+label+" admin team delete <team>.");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
                // Confirm pending dangerous actions (last-member leave -> disband)
                if (pendingDisband.remove(p.getUniqueId())) {
                    java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                    if (ot.isEmpty()) { Text.msg(p, "&cYou are not in a team."); return true; }
                    es.jaie55.boatracing.team.Team t = ot.get();
                    // Proceed: remove member (self) and delete team since no members left
                    t.removeMember(p.getUniqueId());
                    teamManager.deleteTeam(t);
                    Text.msg(p, "&aYou left and the team was deleted (no members left).");
                    return true;
                }
                Text.msg(p, "&cNothing to confirm.");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
                boolean any = false;
                if (pendingDisband.remove(p.getUniqueId())) { any = true; }
                if (pendingTransfer.remove(p.getUniqueId()) != null) { any = true; }
                if (pendingKick.remove(p.getUniqueId()) != null) { any = true; }
                if (!any) {
                    Text.msg(p, "&cNothing to cancel.");
                    return true;
                }
                        Text.msg(p, "&aCancelled.");
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
                return true;
            }
            // default fallback
            p.sendMessage(Text.colorize(prefix + "&cLệnh con không hợp lệ. Sử dụng: /boatracing version|reload|setup hoặc /boatracing teams [create|rename|color|join|leave|kick|boat|number|transfer|disband|confirm|cancel]"));
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
            Text.msg(p, "&7Update check failed. See console for details.");
            Text.msg(p, "&7Releases: &f" + updateChecker.getLatestUrl());
            return;
        }
        if (updateChecker.isOutdated()) {
            int behind = updateChecker.getBehindCount();
            String latest = updateChecker.getLatestVersion() != null ? updateChecker.getLatestVersion() : "latest";
            Text.msg(p, "&eAn update for " + getName() + " is available. You are " + behind + " version(s) behind.");
            Text.msg(p, "&eYou are running &6" + current + "&e, the latest version is &6" + latest + "&e.");
            Text.msg(p, "&eUpdate at &f" + updateChecker.getLatestUrl());
        } else {
            String latest = updateChecker.getLatestVersion() != null ? updateChecker.getLatestVersion() : current;
            Text.msg(p, "&aYou're running the latest version (&f" + current + "&a).");
            Text.msg(p, "&7Latest: &f" + latest + " &7| Releases: &f" + updateChecker.getLatestUrl());
        }
    }

    private static String fmtBox(org.bukkit.util.BoundingBox b) {
        return String.format("min(%d,%d,%d) max(%d,%d,%d)",
                (int) Math.floor(b.getMinX()), (int) Math.floor(b.getMinY()), (int) Math.floor(b.getMinZ()),
                (int) Math.floor(b.getMaxX()), (int) Math.floor(b.getMaxY()), (int) Math.floor(b.getMaxZ()));
    }
}
