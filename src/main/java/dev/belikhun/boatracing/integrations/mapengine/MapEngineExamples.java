package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IHoldableDisplay;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.util.Converter;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockVector;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Example snippets for MapEngine integration.
 *
 * These are intentionally not wired into commands yet; they are here as reference
 * (and to ensure our compileOnly integration stays valid).
 */
public final class MapEngineExamples {
    private MapEngineExamples() {}

    /**
     * Creates a single (128x128) holdable map display and returns the ItemStack to give to a player.
     *
     * Note: the caller is responsible for giving the item to the player inventory.
     */
    public static ItemStack createHoldableDemoItem(Player viewer) {
        if (viewer == null) return null;

        MapEngineApi api = MapEngineService.get();
        if (api == null) return null;

        IHoldableDisplay display = api.displayProvider().createHoldableDisplay();

        // Draw onto the map using a DrawingSpace.
        IDrawingSpace input = api.pipeline().createDrawingSpace(display);
        input.ctx().addReceiver(viewer);
        input.ctx().converter(Converter.FLOYD_STEINBERG);
        input.ctx().buffering(true);

        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x11, 0x11, 0x11));
            g.fillRect(0, 0, 128, 128);
            g.setColor(new Color(0xEE, 0xEE, 0xEE));
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString("BoatRacing", 18, 28);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString("MapEngine demo", 18, 48);
            g.setColor(new Color(0x22, 0x88, 0xFF));
            g.fillRect(16, 64, 96, 10);
        } finally {
            g.dispose();
        }

        input.image(image, 0, 0);
        input.flush();

        // z=0 for holdable displays (single layer).
        return display.itemStack(0);
    }

    /**
     * Creates a multi-map in-world display (item frames array). Call spawn(viewer) to show it.
     */
    public static IMapDisplay createFrameDisplay(BlockVector cornerA, BlockVector cornerB, BlockFace facing, Player viewer) {
        if (cornerA == null || cornerB == null || facing == null || viewer == null) return null;

        MapEngineApi api = MapEngineService.get();
        if (api == null) return null;

        IMapDisplay display = api.displayProvider().createBasic(cornerA, cornerB, facing);
        display.spawn(viewer);
        return display;
    }
}
