package dev.belikhun.boatracing.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Best-effort hard position/rotation snap for entities.
 *
 * Used for countdown freeze when Bukkit teleport is rejected/cancelled.
 */
public final class EntityForceTeleport {
	private EntityForceTeleport() {}

	/**
	 * Tries Bukkit teleport first, then falls back to CraftBukkit/NMS reflection.
	 * Returns true if it believes the entity position was updated.
	 */
	public static boolean force(Entity entity, Location lock) {
		if (entity == null || lock == null) return false;

		try {
			if (lock.getWorld() != null && entity.getWorld() != null && !lock.getWorld().equals(entity.getWorld())) {
				return false;
			}
		} catch (Throwable ignored) {}

		try {
			// Paper teleport flags: retaining passengers is default since 1.21.10, but explicit is fine.
			if (entity.teleport(lock, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS)) return true;
		} catch (Throwable ignored) {}

		// Fallback to the Bukkit overload in case the Paper overload isn't available for any reason.
		try {
			if (entity.teleport(lock)) return true;
		} catch (Throwable ignored) {}

		return nms(entity, lock);
	}

	/**
	 * NMS/CraftBukkit reflection-only snap (no Bukkit teleport attempt).
	 * Useful when callers want to separately log/track teleport rejection.
	 */
	public static boolean nms(Entity entity, Location lock) {
		if (entity == null || lock == null) return false;

		try {
			java.lang.reflect.Method getHandle = entity.getClass().getMethod("getHandle");
			Object handle = getHandle.invoke(entity);
			if (handle == null) return false;

			boolean moved = false;

			// Position
			try {
				java.lang.reflect.Method setPos = handle.getClass().getMethod("setPos", double.class, double.class, double.class);
				setPos.invoke(handle, lock.getX(), lock.getY(), lock.getZ());
				moved = true;
			} catch (NoSuchMethodException ignored) {
				try {
					java.lang.reflect.Method setPosRaw = handle.getClass().getMethod("setPosRaw", double.class, double.class, double.class);
					setPosRaw.invoke(handle, lock.getX(), lock.getY(), lock.getZ());
					moved = true;
				} catch (Throwable ignored2) {}
			}

			// Rotation
			try {
				java.lang.reflect.Method setYRot = handle.getClass().getMethod("setYRot", float.class);
				setYRot.invoke(handle, lock.getYaw());
			} catch (Throwable ignored) {}
			try {
				java.lang.reflect.Method setXRot = handle.getClass().getMethod("setXRot", float.class);
				setXRot.invoke(handle, lock.getPitch());
			} catch (Throwable ignored) {}

			// Velocity
			try {
				java.lang.reflect.Method setDeltaMovement = handle.getClass().getMethod("setDeltaMovement", double.class, double.class, double.class);
				setDeltaMovement.invoke(handle, 0.0D, 0.0D, 0.0D);
			} catch (NoSuchMethodException ignored) {
				try {
					Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
					Object zero = vec3.getConstructor(double.class, double.class, double.class).newInstance(0.0D, 0.0D, 0.0D);
					java.lang.reflect.Method setDeltaMovement = handle.getClass().getMethod("setDeltaMovement", vec3);
					setDeltaMovement.invoke(handle, zero);
				} catch (Throwable ignored2) {}
			}

			try { entity.setRotation(lock.getYaw(), lock.getPitch()); } catch (Throwable ignored) {}

			return moved;
		} catch (Throwable ignored) {}

		return false;
	}
}

