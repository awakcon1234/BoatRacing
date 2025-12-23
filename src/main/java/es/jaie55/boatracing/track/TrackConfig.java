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
        // finish region
        Object fobj = cfg.get("finish");
        Region fin = regionFromObject(fobj);
        if (fin != null) this.finish = fin;
        // pitlane region
        Object pobj = cfg.get("pit");
        Region pit = regionFromObject(pobj);
        if (pit != null) this.pitlane = pit;
        // checkpoints list
        List<?> cps = cfg.getList("checkpoints");
        if (cps != null) {
            for (Object o : cps) {
                Region r = regionFromObject(o);
                if (r != null) this.checkpoints.add(r);
            }
        }
        // team pits map (uuid -> region)
        Object tpObj = cfg.get("team-pits");
        if (tpObj instanceof java.util.Map) {
            java.util.Map<?,?> m = (java.util.Map<?,?>) tpObj;
            for (java.util.Map.Entry<?,?> en : m.entrySet()) {
                try {
                    String key = String.valueOf(en.getKey());
                    java.util.UUID id = java.util.UUID.fromString(key);
                    Region r = regionFromObject(en.getValue());
                    if (r != null) this.teamPits.put(id, r);
                } catch (Throwable ignored) {}
            }
        }
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
            // regions
            if (this.finish != null) cfg.set("finish", regionToMap(this.finish));
            if (this.pitlane != null) cfg.set("pit", regionToMap(this.pitlane));
            if (!this.checkpoints.isEmpty()) {
                List<Map<String,Object>> cps = new ArrayList<>();
                for (Region r : this.checkpoints) cps.add(regionToMap(r));
                cfg.set("checkpoints", cps);
            }
            if (!this.teamPits.isEmpty()) {
                Map<String,Object> map = new LinkedHashMap<>();
                for (Map.Entry<java.util.UUID, Region> en : this.teamPits.entrySet()) {
                    map.put(en.getKey().toString(), regionToMap(en.getValue()));
                }
                cfg.set("team-pits", map);
            }
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

    // Serialization helpers for Region
    private static Map<String,Object> regionToMap(Region r) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("world", r.getWorldName());
        org.bukkit.util.BoundingBox b = r.getBox();
        if (b != null) {
            m.put("minX", b.getMinX()); m.put("minY", b.getMinY()); m.put("minZ", b.getMinZ());
            m.put("maxX", b.getMaxX()); m.put("maxY", b.getMaxY()); m.put("maxZ", b.getMaxZ());
        }
        return m;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static Region regionFromObject(Object obj) {
        if (obj == null) return null;
        java.util.Map m = null;
        if (obj instanceof org.bukkit.configuration.ConfigurationSection cs) {
            m = cs.getValues(false);
        } else if (obj instanceof java.util.Map) {
            m = (java.util.Map) obj;
        }
        if (m == null) return null;
        try {
            String w = (String) m.get("world");
            Number minX = asNumber(m.get("minX"));
            Number minY = asNumber(m.get("minY"));
            Number minZ = asNumber(m.get("minZ"));
            Number maxX = asNumber(m.get("maxX"));
            Number maxY = asNumber(m.get("maxY"));
            Number maxZ = asNumber(m.get("maxZ"));
            if (w == null || minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) return null;
            org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(
                minX.doubleValue(), minY.doubleValue(), minZ.doubleValue(),
                maxX.doubleValue(), maxY.doubleValue(), maxZ.doubleValue()
            );
            return new Region(w, box);
        } catch (Throwable ignored) { return null; }
    }

    private static Number asNumber(Object o) {
        if (o instanceof Number) return (Number) o;
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignored) { }
        }
        return null;
    }
}
