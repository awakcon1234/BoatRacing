package dev.belikhun.boatracing;

import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.util.Text;

public class SpawnCommand implements CommandExecutor {
	private final BoatRacingPlugin plugin;

	public SpawnCommand(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player p)) {
			Text.msg(sender, "&cChỉ dành cho người chơi.");
			return true;
		}

		if (!p.hasPermission("boatracing.spawn") && !p.hasPermission("boatracing.*")) {
			Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
			return true;
		}

		var rs = (plugin != null ? plugin.getRaceService() : null);
		RaceManager rm = (rs != null ? rs.findRaceFor(p.getUniqueId()) : null);

		boolean inLiveRace = false;
		try {
			if (rm != null) {
				inLiveRace = rm.isRunning() || rm.isCountdownActiveFor(p.getUniqueId()) || rm.isIntroActive();
			}
		} catch (Throwable ignored) {
			inLiveRace = rm != null;
		}

		if (inLiveRace) {
			try {
				var gui = (plugin != null ? plugin.getSpawnConfirmGUI() : null);
				if (gui != null) {
					gui.open(p);
					p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
					return true;
				}
			} catch (Throwable ignored) {
			}

			Text.msg(p, "&cBạn đang trong cuộc đua. Hãy dùng nút &f⎋ Rời cuộc đua &ctrên hotbar để về sảnh.");
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
			return true;
		}

		// Not racing: just teleport to lobby spawn.
		try {
			if (rs != null) {
				// If they were in registration (not "racing"), clean them up silently.
				try {
					rs.abandonNow(p, false);
				} catch (Throwable ignored) {
				}
			}

			org.bukkit.Location spawn = (plugin != null ? plugin.resolveLobbySpawn(p)
					: (p.getWorld() != null ? p.getWorld().getSpawnLocation() : null));
			if (spawn != null)
				p.teleport(spawn);
			p.setFallDistance(0f);

			Text.msg(p, "&aĐã dịch chuyển về sảnh.");
			p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
		} catch (Throwable ignored) {
			Text.msg(p, "&cKhông thể dịch chuyển về sảnh lúc này.");
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
		}

		return true;
	}
}
