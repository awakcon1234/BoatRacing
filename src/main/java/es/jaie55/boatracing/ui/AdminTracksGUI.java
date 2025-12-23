package es.jaie55.boatracing.ui;

import org.bukkit.event.Listener;
import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.track.TrackLibrary;

/**
 * Minimal admin tracks GUI placeholder to satisfy compilation.
 */
public class AdminTracksGUI implements Listener {
    public AdminTracksGUI(BoatRacingPlugin plugin, TrackLibrary trackLibrary) {
        // no-op
    }

    public void open(org.bukkit.entity.Player p) {
        p.sendMessage(es.jaie55.boatracing.util.Text.colorize("&7(Tracks UI placeholder)"));
    }
}
