package dev.belikhun.boatracing;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
// No TabExecutor needed: JavaPlugin already handles CommandExecutor and TabCompleter when overriding methods
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Collections;
import java.util.List;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.track.TrackLibrary;
import dev.belikhun.boatracing.track.Region;
import dev.belikhun.boatracing.track.SelectionUtils;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.setup.SetupWizard;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class BoatRacingPlugin extends JavaPlugin {
    private static BoatRacingPlugin instance;
    private dev.belikhun.boatracing.ui.AdminGUI adminGUI;
    private dev.belikhun.boatracing.ui.AdminRaceGUI adminRaceGUI;
    private dev.belikhun.boatracing.profile.PlayerProfileManager profileManager;
    private dev.belikhun.boatracing.ui.ProfileGUI profileGUI;
    private dev.belikhun.boatracing.ui.TrackSelectGUI trackSelectGUI;
    private dev.belikhun.boatracing.ui.HotbarService hotbarService;
    private dev.belikhun.boatracing.ui.ScoreboardService scoreboardService;
    private String prefix;
    private TrackConfig trackConfig;
    private TrackLibrary trackLibrary;
    private dev.belikhun.boatracing.race.RaceService raceService;
    private SetupWizard setupWizard;
    private dev.belikhun.boatracing.ui.AdminTracksGUI tracksGUI;
    private dev.belikhun.boatracing.track.TrackRecordManager trackRecordManager;
    private dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService lobbyBoardService;
    // Plugin metadata (avoid deprecated getDescription())
    private String pluginVersion = "unknown";
    private java.util.List<String> pluginAuthors = java.util.Collections.emptyList();
    // Team and pit features removed

    public static BoatRacingPlugin getInstance() { return instance; }
    public String pref() { return prefix; }
    public dev.belikhun.boatracing.ui.AdminGUI getAdminGUI() { return adminGUI; }
    public dev.belikhun.boatracing.ui.AdminRaceGUI getAdminRaceGUI() { return adminRaceGUI; }
    public dev.belikhun.boatracing.profile.PlayerProfileManager getProfileManager() { return profileManager; }
    public dev.belikhun.boatracing.ui.ProfileGUI getProfileGUI() { return profileGUI; }
    public dev.belikhun.boatracing.ui.TrackSelectGUI getTrackSelectGUI() { return trackSelectGUI; }
    public dev.belikhun.boatracing.ui.HotbarService getHotbarService() { return hotbarService; }
    public dev.belikhun.boatracing.ui.ScoreboardService getScoreboardService() { return scoreboardService; }
    public dev.belikhun.boatracing.race.RaceService getRaceService() { return raceService; }
    public TrackConfig getTrackConfig() { return trackConfig; }
    public TrackLibrary getTrackLibrary() { return trackLibrary; }
    public dev.belikhun.boatracing.ui.AdminTracksGUI getTracksGUI() { return tracksGUI; }
    public dev.belikhun.boatracing.track.TrackRecordManager getTrackRecordManager() { return trackRecordManager; }
    public dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService getLobbyBoardService() { return lobbyBoardService; }
    

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
    this.adminGUI = new dev.belikhun.boatracing.ui.AdminGUI(this);
    this.adminRaceGUI = new dev.belikhun.boatracing.ui.AdminRaceGUI(this);
    this.profileManager = new dev.belikhun.boatracing.profile.PlayerProfileManager(getDataFolder());
	this.trackRecordManager = new dev.belikhun.boatracing.track.TrackRecordManager(getDataFolder());
    this.profileGUI = new dev.belikhun.boatracing.ui.ProfileGUI(this);
    this.trackSelectGUI = new dev.belikhun.boatracing.ui.TrackSelectGUI(this);
    this.trackConfig = new TrackConfig(this, getDataFolder());
    this.trackLibrary = new TrackLibrary(getDataFolder(), trackConfig);
    this.raceService = new dev.belikhun.boatracing.race.RaceService(this);
    this.scoreboardService = new dev.belikhun.boatracing.ui.ScoreboardService(this);
    this.hotbarService = new dev.belikhun.boatracing.ui.HotbarService(this);
    this.setupWizard = new SetupWizard(this);
    this.tracksGUI = new dev.belikhun.boatracing.ui.AdminTracksGUI(this, trackLibrary);
    this.lobbyBoardService = new dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService(this);
    // Team GUI removed
    Bukkit.getPluginManager().registerEvents(adminGUI, this);
    Bukkit.getPluginManager().registerEvents(tracksGUI, this);
    Bukkit.getPluginManager().registerEvents(adminRaceGUI, this);
    Bukkit.getPluginManager().registerEvents(profileGUI, this);
    Bukkit.getPluginManager().registerEvents(trackSelectGUI, this);
    Bukkit.getPluginManager().registerEvents(new dev.belikhun.boatracing.ui.HotbarListener(this, hotbarService), this);
    try {
        if (scoreboardService != null) {
            scoreboardService.start();
            boolean sbDebug = getConfig().getBoolean("scoreboard.debug", false);
            scoreboardService.setDebug(sbDebug);
            if (sbDebug) getLogger().info("[SB] Debug enabled via config");
        }
    } catch (Throwable ignored) {}

    // Hotbar UX items
    try {
        if (hotbarService != null) hotbarService.start();
    } catch (Throwable ignored) {}
    
    dev.belikhun.boatracing.track.SelectionManager.init(this);
    Bukkit.getPluginManager().registerEvents(new dev.belikhun.boatracing.track.WandListener(this), this);
    // Movement listener for race tracking
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            private final java.util.Map<java.util.UUID, Long> lastCpDbg = new java.util.HashMap<>();

            @org.bukkit.event.EventHandler
            public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
                if (e.getTo() == null) return;
                if (raceService == null) return;
                var rm = raceService.findRaceFor(e.getPlayer().getUniqueId());
                if (rm == null || !rm.isRunning()) return;
                rm.tickPlayer(e.getPlayer(), e.getFrom(), e.getTo());
            }

            @org.bukkit.event.EventHandler(ignoreCancelled = true)
            public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent e) {
                if (raceService == null) return;
                org.bukkit.entity.Entity vehicle = e.getVehicle();
                boolean boatLike = (vehicle instanceof org.bukkit.entity.Boat) || (vehicle instanceof org.bukkit.entity.ChestBoat);
                if (!boatLike) {
                    try {
                        String t = vehicle.getType() != null ? vehicle.getType().name() : null;
                        boatLike = t != null && (t.endsWith("_BOAT") || t.endsWith("_CHEST_BOAT") || t.endsWith("_RAFT") || t.endsWith("_CHEST_RAFT")
                                || t.equals("BOAT") || t.equals("CHEST_BOAT"));
                    } catch (Throwable ignored) { boatLike = false; }
                }
                if (!boatLike) return;
                org.bukkit.Location to = e.getTo();
                org.bukkit.Location from = e.getFrom();
                if (to == null || from == null) return;

                boolean cpDbg = false;
                try { cpDbg = getConfig().getBoolean("racing.debug.checkpoints", false); } catch (Throwable ignored) {}

                // Tick checkpoints using the vehicle position (players in boats may not fire PlayerMoveEvent reliably)
                // Tick checkpoints using the vehicle position (players in boats may not fire PlayerMoveEvent reliably)
                // Route to the correct race manager per player (supports multiple concurrent races).
                    for (org.bukkit.entity.Entity passenger : vehicle.getPassengers()) {
                        if (passenger instanceof org.bukkit.entity.Player p) {
                            RaceManager raceManager = raceService.findRaceFor(p.getUniqueId());
                            if (raceManager == null || !raceManager.isRunning()) continue;
                            if (cpDbg) {
                                long now = System.currentTimeMillis();
                                Long prev = lastCpDbg.get(p.getUniqueId());
                                if (prev == null || (now - prev) >= 1000L) {
                                    lastCpDbg.put(p.getUniqueId(), now);
                                    getLogger().info("[CPDBG] VehicleMoveEvent tick for " + p.getName()
                                            + " to=" + dev.belikhun.boatracing.util.Text.fmtPos(to)
                                            + " checkpoints=" + (raceManager.getTrackConfig() == null ? 0 : raceManager.getTrackConfig().getCheckpoints().size())
                                            + " expectedNext=" + (raceManager.getParticipantState(p.getUniqueId()) == null ? "?" : (raceManager.getParticipantState(p.getUniqueId()).nextCheckpointIndex + 1))
                                    );
                                }
                            }
                            raceManager.tickPlayer(p, from, to);
                        }
                    }

                // Freeze boats during the start countdown so racers can't move before GO
                // (only if at least one passenger is in countdown in their race).

                boolean hasCountdownRacer = false;
                org.bukkit.Location lock = null;
                for (org.bukkit.entity.Entity passenger : vehicle.getPassengers()) {
                    if (passenger instanceof org.bukkit.entity.Player p) {
                        RaceManager raceManager = raceService.findRaceFor(p.getUniqueId());
                        if (raceManager != null && raceManager.isCountdownActiveFor(p.getUniqueId())) {
                            hasCountdownRacer = true;
                            lock = raceManager.getCountdownLockLocation(p.getUniqueId());
                            break;
                        }
                    }
                }
                if (!hasCountdownRacer) return;

                try {
                    org.bukkit.Location target = (lock != null ? lock : from);
                    if (target != null) {
                        // Ensure lock has a world; Bukkit teleport returns false if world is null.
                        try {
                            if (target.getWorld() == null) target.setWorld(vehicle.getWorld());
                        } catch (Throwable ignored) {}

                        vehicle.setVelocity(new org.bukkit.util.Vector(0, 0, 0));

                        boolean tpOk;
                        try {
                            tpOk = vehicle.teleport(target, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                        } catch (Throwable t) {
                            try { tpOk = vehicle.teleport(target); } catch (Throwable ignored) { tpOk = false; }
                        }
                        if (!tpOk) {
                            try { dev.belikhun.boatracing.util.EntityForceTeleport.nms(vehicle, target); } catch (Throwable ignored) {}
                        }

                        vehicle.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                        try { vehicle.setRotation(target.getYaw(), target.getPitch()); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        }, this);

        // MapEngine lobby board (optional)
        try {
            if (lobbyBoardService != null) {
                lobbyBoardService.reloadFromConfig();
            }
        } catch (Throwable ignored) {}

        // Prevent racers from leaving their boat during countdown/race
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(ignoreCancelled = true)
            public void onVehicleExit(org.bukkit.event.vehicle.VehicleExitEvent e) {
                if (raceService == null) return;
                if (!(e.getExited() instanceof org.bukkit.entity.Player p)) return;
                RaceManager rm = raceService.findRaceFor(p.getUniqueId());
                if (rm == null) return;
                if (!rm.shouldPreventBoatExit(p.getUniqueId())) return;
                e.setCancelled(true);
            }
        }, this);

        // Respawn racers at their last checkpoint, or at start if all checkpoints were reached.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
                if (raceService == null) return;
                org.bukkit.entity.Player p = e.getPlayer();
                if (p == null) return;
                RaceManager raceManager = raceService.findRaceFor(p.getUniqueId());
                if (raceManager == null) return;
                org.bukkit.Location target = raceManager.getRaceRespawnLocation(p.getUniqueId(), p.getLocation());
                if (target != null && target.getWorld() != null) {
                    e.setRespawnLocation(target);

                    // Play respawn cue after the respawn has applied.
                    try {
                        Bukkit.getScheduler().runTaskLater(BoatRacingPlugin.this, () -> {
                            try {
                                if (!p.isOnline()) return;
                                try { raceManager.ensureRacerHasBoat(p); } catch (Throwable ignored) {}
                                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
                            } catch (Throwable ignored) {}
                        }, 2L);
                    } catch (Throwable ignored) {}
                }
            }
        }, this);

        // Disqualify racers who disconnect mid-race and clean up their state/boat.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                if (raceService == null) return;
                org.bukkit.entity.Player p = e.getPlayer();
                if (p == null) return;
                try { raceService.handleDisconnect(p.getUniqueId()); } catch (Throwable ignored) {}
            }

            @org.bukkit.event.EventHandler
            public void onKick(org.bukkit.event.player.PlayerKickEvent e) {
                if (raceService == null) return;
                org.bukkit.entity.Player p = e.getPlayer();
                if (p == null) return;
                try { raceService.handleDisconnect(p.getUniqueId()); } catch (Throwable ignored) {}
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

    private static org.bukkit.block.Block getTargetBlockLenient(org.bukkit.entity.Player p, int range) {
        if (p == null) return null;
        try {
            try {
                org.bukkit.block.Block b = p.getTargetBlockExact(range, org.bukkit.FluidCollisionMode.ALWAYS);
                if (b != null) return b;
            } catch (Throwable ignored) {}

            try {
                org.bukkit.block.Block b = p.getTargetBlockExact(range);
                if (b != null) return b;
            } catch (Throwable ignored) {}

            try {
                org.bukkit.util.RayTraceResult rr = p.rayTraceBlocks((double) range, org.bukkit.FluidCollisionMode.ALWAYS);
                if (rr != null && rr.getHitBlock() != null) return rr.getHitBlock();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public void onDisable() {
        // Ensure we cleanly stop scheduled tasks and remove plugin-spawned boats.
        try {
            if (scoreboardService != null) scoreboardService.stop();
        } catch (Throwable ignored) {}

        try {
            if (hotbarService != null) hotbarService.stop();
        } catch (Throwable ignored) {}

        try {
            if (raceService != null) raceService.stopAll(false);
        } catch (Throwable ignored) {}

        try {
            if (lobbyBoardService != null) lobbyBoardService.stop();
        } catch (Throwable ignored) {}
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
                    Text.msg(p, "&eDùng: /"+label+" scoreboard <on|off|tick|debug on|debug off>");
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
                    case "tick" -> { try { dev.belikhun.boatracing.ui.ScoreboardService svc = scoreboardService; if (svc != null) svc.forceTick(); Text.msg(p, "&aĐã cập nhật."); } catch (Throwable ignored) {} }
                    case "debug" -> {
                        if (args.length < 3) { Text.msg(p, "&eDùng: /"+label+" scoreboard debug <on|off>"); return true; }
                        boolean enable = args[2].equalsIgnoreCase("on");
                        try { scoreboardService.setDebug(enable); } catch (Throwable ignored) {}
                        Text.msg(p, enable ? "&aBật debug scoreboard." : "&aTắt debug scoreboard.");
                    }
                    default -> Text.msg(p, "&eDùng: /"+label+" scoreboard <on|off|tick>");
                }
                return true;
            }
                        if (args[0].equalsIgnoreCase("profile")) {
                            // /boatracing profile speedunit <kmh|bps>
                            if (args.length >= 3 && args[1].equalsIgnoreCase("speedunit")) {
                                String u = args[2].toLowerCase();
                                if (!u.equals("kmh") && !u.equals("bps") && !u.equals("bph")) {
                                    Text.msg(p, "&cĐơn vị không hợp lệ. Dùng: &fkmh&7, &fbps&7 hoặc &fbph");
                                    return true;
                                }
                                profileManager.setSpeedUnit(p.getUniqueId(), u);
                                String unitLabel = u.equals("kmh")?"km/h":(u.equals("bps")?"bps":"bph");
                                Text.msg(p, "&aĐã đặt đơn vị tốc độ: &f" + unitLabel);
                                return true;
                            }
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

                // Restart scoreboard so update-ticks/templates changes apply immediately.
                try {
                    if (scoreboardService != null) {
                        scoreboardService.restart();
                        boolean sbDebug = getConfig().getBoolean("scoreboard.debug", false);
                        scoreboardService.setDebug(sbDebug);
                    }
                } catch (Throwable ignored) {}

                // Reload MapEngine lobby board config
                try {
                    if (lobbyBoardService != null) lobbyBoardService.reloadFromConfig();
                } catch (Throwable ignored) {}
                // Team features removed; nothing to re-create
                // ViaVersion integration removed; nothing to re-apply
                Text.msg(p, "&aĐã tải lại plugin.");
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return true;
            }

            // /boatracing board (MapEngine lobby board)
            if (args[0].equalsIgnoreCase("board")) {
                if (!p.hasPermission("boatracing.admin")) {
                    Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
                    return true;
                }
                if (lobbyBoardService == null) {
                    Text.msg(p, "&cTính năng bảng đang bị tắt.");
                    return true;
                }

                if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
                    Text.msg(p, "&eBảng thông tin sảnh (MapEngine):");
                    Text.tell(p, "&7 - &f/" + label + " board set [north|south|east|west] &7(Dùng selection hiện tại; bỏ trống để tự chọn theo hướng nhìn)");
                    Text.tell(p, "&7 - &f/" + label + " board clear");
                    Text.tell(p, "&7 - &f/" + label + " board status");
                    return true;
                }

                String sub = args[1].toLowerCase();
                switch (sub) {
                    case "status" -> {
                        java.util.List<String> lines;
                        try { lines = lobbyBoardService.statusLines(); }
                        catch (Throwable t) {
                            lines = java.util.List.of("&cKhông thể lấy trạng thái bảng: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                        }
                        for (String line : lines) {
                            try { Text.msg(p, line); } catch (Throwable ignored) {}
                        }
                        return true;
                    }
                    case "clear" -> {
                        lobbyBoardService.clearPlacement();
                        Text.msg(p, "&aĐã xóa vị trí bảng.");
                        return true;
                    }
                    case "set" -> {
                        // Facing is optional; if omitted, auto-select based on player view/position.
                        var sel = dev.belikhun.boatracing.track.SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            Text.msg(p, "&cKhông phát hiện selection. Dùng wand để chọn 2 góc trước.");
                            return true;
                        }
                        org.bukkit.block.BlockFace face = null;
                        if (args.length >= 3) {
                            try {
                                face = org.bukkit.block.BlockFace.valueOf(args[2].toUpperCase(java.util.Locale.ROOT));
                            } catch (Throwable t) {
                                Text.msg(p, "&cHướng không hợp lệ. Dùng: north|south|east|west");
                                return true;
                            }
                        }

                        boolean ok = lobbyBoardService.setPlacementFromSelection(p, sel.box, face);
                        if (!ok) {
                            Text.msg(p, "&cKhông thể đặt bảng. Hãy chọn vùng phẳng (2D) phù hợp và thử lại.");
                            return true;
                        }
                        Text.msg(p, "&aĐã đặt bảng thông tin sảnh.");
                        Text.tell(p, lobbyBoardService.placementSummary());
                        return true;
                    }
                    default -> {
                        Text.msg(p, "&cKhông rõ lệnh. Dùng: /" + label + " board help");
                        return true;
                    }
                }
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
                        RaceManager rm = raceService.getOrCreate(tname);
                        if (rm == null) { Text.msg(p, "&cTrack not found or failed to load: &f" + tname); return true; }
                        if (!rm.getTrackConfig().isReady()) {
                            Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", rm.getTrackConfig().missingRequirements()));
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        int laps = rm.getTotalLaps();
                        boolean ok = rm.openRegistration(laps, null);
                        if (!ok) Text.msg(p, "&cKhông thể mở đăng ký lúc này.");
                        return true;
                    }
                    case "join" -> {
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race join <track>"); return true; }
                        String tname = args[2];
                        RaceManager rm = raceService.getOrCreate(tname);
                        if (rm == null) { Text.msg(p, "&cTrack not found or failed to load: &f" + tname); return true; }
                        if (!rm.getTrackConfig().isReady()) {
                            Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", rm.getTrackConfig().missingRequirements()));
                            return true;
                        }
                        // Tracks are open by default: joining auto-opens registration if needed.
                        if (!raceService.join(tname, p)) {
                            Text.msg(p, "&cKhông thể tham gia đăng ký lúc này.");
                        }
                        return true;
                    }
                    case "leave" -> {
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race leave <track>"); return true; }
                        String tname = args[2];
                        RaceManager rm = raceService.getOrCreate(tname);
                        if (rm == null) { Text.msg(p, "&cTrack not found or failed to load: &f" + tname); return true; }
                        boolean removed = raceService.leave(tname, p);
                        if (!removed) {
                            if (!rm.isRegistering()) {
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
                        RaceManager rm = raceService.getOrCreate(tname);
                        if (rm == null) { Text.msg(p, "&cTrack not found or failed to load: &f" + tname); return true; }
                        if (rm.getRegistered().isEmpty()) {
                            Text.msg(p, "&cKhông có người tham gia đã đăng ký. &7Mở đăng ký trước: &f/" + label + " race open");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        rm.forceStart();
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
                        RaceManager raceManager = raceService.getOrCreate(tname);
                        if (raceManager == null) { Text.msg(p, "&cTrack not found or failed to load: &f" + tname); return true; }
                        if (!raceManager.getTrackConfig().isReady()) {
                            Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", raceManager.getTrackConfig().missingRequirements()));
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
                        raceManager.startLightsCountdown(placed);
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
                        boolean any = raceService.stopRace(tname, true);
                        if (!any) {
                            Text.msg(p, "&7Không có gì để dừng.");
                        }
                        return true;
                    }
                    case "status" -> {
                        if (args.length < 3) { Text.msg(p, "&cCách dùng: /" + label + " race status <track>"); return true; }
                        String tname = args[2];
                        RaceManager raceManager = raceService.getOrCreate(tname);
                        if (raceManager == null) { Text.msg(p, "&cTrack not found or failed to load: &f" + tname); return true; }
                        var tc = raceManager.getTrackConfig();
                        String cur = tc.getCurrentName() != null ? tc.getCurrentName() : tname;
                        boolean running = raceManager.isRunning();
                        boolean registering = raceManager.isRegistering();
                        int regs = raceManager.getRegistered().size();
                        int laps = raceManager.getTotalLaps();
                        int participants = running ? raceManager.getInvolved().size() : 0;
                        int starts = tc.getStarts().size();
                        int lights = tc.getLights().size();
                        int cps = tc.getCheckpoints().size();
                        boolean hasFinish = tc.getFinish() != null;
                        boolean ready = tc.isReady();
                        java.util.List<String> missing = ready ? java.util.Collections.emptyList() : tc.missingRequirements();

                        Text.msg(p, "&eTrạng thái cuộc đua:");
                        Text.tell(p, "&7Đường đua: &f" + cur);
                        Text.tell(p, running ? "&aĐang chạy &7(Tham gia: &f" + participants + "&7)" : "&7Không có cuộc đua đang chạy.");
                        Text.tell(p, registering ? "&eĐăng ký mở &7(Đã đăng ký: &f" + regs + "&7)" : "&7Đăng ký đóng.");
                        Text.tell(p, "&7Số vòng: &f" + laps);
                        Text.tell(p, "&7Vị trí bắt đầu: &f" + starts + " &8● &7Đèn xuất phát: &f" + lights + "/5 &8● &7Vạch kết thúc: &f" + (hasFinish?"có":"không"));
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
                    Text.tell(p, "&7 - &f/" + label + " setup pos1 &7(Đặt góc A = vị trí hiện tại)");
                    Text.tell(p, "&7 - &f/" + label + " setup pos2 &7(Đặt góc B = vị trí hiện tại)");
                    Text.tell(p, "&7 - &f/" + label + " setup setbounds &7(Đặt vùng bao đường đua từ selection hiện tại)");
                    Text.tell(p, "&7 - &f/" + label + " setup setwaitspawn &7(Đặt điểm spawn chờ từ vị trí hiện tại)");
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
                    case "pos1" -> {
                        dev.belikhun.boatracing.track.SelectionManager.setCornerA(p, p.getLocation());
                        Text.msg(p, "&aĐã đặt &fGóc A &a= &f" + Text.fmtPos(p.getLocation()));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.3f);
                        return true;
                    }
                    case "pos2" -> {
                        dev.belikhun.boatracing.track.SelectionManager.setCornerB(p, p.getLocation());
                        Text.msg(p, "&aĐã đặt &fGóc B &a= &f" + Text.fmtPos(p.getLocation()));
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.3f);
                        return true;
                    }
                    case "setbounds" -> {
                        dev.belikhun.boatracing.track.SelectionUtils.SelectionDetails sel = dev.belikhun.boatracing.track.SelectionUtils.getSelectionDetailed(p);
                        if (sel == null) {
                            Text.msg(p, "&cKhông có selection hợp lệ. Dùng &f/" + label + " setup pos1 &7và &f" + label + " setup pos2 &7hoặc dùng wand.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        dev.belikhun.boatracing.track.Region r = new dev.belikhun.boatracing.track.Region(sel.worldName, sel.box);
                        trackConfig.setBounds(r);
                        Text.msg(p, "&aĐã đặt vùng bao cho đường đua.");
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
                    }
                    case "setwaitspawn" -> {
                        org.bukkit.Location raw = p.getLocation();
                        org.bukkit.Location loc = dev.belikhun.boatracing.track.TrackConfig.normalizeStart(raw);
                        trackConfig.setWaitingSpawn(loc);
                        Text.msg(p, "&aĐã đặt spawn chờ tại &f" + Text.fmtPos(loc) + " &7yaw=" + Math.round(loc.getYaw()) + ", pitch=0");
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                        return true;
                    }
                    case "wand" -> {
                        dev.belikhun.boatracing.track.SelectionManager.giveWand(p);
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
                        org.bukkit.Location raw = p.getLocation();
                        org.bukkit.Location loc = dev.belikhun.boatracing.track.TrackConfig.normalizeStart(raw);
                        trackConfig.addStart(loc);
                        Text.msg(p, "&aĐã thêm vị trí bắt đầu tại &f" + Text.fmtPos(loc) + " &7yaw=" + Math.round(loc.getYaw()) + ", pitch=0");
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
                        Text.msg(p, "&aĐã đặt vùng đích (&f" + Text.fmtArea(r) + "&a)");
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
                        Text.msg(p, "&aĐã thêm checkpoint #&f" + trackConfig.getCheckpoints().size() + " &7(" + Text.fmtArea(r) + ")");
                        Text.tell(p, "&7Mẹo: Có thể thêm nhiều checkpoint. Thứ tự quan trọng.");
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        if (setupWizard != null) setupWizard.afterAction(p);
                    }
                    case "addlight" -> {
                        org.bukkit.block.Block target = getTargetBlockLenient(p, 20);
                        if (target == null) {
                            Text.msg(p, "&cHãy nhìn vào Đèn Redstone trong bán kính 20 block.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        if (target.getType() != org.bukkit.Material.REDSTONE_LAMP) {
                            Text.msg(p, "&cBlock đang nhìn không phải Đèn Redstone: &f" + target.getType());
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        boolean ok = trackConfig.addLight(target);
                        if (!ok) {
                            Text.msg(p, "&cKhông thể thêm đèn. Dùng Đèn Redstone, tránh trùng lặp, tối đa 5.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                            return true;
                        }
                        Text.msg(p, "&aĐã thêm đèn xuất phát &7(" + Text.fmtBlock(target) + ")");
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
                        if (raceService != null) raceService.setDefaultLaps(laps);
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
                root.add("scoreboard");
                if (sender.hasPermission("boatracing.setup")) root.add("setup");
                if (sender.hasPermission("boatracing.admin")) root.add("admin");
                if (sender.hasPermission("boatracing.admin")) root.add("board");
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
                root.add("scoreboard");
                if (sender.hasPermission("boatracing.setup")) root.add("setup");
                if (sender.hasPermission("boatracing.admin")) root.add("admin");
                if (sender.hasPermission("boatracing.admin")) root.add("board");
                if (sender.hasPermission("boatracing.reload")) root.add("reload");
                if (sender.hasPermission("boatracing.version")) root.add("version");
                return root.stream().filter(s -> s.startsWith(pref)).toList();
            }

            if (args.length >= 2 && args[0].equalsIgnoreCase("board")) {
                if (!sender.hasPermission("boatracing.admin")) return java.util.Collections.emptyList();
                if (args.length == 2) return java.util.Arrays.asList("help", "set", "status", "clear");
                if (args.length == 3 && args[1].equalsIgnoreCase("set")) return java.util.Arrays.asList("north", "south", "east", "west");
                return java.util.Collections.emptyList();
            }
            if (args.length >= 2 && (args[0].equalsIgnoreCase("scoreboard") || args[0].equalsIgnoreCase("sb"))) {
                if (!sender.hasPermission("boatracing.admin")) return java.util.Collections.emptyList();
                if (args.length == 2) return java.util.Arrays.asList("on","off","tick","debug");
                if (args.length == 3 && args[1].equalsIgnoreCase("debug")) return java.util.Arrays.asList("on","off");
                return java.util.Collections.emptyList();
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
                if (args.length == 2) return List.of("help","addstart","clearstarts","pos1","pos2","setbounds","setwaitspawn","setfinish","addcheckpoint","clearcheckpoints","addlight","clearlights","setpos","clearpos","show","selinfo","wand","wizard");
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
}

