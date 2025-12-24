package es.jaie55.boatracing.profile;

import org.bukkit.DyeColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerProfileManager {
    private final File file;
    private final Map<UUID, Profile> profiles = new HashMap<>();

    public static class Profile {
        public DyeColor color = DyeColor.WHITE;
        public int number = 0; // 0 means unset
        public String icon = ""; // unicode icon (optional)
        public int completed = 0;
        public int wins = 0;
        public String boatType = ""; // Material name of the chosen boat/raft item
    }

    public PlayerProfileManager(File dataFolder) {
        this.file = new File(dataFolder, "profiles.yml");
        load();
    }

    public Profile get(UUID id) {
        return profiles.computeIfAbsent(id, k -> new Profile());
    }

    public DyeColor getColor(UUID id) { return get(id).color; }
    public void setColor(UUID id, DyeColor color) { get(id).color = color == null ? DyeColor.WHITE : color; save(); }

    public int getNumber(UUID id) { return get(id).number; }
    public void setNumber(UUID id, int number) { get(id).number = Math.max(0, Math.min(99, number)); save(); }

    public String getIcon(UUID id) { return get(id).icon; }
    public void setIcon(UUID id, String icon) { get(id).icon = icon == null ? "" : icon; save(); }

    public int getCompleted(UUID id) { return get(id).completed; }
    public int getWins(UUID id) { return get(id).wins; }
    public void incCompleted(UUID id) { get(id).completed++; save(); }
    public void incWins(UUID id) { get(id).wins++; save(); }

    public String getBoatType(UUID id) { return get(id).boatType; }
    public void setBoatType(UUID id, String type) { get(id).boatType = type == null ? "" : type; save(); }

    public void load() {
        profiles.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        var sec = cfg.getConfigurationSection("profiles");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Profile p = new Profile();
                String colorName = sec.getString(key + ".color", DyeColor.WHITE.name());
                try { p.color = DyeColor.valueOf(colorName); } catch (IllegalArgumentException ignored) { p.color = DyeColor.WHITE; }
                p.number = sec.getInt(key + ".number", 0);
                p.icon = sec.getString(key + ".icon", "");
                p.completed = sec.getInt(key + ".completed", 0);
                p.wins = sec.getInt(key + ".wins", 0);
                p.boatType = sec.getString(key + ".boatType", "");
                profiles.put(id, p);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Profile> e : profiles.entrySet()) {
            String base = "profiles." + e.getKey().toString();
            Profile p = e.getValue();
            cfg.set(base + ".color", p.color.name());
            cfg.set(base + ".number", p.number);
            cfg.set(base + ".icon", p.icon);
            cfg.set(base + ".completed", p.completed);
            cfg.set(base + ".wins", p.wins);
            cfg.set(base + ".boatType", p.boatType);
        }
        try { cfg.save(file); } catch (IOException ignored) {}
    }
}
