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

import es.jaie55.boatracing.util.Text;
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
    private es.jaie55.boatracing.ui.AdminGUI adminGUI;
    private es.jaie55.boatracing.ui.AdminRaceGUI adminRaceGUI;
    private es.jaie55.boatracing.profile.PlayerProfileManager profileManager;
    private es.jaie55.boatracing.ui.ProfileGUI profileGUI;
    private es.jaie55.boatracing.ui.ScoreboardService scoreboardService;
    private String prefix;
    private TrackConfig trackConfig;
    private TrackLibrary trackLibrary;
    private RaceManager raceManager;
    private SetupWizard setupWizard;
    private es.jaie55.boatracing.ui.AdminTracksGUI tracksGUI;
    // Last latest-version announced in console due to 5-minute silent checks (to avoid duplicate prints)
    private volatile String lastConsoleAnnouncedVersion = null;
    // Plugin metadata (avoid deprecated getDescription())
    private String pluginVersion = "unknown";
    private java.util.List<String> pluginAuthors = java.util.Collections.emptyList();
    // Team and pit features removed

    public static BoatRacingPlugin getInstance() { return instance; }
    public String pref() { return prefix; }
    public es.jaie55.boatracing.ui.AdminGUI getAdminGUI() { return adminGUI; }
    public es.jaie55.boatracing.ui.AdminRaceGUI getAdminRaceGUI() { return adminRaceGUI; }
    public es.jaie55.boatracing.profile.PlayerProfileManager getProfileManager() { return profileManager; }
    public es.jaie55.boatracing.ui.ProfileGUI getProfileGUI() { return profileGUI; }
    public es.jaie55.boatracing.ui.ScoreboardService getScoreboardService() { return scoreboardService; }
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
        // Load plugin metadata (version/authors) from plugin.yml to avoid deprecated API
        loadPluginMeta();
        // Team and pit features removed
    this.adminGUI = new es.jaie55.boatracing.ui.AdminGUI(this);
    this.adminRaceGUI = new es.jaie55.boatracing.ui.AdminRaceGUI(this);
    this.profileManager = new es.jaie55.boatracing.profile.PlayerProfileManager(getDataFolder());
    this.profileGUI = new es.jaie55.boatracing.ui.ProfileGUI(this);
    this.scoreboardService = new es.jaie55.boatracing.ui.ScoreboardService(this);
    this.trackConfig = new TrackConfig(getDataFolder());
    this.trackLibrary = new TrackLibrary(getDataFolder(), trackConfig);
    this.raceManager = new RaceManager(this, trackConfig);
    this.setupWizard = new SetupWizard(this);
    this.tracksGUI = new es.jaie55.boatracing.ui.AdminTracksGUI(this, trackLibrary);
    // Team GUI removed
    Bukkit.getPluginManager().registerEvents(adminGUI, this);
    Bukkit.getPluginManager().registerEvents(tracksGUI, this);
    Bukkit.getPluginManager().registerEvents(adminRaceGUI, this);
    Bukkit.getPluginManager().registerEvents(profileGUI, this);
    try { scoreboardService.start(); } catch (Throwable ignored) {}
    
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
    
// Removed bStats metrics and the external update checker per configuration.
        // If you need to re-enable update checking or metrics, re-add a custom implementation and config keys.

    // ViaVersion integration and internal scoreboard number hiding removed by request

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

    // Load plugin metadata (version/authors) from plugin.yml to avoid calling deprecated getDescription()
    private void loadPluginMeta() {
        InputStream is = getResource("plugin.yml");
        if (is == null) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
        this.pluginVersion = y.getString("version", this.pluginVersion);
        java.util.List<String> authors = y.getStringList("authors");
        if (authors != null && !authors.isEmpty()) this.pluginAuthors = authors;
    }

    public String getPluginVersion() { return pluginVersion; }
    public java.util.List<String> getPluginAuthors() { return pluginAuthors; }


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
        // Nothing to persist
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
                Text.msg(p, "&cCách dùng: /" + label + " profile|race|setup|reload|version");
                return true;
            }
            if (args[0].equalsIgnoreCase("scoreboard") || args[0].equalsIgnoreCase("sb")) {
                if (!p.hasPermission("boatracing.admin")) { Text.msg(p, "&cBạn không có quyền thực hiện điều đó."); return true; }
                if (args.length < 2) {
                    Text.msg(p, "&eDùng: /"+label+" scoreboard <on|off|tick>");
                    return true;
                }
                String sub = args[1].toLowerCase();
                switch (sub) {
                    case "on" -> { try { scoreboardService.start(); Text.msg(p, "&aScoreboard bật."); } catch (Throwable ignored) {} }
                    case "off" -> {
                        try { scoreboardService.stop(); } catch (Throwable ignored) {}
                        for (org.bukkit.entity.Player pl : org.bukkit.Bukkit.getOnlinePlayers()) {
                            org.bukkit.scoreboard.Scoreboard sb = org.bukkit.Bukkit.getScoreboardManager().getNewScoreboard();
                            pl.setScoreboard(sb);
                        }
                        Text.msg(p, "&aScoreboard tắt.");
                    }
                    case "tick" -> { try { es.jaie55.boatracing.ui.ScoreboardService svc = scoreboardService; if (svc != null) svc.forceTick(); Text.msg(p, "&aĐã cập nhật."); } catch (Throwable ignored) {} }
                    default -> Text.msg(p, "&eDùng: /"+label+" scoreboard <on|off|tick>");
                }
                return true;
            }
                        if (args[0].equalsIgnoreCase("profile")) {
                            // Open player profile GUI
                            profileGUI.open(p);
                            return true;
                        }
            // /boatracing version
            if (args[0].equalsIgnoreCase("version")) {
                if (!p.hasPermission("boatracing.version")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                String current = pluginVersion;
                java.util.List<String> authors = pluginAuthors;
                Text.msg(p, "&e" + getName() + "-" + current);
                if (!authors.isEmpty()) {
                    Text.msg(p, "&eAuthors: &f" + String.join(", ", authors));
                }
                

                // Update checks removed; only display local metadata here
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                if (!p.hasPermission("boatracing.reload")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return true;
                }
                // Reload config and data
                reloadConfig();
                // After reload, also merge any new defaults into config.yml
                try { mergeConfigDefaults(); } catch (Throwable ignored) {}
                this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
                // Team features removed; nothing to re-create
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
                        // Team requirement removed: racers can join solo
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
                        boolean hasPit = trackConfig.getPitlane() != null; // pit mechanic removed
                        boolean ready = trackConfig.isReady();
                        java.util.List<String> missing = ready ? java.util.Collections.emptyList() : trackConfig.missingRequirements();

                        Text.msg(p, "&eTrạng thái cuộc đua:");
                        Text.tell(p, "&7Đường đua: &f" + cur);
                        Text.tell(p, running ? "&aĐang chạy &7(Tham gia: &f" + participants + "&7)" : "&7Không có cuộc đua đang chạy.");
                        Text.tell(p, registering ? "&eĐăng ký mở &7(Đã đăng ký: &f" + regs + "&7)" : "&7Đăng ký đóng.");
                        Text.tell(p, "&7Số vòng: &f" + laps);
                        Text.tell(p, "&7Vị trí bắt đầu: &f" + starts + " &8• &7Đèn xuất phát: &f" + lights + "/5 &8• &7Vạch kết thúc: &f" + (hasFinish?"có":"không"));
                        Text.tell(p, "&7Điểm checkpoint: &f" + cps);
                        // pit mechanic removed
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
                    // pit mechanic removed
                    Text.tell(p, "&7 - &f/" + label + " setup addcheckpoint &7(Thêm checkpoint từ selection; có thể thêm nhiều. Thứ tự quan trọng)");
                    Text.tell(p, "&7 - &f/" + label + " setup addlight &7(Thêm Đèn Redstone đang nhìn thành đèn xuất phát; tối đa 5, từ trái sang phải)");
                    Text.tell(p, "&7 - &f/" + label + " setup clearlights &7(Xóa tất cả đèn xuất phát đã cấu hình)");
                    Text.tell(p, "&7 - &f/" + label + " setup setlaps <n> &7(Đặt số vòng cho cuộc đua)");
                    // pit mechanic removed
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
                    // case "setpit" removed
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
                        Text.msg(p, "&aĐã xóa tất cả đèn xuất phát.");
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
                    // case "setpitstops" removed
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
                        boolean hasPit = trackConfig.getPitlane() != null; // pit mechanic removed
                        int teamPitCount = trackConfig.getTeamPits().size();
                        int customStarts = trackConfig.getCustomStartSlots().size();
                        Text.msg(p, "&eCấu hình đường đua:");
                        String tname = (getTrackLibrary() != null && getTrackLibrary().getCurrent() != null) ? getTrackLibrary().getCurrent() : "(unsaved)";
                        Text.tell(p, "&7 - &fĐường đua: &e" + tname);
                        Text.tell(p, "&7 - &fVị trí bắt đầu: &e" + starts);
                        Text.tell(p, "&7 - &fĐèn bắt đầu: &e" + lights + "/5");
                        Text.tell(p, "&7 - &fVùng đích: &e" + (hasFinish ? "có" : "không"));
                        // pit mechanic removed
                        Text.tell(p, "&7 - &fVị trí bắt đầu tùy chỉnh: &e" + (customStarts > 0 ? (customStarts + " người") : "không có"));
                        Text.tell(p, "&7 - &fCheckpoints: &e" + cps);
                        // pit mechanic removed
                    }
                    case "selinfo" -> {
                        java.util.List<String> dump = SelectionUtils.debugSelection(p);
                        Text.msg(p, "&eThông tin vùng chọn:");
                        for (String line : dump) Text.tell(p, "&7 - &f" + line);
                    }
                    default -> Text.msg(p, "&cLệnh cấu hình không rõ. Dùng /" + label + " setup help");
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
                    Text.msg(p, "&eLệnh quản trị:");
                    Text.tell(p, "&7 - &f/" + label + " admin tracks &7(Quản lý đường đua qua GUI)");
                    return true;
                }
                if (args[1].equalsIgnoreCase("tracks")) {
                    if (!p.hasPermission("boatracing.setup")) { Text.msg(p, "&cBạn không có quyền thực hiện điều đó."); return true; }
                    tracksGUI.open(p);
                    return true;
                }
                // Only tracks admin remains
                Text.msg(p, "&cCách dùng: /"+label+" admin help");
                return true;
            }
            // default fallback (teams removed)
            Text.msg(p, "&cLệnh con không hợp lệ. Sử dụng: /boatracing version|reload|setup|race");
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
                // if (sender.hasPermission("boatracing.teams")) root.add("teams");
                root.add("profile");
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
                // if (sender.hasPermission("boatracing.teams")) root.add("teams");
                root.add("profile");
                root.add("race");
                if (sender.hasPermission("boatracing.setup")) root.add("setup");
                if (sender.hasPermission("boatracing.admin")) root.add("admin");
                if (sender.hasPermission("boatracing.reload")) root.add("reload");
                if (sender.hasPermission("boatracing.version")) root.add("version");
                return root.stream().filter(s -> s.startsWith(pref)).toList();
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
                if (!sender.hasPermission("boatracing.admin")) return java.util.Collections.emptyList();
                if (args.length == 2) return java.util.Arrays.asList("help","tracks");
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
                if (args.length == 2) return Arrays.asList("help","addstart","clearstarts","setfinish","addcheckpoint","clearcheckpoints","addlight","clearlights","setpos","clearpos","show","selinfo","wand","wizard");
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
            // teams tab-completion removed
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }


    private static String fmtBox(org.bukkit.util.BoundingBox b) {
        return String.format("min(%d,%d,%d) max(%d,%d,%d)",
                (int) Math.floor(b.getMinX()), (int) Math.floor(b.getMinY()), (int) Math.floor(b.getMinZ()),
                (int) Math.floor(b.getMaxX()), (int) Math.floor(b.getMaxY()), (int) Math.floor(b.getMaxZ()));
    }
}
