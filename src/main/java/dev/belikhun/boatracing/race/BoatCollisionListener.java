package dev.belikhun.boatracing.race;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;

import dev.belikhun.boatracing.BoatRacingPlugin;

public class BoatCollisionListener implements Listener {
	private final BoatRacingPlugin plugin;

	public BoatCollisionListener(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(ignoreCancelled = true)
	public void onBoatBoatCollision(VehicleEntityCollisionEvent e) {
		if (plugin == null)
			return;
		var rs = plugin.getRaceService();
		if (rs == null)
			return;

		Entity vehicle = e.getVehicle();
		Entity other = e.getEntity();
		if (!isBoatLike(vehicle) || !isBoatLike(other))
			return;

		// Only suppress collision if at least one of the boats is used by a racer who
		// is actively in a race flow (intro/countdown/running).
		if (!isActiveRacingBoat(rs, vehicle) && !isActiveRacingBoat(rs, other))
			return;

		e.setCancelled(true);
	}

	private boolean isActiveRacingBoat(RaceService rs, Entity boat) {
		try {
			for (Entity passenger : boat.getPassengers()) {
				if (!(passenger instanceof Player p))
					continue;
				RaceManager rm = rs.findRaceFor(p.getUniqueId());
				if (rm == null)
					continue;

				boolean active;
				try {
					active = rm.isRunning() || rm.isIntroActive() || rm.isCountdownActiveFor(p.getUniqueId());
				} catch (Throwable ignored) {
					active = true;
				}

				if (active)
					return true;
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private boolean isBoatLike(Entity entity) {
		if (entity == null)
			return false;
		boolean boatLike = (entity instanceof org.bukkit.entity.Boat)
				|| (entity instanceof org.bukkit.entity.ChestBoat);
		if (boatLike)
			return true;
		try {
			String t = entity.getType() != null ? entity.getType().name() : null;
			return t != null && (t.endsWith("_BOAT") || t.endsWith("_CHEST_BOAT") || t.endsWith("_RAFT")
					|| t.endsWith("_CHEST_RAFT")
					|| t.equals("BOAT") || t.equals("CHEST_BOAT"));
		} catch (Throwable ignored) {
			return false;
		}
	}
}
