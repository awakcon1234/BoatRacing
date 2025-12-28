package dev.belikhun.boatracing.race;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class RaceFx {
	private RaceFx() {
	}

	private static NamespacedKey fireworkKey(Plugin plugin) {
		try {
			if (plugin == null)
				return null;
			return new NamespacedKey(plugin, "boatracing_fx_firework");
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static void markFirework(Plugin plugin, Firework fw) {
		if (plugin == null || fw == null)
			return;
		NamespacedKey key = fireworkKey(plugin);
		if (key == null)
			return;
		try {
			fw.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
		} catch (Throwable ignored) {
		}
	}

	public static boolean isRaceFirework(Plugin plugin, Entity e) {
		if (plugin == null || e == null)
			return false;
		NamespacedKey key = fireworkKey(plugin);
		if (key == null)
			return false;
		try {
			return e.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
		} catch (Throwable ignored) {
			return false;
		}
	}
}
