package dev.belikhun.boatracing.track;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Track configuration with simple disk persistence (YAML files under dataFolder/tracks).
 */
public class TrackConfig {
	// Optional per-track UI metadata
	private org.bukkit.inventory.ItemStack icon;
	private java.util.UUID authorId;
	private String authorName;
	private String authorText;

	private final List<Location> starts = new ArrayList<>();
	private final List<Block> lights = new ArrayList<>();
	private final List<Region> checkpoints = new ArrayList<>();
	private Region finish;
	// Optional overall track bounding box (set via setup pos1/pos2 + setbounds)
	private Region bounds;
	private Region pitlane;
	private final Map<java.util.UUID, Region> teamPits = new HashMap<>();
	private final Map<java.util.UUID, Integer> customStartSlots = new HashMap<>();
	// Centerline polyline nodes across the entire course (optional, built by path builder)
	private final List<org.bukkit.Location> centerline = new ArrayList<>();
	// Cached track length in blocks (arc-length along the centerline, including loop close)
	private double cachedTrackLength = -1.0;
	private final File tracksDir;
	private final Logger logger;
	private String currentName = null;
	private org.bukkit.Location waitingSpawn;
	// Single world name for the entire track
	private String worldName = null;

	public TrackConfig(File dataFolder) {
		this(Logger.getLogger("BoatRacing"), dataFolder);
	}

	public TrackConfig(org.bukkit.plugin.Plugin plugin, File dataFolder) {
		this(plugin != null ? plugin.getLogger() : Logger.getLogger("BoatRacing"), dataFolder);
	}

	public TrackConfig(Logger logger, File dataFolder) {
		this.logger = (logger != null) ? logger : Logger.getLogger("BoatRacing");
		this.tracksDir = new File(dataFolder, "tracks");
		if (!tracksDir.exists()) tracksDir.mkdirs();
	}

	public boolean isReady() {
		return !starts.isEmpty() && finish != null;
	}

	/**
	 * Reset ALL in-memory data for creating a brand-new track.
	 *
	 * This is intentionally stricter than individual clear*() helpers and is used
	 * by the "Tạo mới" flow to avoid copying data from the previously selected track.
	 */
	public void resetForNewTrack() {
		this.icon = null;
		this.authorId = null;
		this.authorName = null;
		this.authorText = null;
		this.starts.clear();
		this.lights.clear();
		this.checkpoints.clear();
		this.finish = null;
		this.bounds = null;
		this.pitlane = null;
		this.teamPits.clear();
		this.customStartSlots.clear();
		this.centerline.clear();
		this.cachedTrackLength = -1.0;
		this.waitingSpawn = null;
		this.worldName = null;
		this.currentName = null;
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
		this.icon = null;
		this.authorId = null;
		this.authorName = null;
		this.authorText = null;
		this.starts.clear();
		this.lights.clear();
		this.checkpoints.clear();
		this.finish = null; this.bounds = null; this.pitlane = null; this.teamPits.clear(); this.customStartSlots.clear();
		this.centerline.clear();
		this.cachedTrackLength = readCachedTrackLength(cfg);
		this.waitingSpawn = null;
		// Critical: do not carry worldName across tracks. It must be derived from the file or inferred.
		this.worldName = null;

		// UI metadata
		try {
			org.bukkit.inventory.ItemStack it = cfg.getItemStack("icon");
			if (it != null && it.getType() != org.bukkit.Material.AIR) {
				this.icon = it.clone();
				try { this.icon.setAmount(1); } catch (Throwable ignored) {}
			}
		} catch (Throwable ignored) {
			this.icon = null;
		}
		try {
			// New format: author.uuid/name or author.text
			String uuidStr = cfg.getString("author.uuid", null);
			if (uuidStr == null) uuidStr = cfg.getString("author.id", null);
			if (uuidStr == null) uuidStr = cfg.getString("author-uuid", null);
			if (uuidStr != null && !uuidStr.isBlank()) {
				try {
					this.authorId = java.util.UUID.fromString(uuidStr.trim());
				} catch (Throwable ignored2) {
					this.authorId = null;
				}
				this.authorName = cfg.getString("author.name", null);
				if (this.authorName == null) this.authorName = cfg.getString("author-name", null);
				this.authorText = null;
			} else {
				String txt = cfg.getString("author.text", null);
				if (txt == null) txt = cfg.getString("author", null);
				this.authorText = (txt != null && !txt.isBlank()) ? txt.trim() : null;
				this.authorId = null;
				this.authorName = null;
			}
		} catch (Throwable ignored) {
			this.authorId = null;
			this.authorName = null;
			this.authorText = null;
		}

		// track world
		this.worldName = cfg.getString("world", this.worldName);
		// starts
		List<?> s = cfg.getList("starts");
		if (s != null) {
			for (Object o : s) {
				if (o instanceof org.bukkit.configuration.ConfigurationSection) continue;
				if (o instanceof java.util.Map) {
					java.util.Map<?,?> m = (java.util.Map<?,?>) o;
					try {
						String w = (String)m.get("world");
						if (w == null) w = this.worldName;
						double x = ((Number)m.get("x")).doubleValue();
						double y = ((Number)m.get("y")).doubleValue();
						double z = ((Number)m.get("z")).doubleValue();
						float yaw = m.get("yaw") == null ? 0f : ((Number)m.get("yaw")).floatValue();
						float pitch = m.get("pitch") == null ? 0f : ((Number)m.get("pitch")).floatValue();
						org.bukkit.World world = Bukkit.getWorld(w);
						if (world == null) {
							logger.warning("TrackConfig: start position world not loaded: " + w + " (track=" + name + ")");
						}
						Location loc = new Location(world, x, y, z, yaw, pitch);
						this.starts.add(loc);
					} catch (Throwable ignored) {}
				}
			}
		}

		// Legacy tracks might not have the top-level 'world' key; infer it from loaded start positions.
		if (this.worldName == null && !this.starts.isEmpty()) {
			org.bukkit.Location l0 = this.starts.get(0);
			if (l0 != null && l0.getWorld() != null) this.worldName = l0.getWorld().getName();
		}
		// lights as simple location lists
		List<?> ls = cfg.getList("lights");
		if (ls != null) {
			for (Object o : ls) {
				if (o instanceof java.util.Map) {
					java.util.Map<?,?> m = (java.util.Map<?,?>) o;
					try {
						String w = (String)m.get("world");
						if (w == null) w = this.worldName;
						int x = ((Number)m.get("x")).intValue();
						int y = ((Number)m.get("y")).intValue();
						int z = ((Number)m.get("z")).intValue();
						org.bukkit.World world = Bukkit.getWorld(w);
						if (world == null) {
							logger.warning("TrackConfig: light world not loaded: " + w + " (track=" + name + ")");
							continue;
						}
						Block b = world.getBlockAt(x,y,z);
						this.lights.add(b);
					} catch (Throwable ignored) {}
				}
			}
		}
		// finish region
		Object fobj = cfg.get("finish");
		Region fin = regionFromObject(fobj);
		if (fin != null) this.finish = fin;

		// Legacy tracks might keep world only inside region objects; capture it once.
		if (this.worldName == null && this.finish != null && this.finish.getWorldName() != null) {
			this.worldName = this.finish.getWorldName();
		}

		// optional bounds region
		Object bobj = cfg.get("bounds");
		Region b = regionFromObject(bobj);
		if (b != null) this.bounds = b;
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
		// centerline nodes
		java.util.List<?> cl = cfg.getList("centerline");
		if (cl != null) {
			for (Object o : cl) {
				if (o instanceof java.util.Map) {
					java.util.Map<?,?> m = (java.util.Map<?,?>) o;
					try {
						String w = (String) m.get("world");
						if (w == null) w = this.worldName;
						double x = asNumber(m.get("x")).doubleValue();
						double y = asNumber(m.get("y")).doubleValue();
						double z = asNumber(m.get("z")).doubleValue();
						org.bukkit.World ww = org.bukkit.Bukkit.getWorld(w);
						if (ww != null) this.centerline.add(new org.bukkit.Location(ww, x, y, z));
						else logger.warning("TrackConfig: centerline world not loaded: " + w + " (track=" + name + ")");
					} catch (Throwable ignored) {}
				}
			}
		}

		// If we successfully loaded a centerline, recompute length (overrides cache).
		try {
			double computed = computeTrackLengthFromCenterline();
			if (computed > 0.0) this.cachedTrackLength = computed;
		} catch (Throwable ignored) {}
		this.currentName = name;
		// waiting spawn
		Object wsp = cfg.get("waitingSpawn");
		if (wsp == null) wsp = cfg.get("waiting_spawn");
		if (wsp == null) wsp = cfg.get("waiting-spawn");
		org.bukkit.Location wsLoc = locationFromObject(wsp);
		if (wsLoc != null) this.waitingSpawn = wsLoc;
		else if (wsp != null) {
			String type = wsp.getClass().getName();
			logger.warning("TrackConfig: waitingSpawn present but failed to parse (type=" + type + ", track=" + name + ", world=" + this.worldName + ")");
		}
		return true;
	}

	private double readCachedTrackLength(YamlConfiguration cfg) {
		if (cfg == null) return -1.0;
		try {
			if (cfg.contains("cache.centerline-length")) return cfg.getDouble("cache.centerline-length", -1.0);
			if (cfg.contains("centerline-length")) return cfg.getDouble("centerline-length", -1.0);
			if (cfg.contains("centerlineLength")) return cfg.getDouble("centerlineLength", -1.0);
			if (cfg.contains("track-length")) return cfg.getDouble("track-length", -1.0);
			if (cfg.contains("trackLength")) return cfg.getDouble("trackLength", -1.0);
		} catch (Throwable ignored) {}
		return -1.0;
	}

	private double computeTrackLengthFromCenterline() {
		if (this.centerline == null || this.centerline.size() < 2) return 0.0;
		double sum = 0.0;
		for (int i = 1; i < this.centerline.size(); i++) {
			org.bukkit.Location a = this.centerline.get(i - 1);
			org.bukkit.Location b = this.centerline.get(i);
			if (a == null || b == null) continue;
			if (a.getWorld() == null || b.getWorld() == null) continue;
			if (!a.getWorld().equals(b.getWorld())) continue;
			try {
				double d = a.distance(b);
				if (Double.isFinite(d) && d > 0.0) sum += d;
			} catch (Throwable ignored) {}
		}

		// Close the loop (used by race logic when computing arc-length distances)
		try {
			org.bukkit.Location last = this.centerline.get(this.centerline.size() - 1);
			org.bukkit.Location first = this.centerline.get(0);
			if (last != null && first != null && last.getWorld() != null && first.getWorld() != null
					&& last.getWorld().equals(first.getWorld())) {
				double d = last.distance(first);
				if (Double.isFinite(d) && d > 0.0) sum += d;
			}
		} catch (Throwable ignored) {}

		return sum;
	}

	public boolean save(String name) {
		try {
			File f = new File(tracksDir, name + ".yml");
			YamlConfiguration cfg = new YamlConfiguration();

			// UI metadata
			try {
				if (this.icon != null && this.icon.getType() != org.bukkit.Material.AIR) {
					org.bukkit.inventory.ItemStack it = this.icon.clone();
					try { it.setAmount(1); } catch (Throwable ignored) {}
					cfg.set("icon", it);
				}
			} catch (Throwable ignored) {
			}
			try {
				if (this.authorId != null) {
					cfg.set("author.uuid", this.authorId.toString());
					if (this.authorName != null && !this.authorName.isBlank()) cfg.set("author.name", this.authorName);
				} else if (this.authorText != null && !this.authorText.isBlank()) {
					cfg.set("author.text", this.authorText);
				}
			} catch (Throwable ignored) {
			}

			// Ensure world is always persisted once when possible (prevents waitingSpawn-only tracks from losing their world on reload)
			if (this.worldName == null && this.waitingSpawn != null && this.waitingSpawn.getWorld() != null) {
				this.worldName = this.waitingSpawn.getWorld().getName();
			}
			if (this.worldName != null) cfg.set("world", this.worldName);
			List<Map<String,Object>> s = new ArrayList<>();
			for (Location loc : this.starts) {
				Map<String,Object> m = new LinkedHashMap<>();
				m.put("x", loc.getX()); m.put("y", loc.getY()); m.put("z", loc.getZ());
				m.put("yaw", loc.getYaw()); m.put("pitch", loc.getPitch());
				s.add(m);
			}
			cfg.set("starts", s);
			List<Map<String,Object>> ls = new ArrayList<>();
			for (Block b : this.lights) {
				Map<String,Object> m = new LinkedHashMap<>();
				m.put("x", b.getX()); m.put("y", b.getY()); m.put("z", b.getZ());
				ls.add(m);
			}
			cfg.set("lights", ls);
			// regions
			if (this.finish != null) cfg.set("finish", regionToMap(this.finish));
			if (this.bounds != null) cfg.set("bounds", regionToMap(this.bounds));
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
			if (!this.centerline.isEmpty()) {
				java.util.List<java.util.Map<String,Object>> cl = new java.util.ArrayList<>();
				for (org.bukkit.Location loc : this.centerline) {
					java.util.Map<String,Object> m = new java.util.LinkedHashMap<>();
					m.put("x", loc.getX()); m.put("y", loc.getY()); m.put("z", loc.getZ());
					cl.add(m);
				}
				cfg.set("centerline", cl);
			}

			// Persist cached length for faster UI and as a fallback when worlds aren't loaded.
			try {
				double computed = computeTrackLengthFromCenterline();
				if (computed > 0.0) this.cachedTrackLength = computed;
			} catch (Throwable ignored) {}
			if (this.cachedTrackLength > 0.0 && Double.isFinite(this.cachedTrackLength)) {
				cfg.set("cache.centerline-length", this.cachedTrackLength);
			}
			if (this.waitingSpawn != null) {
				java.util.Map<String,Object> ws = new java.util.LinkedHashMap<>();
				String wName = (this.waitingSpawn.getWorld() != null ? this.waitingSpawn.getWorld().getName() : this.worldName);
				if (wName != null) ws.put("world", wName);
				ws.put("x", this.waitingSpawn.getX());
				ws.put("y", this.waitingSpawn.getY());
				ws.put("z", this.waitingSpawn.getZ());
				ws.put("yaw", this.waitingSpawn.getYaw());
				ws.put("pitch", this.waitingSpawn.getPitch());
				cfg.set("waitingSpawn", ws);
			}
			cfg.save(f);
			this.currentName = name;
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	// Starts
	public void addStart(Location loc) {
		if (loc != null && loc.getWorld() != null && (this.worldName == null)) this.worldName = loc.getWorld().getName();
		starts.add(normalizeStart(withTrackWorld(loc)));
	}

	/**
	 * Normalize a start location:
	 * - X/Z snapped to nearest 0.5 (.. .0 or .5)
	 * - Pitch = 0
	 * - Yaw snapped to nearest multiple of 45 degrees (kept in [-180, 180])
	 */
	public static Location normalizeStart(Location src) {
		if (src == null) return null;
		Location l = src.clone();
		// Snap X/Z to nearest 0.5
		double nx = Math.round(l.getX() * 2.0) / 2.0;
		double nz = Math.round(l.getZ() * 2.0) / 2.0;
		l.setX(nx);
		l.setZ(nz);
		// Pitch to 0
		l.setPitch(0.0f);
		// Yaw to nearest multiple of 45
		float yaw = l.getYaw();
		double snapped = Math.round(((double) yaw) / 45.0) * 45.0;
		// Normalize to [-180, 180]
		while (snapped <= -180.0) snapped += 360.0;
		while (snapped > 180.0) snapped -= 360.0;
		l.setYaw((float) snapped);
		return l;
	}
	public void clearStarts() { starts.clear(); }
	public List<Location> getStarts() { return Collections.unmodifiableList(starts); }

	// Finish / pit
	public void setFinish(Region r) { this.finish = overrideRegionWorld(r); }
	public Region getFinish() { return finish; }
	// Bounds
	public void setBounds(Region r) {
		if (r == null) { this.bounds = null; return; }
		org.bukkit.util.BoundingBox b = r.getBox();
		if (b != null) b = snapBoxToWhole(b);
		this.bounds = overrideRegionWorld(new Region(r.getWorldName(), b));
	}
	public Region getBounds() { return bounds; }
	public void setPitlane(Region r) { this.pitlane = overrideRegionWorld(r); }
	public Region getPitlane() { return pitlane; }

	// Team pits
	public void setTeamPit(java.util.UUID teamId, Region r) { teamPits.put(teamId, r); }
	public Map<java.util.UUID, Region> getTeamPits() { return Collections.unmodifiableMap(teamPits); }

	// Checkpoints
	public void addCheckpoint(Region r) { checkpoints.add(overrideRegionWorld(r)); }
	public void clearCheckpoints() { checkpoints.clear(); }
	public List<Region> getCheckpoints() { return Collections.unmodifiableList(checkpoints); }

	// Lights
	public boolean addLight(Block b) {
		if (b == null) return false;
		if (b.getType() != org.bukkit.Material.REDSTONE_LAMP) return false;
		for (Block existing : lights) {
			if (existing == null) continue;
			try {
				if (existing.getWorld() != null && b.getWorld() != null && !existing.getWorld().equals(b.getWorld())) continue;
				if (existing.getX() == b.getX() && existing.getY() == b.getY() && existing.getZ() == b.getZ()) return false;
			} catch (Throwable ignored) {}
		}
		lights.add(b);
		return true;
	}
	public void clearLights() { lights.clear(); }
	public List<Block> getLights() { return Collections.unmodifiableList(lights); }

	// Custom slots
	public void setCustomStartSlot(java.util.UUID uid, int zeroBasedIndex) { customStartSlots.put(uid, zeroBasedIndex); }
	public void clearCustomStartSlot(java.util.UUID uid) { customStartSlots.remove(uid); }
	public Map<java.util.UUID, Integer> getCustomStartSlots() { return Collections.unmodifiableMap(customStartSlots); }

	public String getCurrentName() { return currentName; }
	public java.util.List<org.bukkit.Location> getCenterline() { return java.util.Collections.unmodifiableList(centerline); }
	public double getTrackLength() { return (Double.isFinite(cachedTrackLength) ? cachedTrackLength : -1.0); }
	public void setCenterline(java.util.List<org.bukkit.Location> nodes) {
		this.centerline.clear();
		if (nodes != null) {
			for (org.bukkit.Location l : nodes) this.centerline.add(withTrackWorld(l));
		}
		try {
			double computed = computeTrackLengthFromCenterline();
			this.cachedTrackLength = computed > 0.0 ? computed : this.cachedTrackLength;
		} catch (Throwable ignored) {}
	}
	public void clearCenterline() {
		this.centerline.clear();
		this.cachedTrackLength = -1.0;
	}

	public org.bukkit.Location getWaitingSpawn() { return withTrackWorld(waitingSpawn); }
	public void setWaitingSpawn(org.bukkit.Location loc) {
		if (loc == null) { this.waitingSpawn = null; return; }
		if (loc.getWorld() != null) {
			String w = loc.getWorld().getName();
			if (this.worldName == null) {
				this.worldName = w;
			} else if (!this.worldName.equals(w)) {
				logger.warning("TrackConfig: waitingSpawn world differs from track world (trackWorld=" + this.worldName + ", spawnWorld=" + w + ", track=" + currentName + ")");
				this.worldName = w;
			}
		}
		this.waitingSpawn = withTrackWorld(loc);
	}

	// --- Helpers ---
	private static org.bukkit.util.BoundingBox snapBoxToWhole(org.bukkit.util.BoundingBox box) {
		if (box == null) return null;
		double minX = Math.round(box.getMinX());
		double minY = Math.round(box.getMinY());
		double minZ = Math.round(box.getMinZ());
		double maxX = Math.round(box.getMaxX());
		double maxY = Math.round(box.getMaxY());
		double maxZ = Math.round(box.getMaxZ());
		// Ensure ordering after rounding
		double nminX = Math.min(minX, maxX), nmaxX = Math.max(minX, maxX);
		double nminY = Math.min(minY, maxY), nmaxY = Math.max(minY, maxY);
		double nminZ = Math.min(minZ, maxZ), nmaxZ = Math.max(minZ, maxZ);
		return new org.bukkit.util.BoundingBox(nminX, nminY, nminZ, nmaxX, nmaxY, nmaxZ);
	}

	public org.bukkit.Location getStartCenter() {
		if (starts.isEmpty()) return null;
		double x = 0, y = 0, z = 0; String world = null;
		for (org.bukkit.Location l : starts) { x += l.getX(); y += l.getY(); z += l.getZ(); if (world == null && l.getWorld()!=null) world = l.getWorld().getName(); }
		x /= starts.size(); y /= starts.size(); z /= starts.size();
		if (world == null) world = this.worldName;
		org.bukkit.World w = world != null ? org.bukkit.Bukkit.getWorld(world) : (starts.get(0).getWorld());
		return w != null ? new org.bukkit.Location(w, x, y, z) : null;
	}

	// Serialization helpers for Region
	private static Map<String,Object> regionToMap(Region r) {
		Map<String,Object> m = new LinkedHashMap<>();
		org.bukkit.util.BoundingBox b = r.getBox();
		if (b != null) {
			m.put("minX", b.getMinX()); m.put("minY", b.getMinY()); m.put("minZ", b.getMinZ());
			m.put("maxX", b.getMaxX()); m.put("maxY", b.getMaxY()); m.put("maxZ", b.getMaxZ());
		}
		return m;
	}

	private Region regionFromObject(Object obj) {
		if (obj == null) return null;
		java.util.Map<?,?> m = null;
		if (obj instanceof org.bukkit.configuration.ConfigurationSection cs) {
			m = cs.getValues(false);
		} else if (obj instanceof java.util.Map) {
			m = (java.util.Map<?,?>) obj;
		}
		if (m == null) return null;
		try {
			String w = (String) m.get("world");
			if (w == null) w = this.worldName;
			Number minX = asNumber(m.get("minX"));
			Number minY = asNumber(m.get("minY"));
			Number minZ = asNumber(m.get("minZ"));
			Number maxX = asNumber(m.get("maxX"));
			Number maxY = asNumber(m.get("maxY"));
			Number maxZ = asNumber(m.get("maxZ"));
			if (w == null || minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) return null;
			double nminX = Math.min(minX.doubleValue(), maxX.doubleValue());
			double nmaxX = Math.max(minX.doubleValue(), maxX.doubleValue());
			double nminY = Math.min(minY.doubleValue(), maxY.doubleValue());
			double nmaxY = Math.max(minY.doubleValue(), maxY.doubleValue());
			double nminZ = Math.min(minZ.doubleValue(), maxZ.doubleValue());
			double nmaxZ = Math.max(minZ.doubleValue(), maxZ.doubleValue());
			org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(nminX, nminY, nminZ, nmaxX, nmaxY, nmaxZ);
			return new Region(w, box);
		} catch (Throwable ignored) { return null; }
	}

	@SuppressWarnings({"rawtypes"})
	private org.bukkit.Location locationFromObject(Object obj) {
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
			if (w == null || w.isBlank()) w = this.worldName;
			Number xN = asNumber(m.get("x"));
			Number yN = asNumber(m.get("y"));
			Number zN = asNumber(m.get("z"));
			if (xN == null || yN == null || zN == null) return null;
			double x = xN.doubleValue();
			double y = yN.doubleValue();
			double z = zN.doubleValue();
			float yaw = m.get("yaw") == null ? 0f : ((Number) m.get("yaw")).floatValue();
			float pitch = m.get("pitch") == null ? 0f : ((Number) m.get("pitch")).floatValue();
			org.bukkit.World ww = (w != null ? org.bukkit.Bukkit.getWorld(w) : null);
			if (ww != null) return new org.bukkit.Location(ww, x, y, z, yaw, pitch);
			// Keep coordinates so we can still apply worldName later via withTrackWorld().
			if (w != null) logger.warning("TrackConfig: waitingSpawn world not loaded: " + w + " (track=" + currentName + ")");
			return new org.bukkit.Location(null, x, y, z, yaw, pitch);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Number asNumber(Object o) {
		if (o instanceof Number) return (Number) o;
		if (o instanceof String s) {
			try { return Double.parseDouble(s); } catch (Exception ignored) { }
		}
		return null;
	}

	// Enforce single world on locations and regions
	private org.bukkit.Location withTrackWorld(org.bukkit.Location src) {
		if (src == null) return null;
		if (this.worldName == null && src.getWorld() != null) this.worldName = src.getWorld().getName();
		if (this.worldName == null) return src.clone();
		org.bukkit.World w = org.bukkit.Bukkit.getWorld(this.worldName);
		if (w == null) return src.clone();
		return new org.bukkit.Location(w, src.getX(), src.getY(), src.getZ(), src.getYaw(), src.getPitch());
	}
	private Region overrideRegionWorld(Region r) {
		if (r == null) return null;
		String w = this.worldName != null ? this.worldName : r.getWorldName();
		return new Region(w, r.getBox());
	}

	public String getWorldName() { return worldName; }
	public void setWorldName(String worldName) { this.worldName = worldName; }

	// --- UI metadata (icon/author) ---
	public org.bukkit.inventory.ItemStack getIcon() {
		return (icon == null ? null : icon.clone());
	}

	public void setIcon(org.bukkit.inventory.ItemStack icon) {
		if (icon == null || icon.getType() == org.bukkit.Material.AIR) {
			this.icon = null;
			return;
		}
		try {
			org.bukkit.inventory.ItemStack it = icon.clone();
			try { it.setAmount(1); } catch (Throwable ignored) {}
			this.icon = it;
		} catch (Throwable ignored) {
			this.icon = null;
		}
	}

	public java.util.UUID getAuthorId() { return authorId; }
	public String getAuthorName() { return authorName; }
	public String getAuthorText() { return authorText; }

	public void clearAuthor() {
		this.authorId = null;
		this.authorName = null;
		this.authorText = null;
	}

	public void setAuthorRacer(java.util.UUID id, String name) {
		if (id == null) {
			clearAuthor();
			return;
		}
		this.authorId = id;
		this.authorName = (name == null || name.isBlank()) ? null : name;
		this.authorText = null;
	}

	public void setAuthorText(String text) {
		String t = (text == null) ? null : text.trim();
		if (t == null || t.isBlank()) {
			clearAuthor();
			return;
		}
		this.authorId = null;
		this.authorName = null;
		this.authorText = t;
	}
}

