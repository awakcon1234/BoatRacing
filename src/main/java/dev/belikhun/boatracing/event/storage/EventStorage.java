package dev.belikhun.boatracing.event.storage;

import dev.belikhun.boatracing.event.*;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class EventStorage {
	private EventStorage() {}

	public static File eventsFolder(File dataFolder) {
		return new File(dataFolder, "events");
	}

	public static File eventFile(File dataFolder, String eventId) {
		String id = (eventId == null ? "" : eventId.trim());
		return new File(eventsFolder(dataFolder), id + ".yml");
	}

	public static File metaFile(File dataFolder) {
		return new File(eventsFolder(dataFolder), "_meta.yml");
	}

	public static void ensureFolders(File dataFolder) {
		File dir = eventsFolder(dataFolder);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
	}

	public static void saveActive(File dataFolder, String activeEventId) {
		ensureFolders(dataFolder);
		YamlConfiguration y = new YamlConfiguration();
		if (activeEventId != null && !activeEventId.isBlank())
			y.set("active", activeEventId.trim());
		try {
			y.save(metaFile(dataFolder));
		} catch (IOException ignored) {}
	}

	public static String loadActive(File dataFolder) {
		ensureFolders(dataFolder);
		File f = metaFile(dataFolder);
		if (!f.exists())
			return null;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
		String a = y.getString("active", null);
		return (a == null || a.isBlank()) ? null : a.trim();
	}

	public static void saveEvent(File dataFolder, RaceEvent e) {
		if (e == null)
			return;
		if (e.id == null || e.id.isBlank())
			return;
		ensureFolders(dataFolder);

		YamlConfiguration y = new YamlConfiguration();
		y.set("id", e.id);
		y.set("title", e.title);
		y.set("description", e.description);
		y.set("startTimeMillis", e.startTimeMillis);
		y.set("state", (e.state == null ? EventState.DRAFT.name() : e.state.name()));
		y.set("currentTrackIndex", e.currentTrackIndex);
		y.set("trackPool", (e.trackPool == null ? List.of() : new ArrayList<>(e.trackPool)));

		List<String> order = new ArrayList<>();
		if (e.registrationOrder != null) {
			for (UUID id : e.registrationOrder) {
				if (id != null)
					order.add(id.toString());
			}
		}
		y.set("registrationOrder", order);

		Map<String, Object> parts = new LinkedHashMap<>();
		if (e.participants != null) {
			for (var en : e.participants.entrySet()) {
				UUID id = en.getKey();
				EventParticipant p = en.getValue();
				if (id == null || p == null)
					continue;
				Map<String, Object> ps = new LinkedHashMap<>();
				ps.put("nameSnapshot", p.nameSnapshot == null ? "" : p.nameSnapshot);
				ps.put("pointsTotal", p.pointsTotal);
				ps.put("status", (p.status == null ? EventParticipantStatus.REGISTERED.name() : p.status.name()));
				ps.put("lastSeenMillis", p.lastSeenMillis);
				parts.put(id.toString(), ps);
			}
		}
		y.set("participants", parts);

		try {
			y.save(eventFile(dataFolder, e.id));
		} catch (IOException ignored) {}
	}

	public static RaceEvent loadEvent(File dataFolder, String eventId) {
		if (eventId == null || eventId.isBlank())
			return null;
		ensureFolders(dataFolder);
		File f = eventFile(dataFolder, eventId);
		if (!f.exists())
			return null;

		YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
		RaceEvent e = new RaceEvent();
		e.id = y.getString("id", eventId);
		e.title = y.getString("title", "");
		e.description = y.getString("description", "");
		e.startTimeMillis = Math.max(0L, y.getLong("startTimeMillis", 0L));

		String st = y.getString("state", EventState.DRAFT.name());
		try {
			e.state = EventState.valueOf(st);
		} catch (IllegalArgumentException ignored) {
			e.state = EventState.DRAFT;
		}

		e.currentTrackIndex = Math.max(0, y.getInt("currentTrackIndex", 0));
		List<String> pool = y.getStringList("trackPool");
		e.trackPool = pool == null ? new ArrayList<>() : new ArrayList<>(pool);

		List<String> order = y.getStringList("registrationOrder");
		e.registrationOrder = new ArrayList<>();
		if (order != null) {
			for (String s : order) {
				try {
					e.registrationOrder.add(UUID.fromString(s));
				} catch (IllegalArgumentException ignored) {}
			}
		}

		e.participants = new HashMap<>();
		var sec = y.getConfigurationSection("participants");
		if (sec != null) {
			for (String key : sec.getKeys(false)) {
				UUID id;
				try {
					id = UUID.fromString(key);
				} catch (IllegalArgumentException ignored) {
					continue;
				}
				EventParticipant p = new EventParticipant(id);
				p.nameSnapshot = sec.getString(key + ".nameSnapshot", "");
				p.pointsTotal = sec.getInt(key + ".pointsTotal", 0);
				p.lastSeenMillis = Math.max(0L, sec.getLong(key + ".lastSeenMillis", 0L));
				String ps = sec.getString(key + ".status", EventParticipantStatus.REGISTERED.name());
				try {
					p.status = EventParticipantStatus.valueOf(ps);
				} catch (IllegalArgumentException ignored) {
					p.status = EventParticipantStatus.REGISTERED;
				}
				e.participants.put(id, p);
			}
		}

		// Ensure registrationOrder contains only known participants (first-come-first-serve).
		if (e.registrationOrder == null)
			e.registrationOrder = new ArrayList<>();
		e.registrationOrder.removeIf(id -> id == null || e.participants == null || !e.participants.containsKey(id));

		return e;
	}

	public static java.util.List<String> listEventIds(File dataFolder) {
		ensureFolders(dataFolder);
		File dir = eventsFolder(dataFolder);
		File[] files = dir.listFiles((d, n) -> n != null && n.endsWith(".yml") && !n.startsWith("_"));
		if (files == null || files.length == 0)
			return java.util.List.of();
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (File f : files) {
			String n = f.getName();
			if (n == null)
				continue;
			if (!n.endsWith(".yml"))
				continue;
			ids.add(n.substring(0, n.length() - 4));
		}
		ids.sort(String.CASE_INSENSITIVE_ORDER);
		return ids;
	}
}
