package es.jaie55.boatracing.ui;

import org.bukkit.event.Listener;
import es.jaie55.boatracing.BoatRacingPlugin;

/**
 * Minimal admin GUI placeholder to satisfy compilation.
 */
public class AdminGUI implements Listener {
    public AdminGUI(BoatRacingPlugin plugin) {
        // no-op
    }

    public void openMain(org.bukkit.entity.Player p) {
        p.sendMessage(es.jaie55.boatracing.util.Text.colorize("&7(Admin UI placeholder)"));
    }
}
