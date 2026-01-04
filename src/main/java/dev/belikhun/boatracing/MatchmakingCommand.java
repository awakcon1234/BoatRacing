package dev.belikhun.boatracing;

import dev.belikhun.boatracing.race.RaceService;
import dev.belikhun.boatracing.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MatchmakingCommand implements CommandExecutor {
	private final BoatRacingPlugin plugin;

	public MatchmakingCommand(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player p)) {
			Text.msg(sender, "&cChỉ dành cho người chơi.");
			return true;
		}

		RaceService rs = plugin.getRaceService();
		if (rs == null) {
			Text.msg(p, "&cDịch vụ đua chưa sẵn sàng.");
			return true;
		}

		boolean queued = rs.isInMatchmaking(p.getUniqueId());
		if (queued) {
			rs.matchmakingLeave(p);
		} else {
			rs.matchmakingJoin(p);
		}
		return true;
	}
}
