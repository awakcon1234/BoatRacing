package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

public class EventRegistrationNpcListener implements Listener {
	private final BoatRacingPlugin plugin;

	public EventRegistrationNpcListener(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	private EventRegistrationNpcService svc() {
		try {
			return plugin != null && plugin.getEventService() != null
					? plugin.getEventService().getRegistrationNpcService()
					: null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private void openRegistrationGui(Player p) {
		if (p == null)
			return;

		EventService es;
		try {
			es = plugin.getEventService();
		} catch (Throwable ignored) {
			es = null;
		}
		if (es == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			return;
		}

		try {
			if (plugin.getEventRegistrationGUI() != null) {
				plugin.getEventRegistrationGUI().open(p);
				return;
			}
		} catch (Throwable ignored) {
		}

		Text.msg(p, "&cKhông thể mở giao diện đăng ký sự kiện.");
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
	}

	@EventHandler(ignoreCancelled = true)
	public void onInteract(PlayerInteractAtEntityEvent e) {
		if (e == null)
			return;
		Player p = e.getPlayer();
		if (p == null)
			return;
		Entity clicked = e.getRightClicked();
		EventRegistrationNpcService rs = svc();
		if (rs == null || !rs.isRegisterNpcEntity(clicked))
			return;

		e.setCancelled(true);
		openRegistrationGui(p);
	}

	@EventHandler(ignoreCancelled = true)
	public void onDamage(EntityDamageByEntityEvent e) {
		if (e == null)
			return;
		EventRegistrationNpcService rs = svc();
		if (rs == null)
			return;
		if (!rs.isRegisterNpcEntity(e.getEntity()))
			return;
		e.setCancelled(true);
		if (e.getDamager() instanceof Player p)
			openRegistrationGui(p);
	}
}
