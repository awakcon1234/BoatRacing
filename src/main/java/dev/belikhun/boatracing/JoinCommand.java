package dev.belikhun.boatracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.util.Text;

public class JoinCommand implements CommandExecutor, TabCompleter {
	private final BoatRacingPlugin plugin;

	public JoinCommand(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player p)) {
			Text.msg(sender, "&cChỉ dành cho người chơi.");
			return true;
		}

		if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
			Text.msg(p, "&cCách dùng: /" + label + " <track>");
			return true;
		}

		String tname = args[0];
		RaceManager rm = plugin.getRaceService().getOrCreate(tname);
		if (rm == null) {
			Text.msg(p, "&cTrack not found or failed to load: &f" + tname);
			return true;
		}
		if (!rm.getTrackConfig().isReady()) {
			Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", rm.getTrackConfig().missingRequirements()));
			return true;
		}

		// Tracks are open by default: joining auto-opens registration if needed.
		if (!plugin.getRaceService().join(tname, p)) {
			Text.msg(p, "&cKhông thể tham gia đăng ký lúc này.");
		}

		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args == null || args.length != 1)
			return Collections.emptyList();

		String prefix = args[0] == null ? "" : args[0].toLowerCase();
		List<String> names = new ArrayList<>();
		if (plugin.getTrackLibrary() != null) {
			for (String n : plugin.getTrackLibrary().list()) {
				if (n != null && n.toLowerCase().startsWith(prefix))
					names.add(n);
			}
		}
		return names;
	}
}
