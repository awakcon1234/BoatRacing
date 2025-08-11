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

public class BoatRacingPlugin extends JavaPlugin {
    private static BoatRacingPlugin instance;
    private TeamManager teamManager;
    private TeamGUI teamGUI;
    private String prefix;
    private UpdateChecker updateChecker;
    // Track pending disband confirmations per player
    private final java.util.Set<java.util.UUID> pendingDisband = new java.util.HashSet<>();
    // Track pending leadership transfer per leader -> target
    private final java.util.Map<java.util.UUID, java.util.UUID> pendingTransfer = new java.util.HashMap<>();
    // Track pending kick per leader -> target
    private final java.util.Map<java.util.UUID, java.util.UUID> pendingKick = new java.util.HashMap<>();

    public static BoatRacingPlugin getInstance() { return instance; }
    public TeamManager getTeamManager() { return teamManager; }
    public String pref() { return prefix; }
    // No chat input handler anymore; all managed via GUI/Anvil

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
        this.teamManager = new TeamManager(this);
        this.teamGUI = new TeamGUI(this);
    Bukkit.getPluginManager().registerEvents(teamGUI, this);
    // bStats metrics (enabled by default). To disable, set enabled: false under bstats in config.yml
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
                p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " teams|reload|version"));
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
                // Links (repo/wiki/discord-style info)
                p.sendMessage(Text.colorize(prefix + "&eWiki: &fhttps://github.com/Jaie55/BoatRacing#readme"));
                p.sendMessage(Text.colorize(prefix + "&eDiscord: &fN/A"));

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
            if (!args[0].equalsIgnoreCase("teams")) {
                p.sendMessage(Text.colorize(prefix + "&cUsage: /" + label + " teams|reload|version"));
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
                if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can rename.")); return true; }
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
                if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can change color.")); return true; }
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
                if (t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cLeaders cannot leave here. Transfer leadership first.")); return true; }
                if (t.getMembers().size() <= 1) { p.sendMessage(Text.colorize(prefix + "&cYou can't leave if the team would be empty.")); return true; }
                t.removeMember(p.getUniqueId()); teamManager.save();
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
                es.jaie55.boatracing.team.Team t = ot.get();
                if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can kick members.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams kick <playerName>")); return true; }
                // Avoid overlapping confirmations
                if (pendingDisband.contains(p.getUniqueId()) || pendingTransfer.containsKey(p.getUniqueId())) {
                    p.sendMessage(Text.colorize(prefix + "&cFinish or cancel your current confirmation first."));
                    return true;
                }
                org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                if (target == null || target.getUniqueId() == null || !t.isMember(target.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cThat player is not in your team.")); return true; }
                if (t.isLeader(target.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cYou cannot kick the leader.")); return true; }
                pendingKick.put(p.getUniqueId(), target.getUniqueId());
                p.sendMessage(Text.colorize(prefix + "&eThis will remove &6" + (target.getName() != null ? target.getName() : args[2]) + " &efrom your team."));
                p.sendMessage(Text.colorize(prefix + "&eType &6/boatracing teams confirm &eto proceed, or &6/boatracing teams cancel &eto abort."));
                return true;
            }
            // /boatracing teams transfer <playerName>
            if (args.length >= 2 && args[1].equalsIgnoreCase("transfer")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                es.jaie55.boatracing.team.Team t = ot.get();
                if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can transfer the leadership.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams transfer <playerName>")); return true; }
                org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                if (target == null || target.getUniqueId() == null || !t.isMember(target.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cThat player is not in your team.")); return true; }
                if (target.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cYou are already the leader.")); return true; }
                // Prevent overlapping confirmations
                if (pendingDisband.contains(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cFinish or cancel your disband confirmation first.")); return true; }
                pendingTransfer.put(p.getUniqueId(), target.getUniqueId());
                p.sendMessage(Text.colorize(prefix + "&eThis will transfer the leadership to &6" + (target.getName() != null ? target.getName() : args[2]) + "&e."));
                p.sendMessage(Text.colorize(prefix + "&eType &6/boatracing teams confirm &eto proceed, or &6/boatracing teams cancel &eto abort."));
                return true;
            }
            // /boatracing teams boat <type>
            if (args.length >= 2 && args[1].equalsIgnoreCase("boat")) {
                java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                if (args.length < 3) { p.sendMessage(Text.colorize(prefix + "&cUsage: /boatracing teams boat <type>")); return true; }
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
                es.jaie55.boatracing.team.Team t = ot.get();
                if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can disband.")); return true; }
                pendingDisband.add(p.getUniqueId());
                p.sendMessage(Text.colorize(prefix + "&eThis will delete your team and kick all members."));
                p.sendMessage(Text.colorize(prefix + "&eType &6/boatracing teams confirm &eto disband."));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
                // Confirm disband first if pending
                if (pendingDisband.contains(p.getUniqueId())) {
                    java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                    if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                    es.jaie55.boatracing.team.Team t = ot.get();
                    if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can disband.")); return true; }
                    java.util.Set<java.util.UUID> members = new java.util.HashSet<>(t.getMembers());
                    members.remove(p.getUniqueId());
                    for (java.util.UUID m : members) {
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                        if (op.isOnline() && op.getPlayer() != null) op.getPlayer().sendMessage(Text.colorize(prefix + "&eYour team was disbanded by the leader."));
                    }
                    teamManager.deleteTeam(t);
                        p.sendMessage(Text.colorize(prefix + "&aTeam disbanded."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
                    pendingDisband.remove(p.getUniqueId());
                    return true;
                }
                // Confirm transfer if pending
                if (pendingTransfer.containsKey(p.getUniqueId())) {
                    java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                    if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                    es.jaie55.boatracing.team.Team t = ot.get();
                    if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can transfer.")); return true; }
                    java.util.UUID targetId = pendingTransfer.remove(p.getUniqueId());
                    if (targetId == null || !t.isMember(targetId)) { p.sendMessage(Text.colorize(prefix + "&cTarget is no longer in your team.")); return true; }
                    if (p.getUniqueId().equals(targetId)) { p.sendMessage(Text.colorize(prefix + "&cYou are already the leader.")); return true; }
                    java.util.UUID oldLeader = t.getLeader();
                    t.setLeader(targetId);
                    teamManager.save();
                    org.bukkit.OfflinePlayer np = Bukkit.getOfflinePlayer(targetId);
                    String newName = np.getName() != null ? np.getName() : targetId.toString().substring(0, 8);
                    p.sendMessage(Text.colorize(prefix + "&aYou transferred the leadership to &e" + newName + "&a."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                    if (np.isOnline() && np.getPlayer() != null) {
                        np.getPlayer().sendMessage(Text.colorize(prefix + "&aYou are now the team leader."));
                        np.getPlayer().playSound(np.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                    }
                    for (java.util.UUID m : t.getMembers()) {
                        if (m.equals(oldLeader) || m.equals(targetId)) continue;
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                        if (op.isOnline() && op.getPlayer() != null) {
                            String oldName = Bukkit.getOfflinePlayer(oldLeader).getName();
                            op.getPlayer().sendMessage(Text.colorize(prefix + "&e" + (oldName != null ? oldName : oldLeader.toString().substring(0, 8)) + " transferred the leadership to " + newName + "."));
                        }
                    }
                    return true;
                }
                // Confirm kick if pending
                if (pendingKick.containsKey(p.getUniqueId())) {
                    java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                    if (ot.isEmpty()) { p.sendMessage(Text.colorize(prefix + "&cYou are not in a team.")); return true; }
                    es.jaie55.boatracing.team.Team t = ot.get();
                    if (!t.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(prefix + "&cOnly the team leader can kick members.")); return true; }
                    java.util.UUID targetId = pendingKick.remove(p.getUniqueId());
                    if (targetId == null || !t.isMember(targetId)) { p.sendMessage(Text.colorize(prefix + "&cTarget is no longer in your team.")); return true; }
                    if (t.isLeader(targetId)) { p.sendMessage(Text.colorize(prefix + "&cYou cannot kick the leader.")); return true; }
                    boolean removed = t.removeMember(targetId);
                    if (!removed) { p.sendMessage(Text.colorize(prefix + "&cCould not kick that player.")); return true; }
                    teamManager.save();
                    org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
                    if (target.isOnline() && target.getPlayer() != null) {
                        target.getPlayer().sendMessage(Text.colorize(prefix + "&cYou have been kicked from " + t.getName() + " by the leader."));
                    }
                    for (java.util.UUID m : t.getMembers()) {
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                        if (op.isOnline() && op.getPlayer() != null) {
                            op.getPlayer().sendMessage(Text.colorize(prefix + "&e" + (target.getName() != null ? target.getName() : targetId.toString().substring(0,8)) + " was kicked from the team."));
                        }
                    }
                    p.sendMessage(Text.colorize(prefix + "&aYou kicked &e" + (target.getName() != null ? target.getName() : targetId.toString().substring(0,8)) + "&a."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.2f);
                    return true;
                }
                p.sendMessage(Text.colorize(prefix + "&cNothing to confirm. Use &6/boatracing teams disband&c, &6/boatracing teams transfer <player>&c, or &6/boatracing teams kick <player> &cfirst."));
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
            p.sendMessage(Text.colorize(prefix + "&cUnknown subcommand. Use: /boatracing version|reload or /boatracing teams [create|rename|color|join|leave|kick|boat|number|transfer|disband|confirm|cancel]"));
            return true;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("boatracing")) {
            if (args.length == 1) {
                java.util.List<String> root = new java.util.ArrayList<>();
                if (sender.hasPermission("boatracing.teams")) root.add("teams");
                if (sender.hasPermission("boatracing.reload")) root.add("reload");
                if (sender.hasPermission("boatracing.version")) root.add("version");
                return root;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("teams")) {
                if (!sender.hasPermission("boatracing.teams")) return Collections.emptyList();
                if (args.length == 2) return Arrays.asList("create", "rename", "color", "join", "leave", "kick", "boat", "number", "transfer", "disband", "confirm", "cancel");
                if (args.length >= 3 && args[1].equalsIgnoreCase("create")) return Collections.emptyList();
                if (args.length >= 3 && args[1].equalsIgnoreCase("color")) return java.util.Arrays.stream(org.bukkit.DyeColor.values()).map(Enum::name).map(String::toLowerCase).toList();
                if (args.length >= 3 && args[1].equalsIgnoreCase("boat")) return Arrays.asList(
                    "oak_boat","oak_chest_boat","spruce_boat","spruce_chest_boat","birch_boat","birch_chest_boat",
                    "jungle_boat","jungle_chest_boat","acacia_boat","acacia_chest_boat","dark_oak_boat","dark_oak_chest_boat",
                    "mangrove_boat","mangrove_chest_boat","cherry_boat","cherry_chest_boat","pale_oak_boat","pale_oak_chest_boat"
                );
                // Suggest team members for kick/transfer
                if (args.length >= 3 && (args[1].equalsIgnoreCase("kick") || args[1].equalsIgnoreCase("transfer"))) {
                    if (!(sender instanceof Player)) return Collections.emptyList();
                    Player p = (Player) sender;
                    java.util.Optional<es.jaie55.boatracing.team.Team> ot = teamManager.getTeamByMember(p.getUniqueId());
                    if (ot.isEmpty()) return Collections.emptyList();
                    es.jaie55.boatracing.team.Team t = ot.get();
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (java.util.UUID m : t.getMembers()) {
                        if (m.equals(p.getUniqueId())) continue; // exclude self
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                        String name = op.getName() != null ? op.getName() : m.toString().substring(0, 8);
                        names.add(name);
                    }
                    String prefix = args[2].toLowerCase();
                    return names.stream().filter(n -> n.toLowerCase().startsWith(prefix)).toList();
                }
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
}
