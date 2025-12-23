package es.jaie55.boatracing.track;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Track configuration with simple disk persistence (YAML files under dataFolder/tracks).
 */
public class TrackConfig {
    private final List<Location> starts = new ArrayList<>();
    private final List<Block> lights = new ArrayList<>();
    private final List<Region> checkpoints = new ArrayList<>();
    private Region finish;
    private Region pitlane;
    private final Map<java.util.UUID, Region> teamPits = new HashMap<>();
    private final Map<java.util.UUID, Integer> customStartSlots = new HashMap<>();
    private final File tracksDir;
    private String currentName = null;

    public TrackConfig(File dataFolder) {
        this.tracksDir = new File(dataFolder, "tracks");
        if (!tracksDir.exists()) tracksDir.mkdirs();
    }

    public boolean isReady() {
        return !starts.isEmpty() && finish != null;
    }

    public List<String> missingRequirements() {
        List<String> out = new ArrayList<>();
        if (starts.isEmpty()) out.add("starts");
        if (finish == null) out.add("finish");
        return out;
    }

    // Persistence
    public boolean exists(String name) {
        File f = new File(tracksDir, name + ".yml");
        return f.exists();
    }

    public boolean load(String name) {
        File f = new File(tracksDir, name + ".yml");
        if (!f.exists()) return false;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        this.starts.clear();
        this.lights.clear();
        this.checkpoints.clear();
        this.finish = null; this.pitlane = null; this.teamPits.clear(); this.customStartSlots.clear();
        // starts
        List<?> s = cfg.getList("starts");
        if (s != null) {
            for (Object o : s) {
                if (o instanceof org.bukkit.configuration.ConfigurationSection) continue;
                if (o instanceof java.util.Map) {
                    java.util.Map m = (java.util.Map)o;
                    try {
                        String w = (String)m.get("world");
                        double x = ((Number)m.get("x")).doubleValue();
                        double y = ((Number)m.get("y")).doubleValue();
                        double z = ((Number)m.get("z")).doubleValue();
                        float yaw = m.get("yaw") == null ? 0f : ((Number)m.get("yaw")).floatValue();
                        float pitch = m.get("pitch") == null ? 0f : ((Number)m.get("pitch")).floatValue();
                        Location loc = new Location(Bukkit.getWorld(w), x, y, z, yaw, pitch);
                        this.starts.add(loc);
                    } catch (Throwable ignored) {}
                }
            }
        }
        // lights as simple location lists
        List<?> ls = cfg.getList("lights");
        if (ls != null) {
            for (Object o : ls) {
                if (o instanceof java.util.Map) {
                    java.util.Map m = (java.util.Map)o;
                    try {
                        String w = (String)m.get("world");
                        int x = ((Number)m.get("x")).intValue();
                        int y = ((Number)m.get("y")).intValue();
                        int z = ((Number)m.get("z")).intValue();
                        Block b = Bukkit.getWorld(w).getBlockAt(x,y,z);
                        this.lights.add(b);
                    } catch (Throwable ignored) {}
                }
            }
        }
        // checkpoints, finish, pit not fully restored for simplicity (regions omitted)
        this.currentName = name;
        return true;
    }

    public boolean save(String name) {
        try {
            File f = new File(tracksDir, name + ".yml");
            YamlConfiguration cfg = new YamlConfiguration();
            List<Map<String,Object>> s = new ArrayList<>();
            for (Location loc : this.starts) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("world", loc.getWorld().getName());
                m.put("x", loc.getX()); m.put("y", loc.getY()); m.put("z", loc.getZ());
                m.put("yaw", loc.getYaw()); m.put("pitch", loc.getPitch());
                s.add(m);
            }
            cfg.set("starts", s);
            List<Map<String,Object>> ls = new ArrayList<>();
            for (Block b : this.lights) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("world", b.getWorld().getName()); m.put("x", b.getX()); m.put("y", b.getY()); m.put("z", b.getZ());
                ls.add(m);
            }
            cfg.set("lights", ls);
            cfg.save(f);
            this.currentName = name;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // Starts
    public void addStart(Location loc) { starts.add(loc); }
    public void clearStarts() { starts.clear(); }
    public List<Location> getStarts() { return Collections.unmodifiableList(starts); }

    // Finish / pit
    public void setFinish(Region r) { this.finish = r; }
    public Region getFinish() { return finish; }
    public void setPitlane(Region r) { this.pitlane = r; }
    public Region getPitlane() { return pitlane; }

    // Team pits
    public void setTeamPit(java.util.UUID teamId, Region r) { teamPits.put(teamId, r); }
    public Map<java.util.UUID, Region> getTeamPits() { return Collections.unmodifiableMap(teamPits); }

    // Checkpoints
    public void addCheckpoint(Region r) { checkpoints.add(r); }
    public void clearCheckpoints() { checkpoints.clear(); }
    public List<Region> getCheckpoints() { return Collections.unmodifiableList(checkpoints); }

    // Lights
    public boolean addLight(Block b) { if (lights.size() >= 5) return false; lights.add(b); return true; }
    public void clearLights() { lights.clear(); }
    public List<Block> getLights() { return Collections.unmodifiableList(lights); }

    // Custom slots
    public void setCustomStartSlot(java.util.UUID uid, int zeroBasedIndex) { customStartSlots.put(uid, zeroBasedIndex); }
    public void clearCustomStartSlot(java.util.UUID uid) { customStartSlots.remove(uid); }
    public Map<java.util.UUID, Integer> getCustomStartSlots() { return Collections.unmodifiableMap(customStartSlots); }

    public String getCurrentName() { return currentName; }
}
