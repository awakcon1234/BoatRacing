package es.jaie55.boatracing.util.chat;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.team.Team;
import es.jaie55.boatracing.team.TeamManager;
import es.jaie55.boatracing.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class InputHandler implements Listener {
    private final BoatRacingPlugin plugin;
    private final Map<UUID, UUID> awaitingNumberForTeam = new HashMap<>();

    public InputHandler(BoatRacingPlugin plugin) {
        this.plugin = plugin;
    }

    public void awaitNumber(Player p, Team team) {
        awaitingNumberForTeam.put(p.getUniqueId(), team.getId());
    p.sendMessage(Text.colorize(plugin.pref() + "&eType a number between 1-99 for your race number."));
    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.6f);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        if (!awaitingNumberForTeam.containsKey(uid)) return;
        e.setCancelled(true);
        String msg = es.jaie55.boatracing.util.Text.plain(e.message()).trim();
        int num;
        try {
            num = Integer.parseInt(msg);
        } catch (NumberFormatException ex) {
            e.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&cPlease enter a valid number."));
            e.getPlayer().playSound(e.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        TeamManager tm = plugin.getTeamManager();
        UUID teamId = awaitingNumberForTeam.remove(uid);
        Team team = tm.getTeams().stream().filter(t -> t.getId().equals(teamId)).findFirst().orElse(null);
        if (team == null) return;
        if (!team.isMember(uid)) {
            e.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&cYou no longer belong to that team."));
            e.getPlayer().playSound(e.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        // Validate range 1-99
        if (num < 1 || num > 99) {
            e.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&cNumber must be between 1 and 99."));
            e.getPlayer().playSound(e.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        team.setRacerNumber(uid, num);
        tm.save();
        e.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&aYour racer # set to " + num + "."));
        e.getPlayer().playSound(e.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.3f);
    }
}
