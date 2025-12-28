package dev.belikhun.boatracing.track;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores global per-track best time records.
 *
 * File: plugins/BoatRacing/track-records.yml
 */
public class TrackRecordManager {
	public static final class TrackRecord {
		public final String trackName;
		public long bestTimeMillis;
		public UUID holderId;
		public String holderName;

		private TrackRecord(String trackName) {
			this.trackName = trackName;
		}
	}

	private final File file;
	private final Map<String, TrackRecord> recordsByTrack = new HashMap<>();

	public TrackRecordManager(File dataFolder) {
		this.file = new File(dataFolder, "track-records.yml");
		load();
	}

	public synchronized TrackRecord get(String trackName) {
		String key = normalizeKey(trackName);
		if (key == null) return null;
		return recordsByTrack.get(key);
	}

	public synchronized boolean updateIfBetter(String trackName, UUID holderId, String holderName, long timeMillis) {
		String key = normalizeKey(trackName);
		if (key == null) return false;
		if (timeMillis <= 0L) return false;

		TrackRecord rec = recordsByTrack.get(key);
		if (rec == null) {
			rec = new TrackRecord(key);
			recordsByTrack.put(key, rec);
		}

		if (rec.bestTimeMillis > 0L && timeMillis >= rec.bestTimeMillis) {
			return false;
		}

		rec.bestTimeMillis = timeMillis;
		rec.holderId = holderId;
		rec.holderName = holderName == null ? "" : holderName;

		save();
		return true;
	}

	public synchronized void load() {
		recordsByTrack.clear();
		if (!file.exists()) return;

		YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection tracks = cfg.getConfigurationSection("tracks");
		if (tracks == null) return;

		for (String key : tracks.getKeys(false)) {
			if (key == null || key.isBlank()) continue;
			ConfigurationSection sec = tracks.getConfigurationSection(key);
			if (sec == null) continue;

			TrackRecord rec = new TrackRecord(key);
			rec.bestTimeMillis = Math.max(0L, sec.getLong("bestTimeMillis", 0L));
			String uuidStr = sec.getString("holder.uuid", "");
			if (uuidStr != null && !uuidStr.isBlank()) {
				try { rec.holderId = UUID.fromString(uuidStr); } catch (IllegalArgumentException ignored) { rec.holderId = null; }
			}
			rec.holderName = sec.getString("holder.name", "");

			recordsByTrack.put(normalizeKey(key), rec);
		}
	}

	public synchronized void save() {
		YamlConfiguration cfg = new YamlConfiguration();
		for (TrackRecord rec : recordsByTrack.values()) {
			if (rec == null) continue;
			String base = "tracks." + rec.trackName;
			if (rec.bestTimeMillis > 0L) cfg.set(base + ".bestTimeMillis", rec.bestTimeMillis);
			if (rec.holderId != null) cfg.set(base + ".holder.uuid", rec.holderId.toString());
			if (rec.holderName != null && !rec.holderName.isBlank()) cfg.set(base + ".holder.name", rec.holderName);
		}

		try { cfg.save(file); } catch (IOException ignored) {}
	}

	private static String normalizeKey(String s) {
		if (s == null) return null;
		String k = s.trim();
		if (k.isEmpty()) return null;
		// Keep original case as users likely refer to track files by name, but normalize whitespace.
		return k;
	}
}
