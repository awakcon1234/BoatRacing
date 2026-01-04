package dev.belikhun.boatracing.track;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple tracks directory listing and selection.
 */
public class TrackLibrary {
	private String current;
	private final File tracksDir;
	private final TrackConfig config;

	public TrackLibrary(File dataFolder, TrackConfig config) {
		this.tracksDir = new File(dataFolder, "tracks");
		if (!tracksDir.exists()) tracksDir.mkdirs();
		this.config = config;
	}

	public boolean exists(String name) {
		File f = new File(tracksDir, name + ".yml");
		return f.exists();
	}

	public boolean select(String name) {
		if (name == null) return false;
		boolean ok = config.load(name);
		if (ok) this.current = name;
		return ok;
	}

	public boolean delete(String name) {
		if (name == null) return false;
		String key = name.trim();
		if (key.isEmpty()) return false;
		File f = new File(tracksDir, key + ".yml");
		boolean existed = f.exists();
		boolean deleted = false;
		try {
			if (existed) deleted = f.delete();
		} catch (Throwable ignored) {
			deleted = false;
		}
		if (current != null && current.equalsIgnoreCase(key)) current = null;
		return existed && deleted;
	}

	public String getCurrent() { return current; }

	public List<String> list() {
		String[] children = tracksDir.list((d, n) -> n.endsWith(".yml"));
		if (children == null) return java.util.Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (String c : children) out.add(c.substring(0, c.length()-4));
		out.sort(String::compareToIgnoreCase);
		return out;
	}
}

