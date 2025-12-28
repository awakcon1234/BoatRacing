package dev.belikhun.boatracing.race;

import dev.belikhun.boatracing.BoatRacingPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class RaceFireworkListener implements Listener {
	private final BoatRacingPlugin plugin;

	public RaceFireworkListener(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(ignoreCancelled = true)
	public void onFireworkDamage(EntityDamageByEntityEvent e) {
		Entity damager = e.getDamager();
		if (damager == null)
			return;
		if (!RaceFx.isRaceFirework(plugin, damager))
			return;
		e.setCancelled(true);
	}
}
