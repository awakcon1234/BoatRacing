package es.jaie55.boatracing.team;

import es.jaie55.boatracing.util.Text;
import org.bukkit.DyeColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import es.jaie55.boatracing.BoatRacingPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class TeamManager {
    private final BoatRacingPlugin plugin;
    private final Map<UUID, Team> teams = new LinkedHashMap<>();
    private int maxMembers;

    // Separate data files
    private File teamsFile;
    private File racersFile;
    private YamlConfiguration teamsCfg;
    private YamlConfiguration racersCfg;

    public TeamManager(BoatRacingPlugin plugin) {
        this.plugin = plugin;
        ensureDataFiles();
        load();
        this.maxMembers = Math.max(1, plugin.getConfig().getInt("max-members-per-team", 2));
    }

    public Collection<Team> getTeams() { return teams.values(); }
    public java.util.List<Team> getTeamsSnapshot() { return new java.util.ArrayList<>(teams.values()); }
    public Optional<Team> findByName(String name) {
        if (name == null) return Optional.empty();
        return teams.values().stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst();
    }

    public Optional<Team> getTeamByMember(UUID player) {
        return teams.values().stream().filter(t -> t.isMember(player)).findFirst();
    }

    public int getMaxMembers() { return maxMembers; }

    public boolean isNumberTaken(int number) {
        // Team numbers removed; now numbers are per-member. This method is no longer used.
        return false;
    }

    public Team createTeam(Player leader, String name, DyeColor color) {
        UUID id = UUID.randomUUID();
    Team team = new Team(id, name, color, leader.getUniqueId());
        teams.put(id, team);
        save();
        leader.sendMessage(Text.colorize(plugin.pref() + "&aCreated team '" + name + "'"));
        return team;
    }

    // Admin variant: create a team with an optional initial member; no chat message.
    public Team createTeam(UUID firstMember, String name, DyeColor color) {
        UUID id = UUID.randomUUID();
        Team team = new Team(id, name, color, firstMember);
        teams.put(id, team);
        save();
        return team;
    }

    public boolean removeTeam(Team team) {
        if (team == null) return false;
        teams.remove(team.getId());
        save();
        return true;
    }

    public boolean addMember(Team team, UUID playerId) {
        if (team == null || playerId == null) return false;
        boolean ok = team.addMember(playerId);
        if (ok) save();
        return ok;
    }

    public boolean removeMember(Team team, UUID playerId) {
        if (team == null || playerId == null) return false;
        boolean ok = team.removeMember(playerId);
        if (ok) save();
        return ok;
    }

    public void deleteTeam(Team team) {
        teams.remove(team.getId());
        save();
    }

    public void save() {
        // Write teams
        teamsCfg.set("teams", null);
    for (Team t : teams.values()) {
            String path = "teams." + t.getId();
            teamsCfg.set(path + ".name", t.getName());
            teamsCfg.set(path + ".color", t.getColor().name());
            java.util.List<String> mems = t.getMembers().stream().map(UUID::toString).collect(Collectors.toList());
            teamsCfg.set(path + ".members", mems);
        }
    // Write racers: per-player number and boat type (do NOT clear the whole section to avoid accidental data loss)
        for (Team t : teams.values()) {
            for (UUID m : t.getMembers()) {
                String base = "racers." + m;
                int num = t.getRacerNumber(m);
                if (num > 0) racersCfg.set(base + ".number", num);
                String boat = t.getBoatType(m);
                if (boat != null) racersCfg.set(base + ".boat", boat);
            }
        }
        saveYaml(teamsCfg, teamsFile);
        saveYaml(racersCfg, racersFile);
    }

    public void load() {
        teams.clear();
        teamsCfg = YamlConfiguration.loadConfiguration(teamsFile);
        racersCfg = YamlConfiguration.loadConfiguration(racersFile);

    ConfigurationSection sec = teamsCfg.getConfigurationSection("teams");
        if (sec == null || sec.getKeys(false).isEmpty()) {
            // Backward-compat: load from old config.yml if present
            FileConfiguration cfg = plugin.getConfig();
            ConfigurationSection old = cfg.getConfigurationSection("teams");
            if (old != null) {
                for (String key : old.getKeys(false)) {
                    String path = "teams." + key;
                    String name = cfg.getString(path + ".name", "Team");
                    DyeColor color = DyeColor.valueOf(cfg.getString(path + ".color", DyeColor.WHITE.name()));
                    UUID id = UUID.fromString(key);
            // Old format had a leader; add them as initial member if present
            UUID leader = null;
            try { String l = cfg.getString(path + ".leader"); if (l != null) leader = UUID.fromString(l); } catch (Exception ignored) {}
            Team t = new Team(id, name, color, leader);
                    java.util.List<String> mems = cfg.getStringList(path + ".members");
                    for (String s : mems) {
                        try { t.addMemberUnchecked(UUID.fromString(s)); } catch (Exception ignored) {}
                    }
                    // Load per-member preferences from old structure
                    ConfigurationSection rs = cfg.getConfigurationSection(path + ".racerNumbers");
                    if (rs != null) {
                        for (String k : rs.getKeys(false)) {
                            try { t.setRacerNumber(UUID.fromString(k), rs.getInt(k)); } catch (Exception ignored) {}
                        }
                    }
                    ConfigurationSection bs = cfg.getConfigurationSection(path + ".boatTypes");
                    if (bs != null) {
                        for (String k : bs.getKeys(false)) {
                            try { t.setBoatType(UUID.fromString(k), bs.getString(k)); } catch (Exception ignored) {}
                        }
                    }
                    teams.put(id, t);
                }
                // Persist in new files
                save();
                return;
            }
            return;
        }
        for (String key : sec.getKeys(false)) {
            String path = "teams." + key;
            String name = teamsCfg.getString(path + ".name", "Team");
            DyeColor color = DyeColor.valueOf(teamsCfg.getString(path + ".color", DyeColor.WHITE.name()));
            UUID id = UUID.fromString(key);
            // New format: no leader; just members list
            Team t = new Team(id, name, color, null);
            java.util.List<String> mems = teamsCfg.getStringList(path + ".members");
                for (String s : mems) {
                    try { t.addMemberUnchecked(UUID.fromString(s)); } catch (Exception ignored) {}
                }
            teams.put(id, t);
        }
        // Load racers from racers.yml
        ConfigurationSection rs = racersCfg.getConfigurationSection("racers");
        boolean hadRacers = false;
        if (rs != null) {
            for (String pid : rs.getKeys(false)) {
                UUID uid;
                try { uid = UUID.fromString(pid); } catch (Exception ex) { continue; }
                int num = rs.getInt(pid + ".number", 0);
                String boat = rs.getString(pid + ".boat", null);
                // Apply to the team the player belongs to, if any
                Optional<Team> ot = getTeamByMember(uid);
                if (ot.isPresent()) {
                    if (num > 0) ot.get().setRacerNumber(uid, num);
                    if (boat != null && !boat.isEmpty()) ot.get().setBoatType(uid, boat);
                }
                hadRacers = true;
            }
        }

        // Migration: if racers.yml has no data but old config.yml contains per-player settings, import them
        if (!hadRacers) {
            FileConfiguration legacyCfg = plugin.getConfig();
            ConfigurationSection legacyTeams = legacyCfg.getConfigurationSection("teams");
            boolean migrated = false;
            if (legacyTeams != null) {
                for (Team t : teams.values()) {
                    String tid = t.getId().toString();
                    String base = "teams." + tid;
                    ConfigurationSection legacyNums = legacyCfg.getConfigurationSection(base + ".racerNumbers");
                    if (legacyNums != null) {
                        for (String k : legacyNums.getKeys(false)) {
                            try {
                                UUID uid = UUID.fromString(k);
                                int num = legacyNums.getInt(k, 0);
                                if (num > 0 && t.isMember(uid)) { t.setRacerNumber(uid, num); migrated = true; }
                            } catch (Exception ignored) {}
                        }
                    }
                    ConfigurationSection legacyBoats = legacyCfg.getConfigurationSection(base + ".boatTypes");
                    if (legacyBoats != null) {
                        for (String k : legacyBoats.getKeys(false)) {
                            try {
                                UUID uid = UUID.fromString(k);
                                String bt = legacyBoats.getString(k, null);
                                if (bt != null && !bt.isEmpty() && t.isMember(uid)) { t.setBoatType(uid, bt); migrated = true; }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (migrated) {
                // Persist migrated data into racers.yml
                save();
                try { plugin.getLogger().info("Migrated racer numbers and boat types from old config.yml into racers.yml"); } catch (Throwable ignored) {}
            }
        }
    }

    private void ensureDataFiles() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        teamsFile = new File(plugin.getDataFolder(), "teams.yml");
        racersFile = new File(plugin.getDataFolder(), "racers.yml");
        if (!teamsFile.exists()) {
            try { teamsFile.createNewFile(); } catch (IOException ignored) {}
        }
        if (!racersFile.exists()) {
            try { racersFile.createNewFile(); } catch (IOException ignored) {}
        }
        teamsCfg = YamlConfiguration.loadConfiguration(teamsFile);
        racersCfg = YamlConfiguration.loadConfiguration(racersFile);
    }

    private void saveYaml(YamlConfiguration cfg, File file) {
        try { cfg.save(file); } catch (IOException e) { plugin.getLogger().warning("Failed to save " + file.getName() + ": " + e.getMessage()); }
    }
}
