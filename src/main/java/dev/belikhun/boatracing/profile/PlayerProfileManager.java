package dev.belikhun.boatracing.profile;

import org.bukkit.DyeColor;
import org.bukkit.configuration.file.YamlConfiguration;
import dev.belikhun.boatracing.util.ColorTranslator;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class PlayerProfileManager {
    private final File file;
    private final Map<UUID, Profile> profiles = new HashMap<>();

    // Keep defaults aligned with ProfileGUI pickers.
    private static final DyeColor[] DEFAULT_COLORS = new DyeColor[] {
        DyeColor.WHITE, DyeColor.BLACK, DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN,
        DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.PURPLE, DyeColor.PINK, DyeColor.LIGHT_BLUE
    };

    private static final String[] DEFAULT_ICONS = new String[] {
        "★","☆","✦","✧","❖","◆","◇","❤","✚","⚡","☀","☂","☕","⚓","♪","♫","🚤","⛵"
    };

    public static class Profile {
        public DyeColor color = DyeColor.WHITE;
        public int number = 0; // 0 means unset
        public String icon = ""; // unicode icon (optional)
        public int completed = 0;
        public int wins = 0;
        /** Total time raced across all finished races (milliseconds). */
        public long timeRacedMillis = 0L;
        /**
         * Per-track personal best time (milliseconds), keyed by track name.
         *
         * Persisted in profiles.yml under: profiles.<uuid>.personalBests.<track>
         */
        public Map<String, Long> personalBests = new HashMap<>();
        public String boatType = ""; // Material name of the chosen boat/raft item
        public String speedUnit = ""; // "kmh" | "bps"; empty = inherit global
    }

    /**
     * Standard racer display used everywhere a racer is printed:
     * <color><icon> <number> <name>
     *
     * This method returns a MiniMessage-formatted string (e.g. "<red>⚡ 12 Belikhun").
     */
    public String formatRacerMini(UUID id, String name) {
        if (id == null) return fallbackRacerMini(name);
        Profile p = get(id);
        String n = (name == null || name.isBlank()) ? shortId(id) : name;
        String icon = (p.icon == null || p.icon.isBlank()) ? "●" : p.icon;
        String number = (p.number > 0) ? String.valueOf(p.number) : "-";
        return ColorTranslator.miniColorTag(p.color) + "[" + icon + " <u>" + number + "</u>] " + n;
    }

    /**
     * Standard racer display for legacy chat (uses &-color codes), same shape:
     * <color><icon> <number> <name>
     */
    public String formatRacerLegacy(UUID id, String name) {
        if (id == null) return fallbackRacerLegacy(name);
        Profile p = get(id);
        String n = (name == null || name.isBlank()) ? shortId(id) : name;
        String icon = (p.icon == null || p.icon.isBlank()) ? "●" : p.icon;
        String number = (p.number > 0) ? String.valueOf(p.number) : "-";
        String c = ColorTranslator.legacyColorCode(p.color);
        return c + "[" + icon + " " + c + "&n" + number + "&r" + c + "] " + n;
    }

    private static String fallbackRacerMini(String name) {
        String n = (name == null || name.isBlank()) ? "(không rõ)" : name;
        return "<white>● - " + n;
    }

    private static String fallbackRacerLegacy(String name) {
        String n = (name == null || name.isBlank()) ? "(không rõ)" : name;
        return "&f● - " + n;
    }

    private static String shortId(UUID id) {
        if (id == null) return "(không rõ)";
        String s = id.toString();
        return s.length() >= 8 ? s.substring(0, 8) : s;
    }

    public PlayerProfileManager(File dataFolder) {
        this.file = new File(dataFolder, "profiles.yml");
        load();
    }

    public Profile get(UUID id) {
        Profile p = profiles.get(id);
        boolean created = false;
        if (p == null) {
            p = new Profile();
            profiles.put(id, p);
            created = true;
        }

        // If player has not set these yet, assign randomized defaults.
        boolean changed = false;
        if (created) {
            p.color = pick(DEFAULT_COLORS);
            p.number = ThreadLocalRandom.current().nextInt(1, 100); // 1..99
            p.icon = pick(DEFAULT_ICONS);
            changed = true;
        } else {
            if (p.color == null) { p.color = pick(DEFAULT_COLORS); changed = true; }
            if (p.number <= 0) { p.number = ThreadLocalRandom.current().nextInt(1, 100); changed = true; }
            if (isBlank(p.icon)) { p.icon = pick(DEFAULT_ICONS); changed = true; }
        }

        if (changed) save();
        return p;
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

    public long getPersonalBestMillis(UUID id, String trackName) {
        if (id == null || trackName == null || trackName.isBlank()) return 0L;
        Profile p = get(id);
        if (p.personalBests == null) p.personalBests = new HashMap<>();
        Long v = p.personalBests.get(trackName);
        return v == null ? 0L : Math.max(0L, v);
    }

    /**
     * Updates the personal best for a track if the new time is better (lower).
     * Returns true if an update happened.
     */
    public boolean updatePersonalBestIfBetter(UUID id, String trackName, long timeMillis) {
        if (id == null) return false;
        if (trackName == null || trackName.isBlank()) return false;
        if (timeMillis <= 0L) return false;
        Profile p = get(id);
        if (p.personalBests == null) p.personalBests = new HashMap<>();
        Long cur = p.personalBests.get(trackName);
        if (cur != null && cur > 0L && timeMillis >= cur) return false;
        p.personalBests.put(trackName, timeMillis);
        save();
        return true;
    }

    public long getTimeRacedMillis(UUID id) { return Math.max(0L, get(id).timeRacedMillis); }

    /** Adds to the racer total time raced. Accepts milliseconds; negative values are ignored. */
    public void addTimeRacedMillis(UUID id, long millis) {
        if (millis <= 0L) return;
        Profile p = get(id);
        long cur = Math.max(0L, p.timeRacedMillis);
        // Saturating-ish add (avoid overflow turning negative)
        long next;
        try {
            next = Math.addExact(cur, millis);
        } catch (ArithmeticException ignored) {
            next = Long.MAX_VALUE;
        }
        p.timeRacedMillis = next;
        save();
    }

    public String getBoatType(UUID id) { return get(id).boatType; }
    public void setBoatType(UUID id, String type) { get(id).boatType = type == null ? "" : type; save(); }

    public String getSpeedUnit(UUID id) { return get(id).speedUnit; }
    public void setSpeedUnit(UUID id, String unit) {
        String u = unit == null ? "" : unit.toLowerCase();
        if (!u.equals("kmh") && !u.equals("bps") && !u.equals("bph") && !u.isEmpty()) u = ""; // sanitize
        get(id).speedUnit = u;
        save();
    }

    public void load() {
        profiles.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        var sec = cfg.getConfigurationSection("profiles");
        if (sec == null) return;
        boolean changed = false;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Profile p = new Profile();

                boolean hasColor = sec.contains(key + ".color");
                boolean hasNumber = sec.contains(key + ".number");
                boolean hasIcon = sec.contains(key + ".icon");

                String colorName = sec.getString(key + ".color", DyeColor.WHITE.name());
                try { p.color = DyeColor.valueOf(colorName); } catch (IllegalArgumentException ignored) { p.color = null; }
                p.number = sec.getInt(key + ".number", 0);
                p.icon = sec.getString(key + ".icon", "");
                p.completed = sec.getInt(key + ".completed", 0);
                p.wins = sec.getInt(key + ".wins", 0);
                p.timeRacedMillis = Math.max(0L, sec.getLong(key + ".timeRacedMillis", 0L));
                // Per-track personal bests
                p.personalBests = new HashMap<>();
                var pb = sec.getConfigurationSection(key + ".personalBests");
                if (pb != null) {
                    for (String tn : pb.getKeys(false)) {
                        if (tn == null || tn.isBlank()) continue;
                        long ms = Math.max(0L, pb.getLong(tn, 0L));
                        if (ms > 0L) p.personalBests.put(tn, ms);
                    }
                }
                p.boatType = sec.getString(key + ".boatType", "");
                p.speedUnit = sec.getString(key + ".speedUnit", "");

                if (ensureDefaults(p, hasColor, hasNumber, hasIcon)) changed = true;
                profiles.put(id, p);
            } catch (IllegalArgumentException ignored) {}
        }

        if (changed) save();
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Profile> e : profiles.entrySet()) {
            String base = "profiles." + e.getKey().toString();
            Profile p = e.getValue();
            cfg.set(base + ".color", (p.color == null ? DyeColor.WHITE : p.color).name());
            cfg.set(base + ".number", p.number);
            cfg.set(base + ".icon", p.icon);
            cfg.set(base + ".completed", p.completed);
            cfg.set(base + ".wins", p.wins);
            if (p.timeRacedMillis > 0L) cfg.set(base + ".timeRacedMillis", p.timeRacedMillis);
            if (p.personalBests != null && !p.personalBests.isEmpty()) {
                String pbBase = base + ".personalBests";
                for (var en : p.personalBests.entrySet()) {
                    String tn = en.getKey();
                    Long ms = en.getValue();
                    if (tn == null || tn.isBlank() || ms == null || ms <= 0L) continue;
                    cfg.set(pbBase + "." + tn, ms);
                }
            }
            cfg.set(base + ".boatType", p.boatType);
            if (p.speedUnit != null && !p.speedUnit.isEmpty()) cfg.set(base + ".speedUnit", p.speedUnit);
        }
        try { cfg.save(file); } catch (IOException ignored) {}
    }

    private static boolean ensureDefaults(Profile p, boolean hasColorKey, boolean hasNumberKey, boolean hasIconKey) {
        boolean changed = false;

        // Color: only randomize when missing/invalid (not when explicitly set to WHITE).
        if (p.color == null || !hasColorKey) {
            p.color = pick(DEFAULT_COLORS);
            changed = true;
        }

        // Number: 0 means unset.
        if (!hasNumberKey || p.number <= 0) {
            p.number = ThreadLocalRandom.current().nextInt(1, 100); // 1..99
            changed = true;
        }

        // Icon: empty/blank means unset.
        if (!hasIconKey || isBlank(p.icon)) {
            p.icon = pick(DEFAULT_ICONS);
            changed = true;
        }

        return changed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static <T> T pick(T[] values) {
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }
}

