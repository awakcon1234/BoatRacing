package dev.belikhun.boatracing.util;

import org.bukkit.DyeColor;

import java.awt.Color;

/**
 * Centralized color formatting helpers.
 *
 * Keep all Bukkit {@link DyeColor} -> UI color mappings here so scoreboard/actionbar/chat
 * remain consistent across the plugin.
 */
public final class ColorTranslator {
    private ColorTranslator() {}

    /**
     * Shared mapping for Bukkit {@link DyeColor} -> MiniMessage color tag.
     */
    public static String miniColorTag(DyeColor dc) {
        if (dc == null) return "<white>";
        return switch (dc) {
            case WHITE -> "<white>";
            case ORANGE -> "<gold>";
            case MAGENTA -> "<light_purple>";
            case LIGHT_BLUE -> "<aqua>";
            case YELLOW -> "<yellow>";
            case LIME -> "<green>";
            case PINK -> "<light_purple>";
            case GRAY -> "<dark_gray>";
            case LIGHT_GRAY -> "<gray>";
            case CYAN -> "<dark_aqua>";
            case PURPLE -> "<dark_purple>";
            case BLUE -> "<dark_blue>";
            case BROWN -> "<gold>";
            case GREEN -> "<dark_green>";
            case RED -> "<red>";
            case BLACK -> "<black>";
            default -> "<white>";
        };
    }

    /**
     * Shared mapping for Bukkit {@link DyeColor} -> legacy (&-code) color prefix.
     */
    public static String legacyColorCode(DyeColor dc) {
        if (dc == null) return "&f";
        return switch (dc) {
            case WHITE -> "&f";
            case ORANGE -> "&6";
            case MAGENTA -> "&d";
            case LIGHT_BLUE -> "&b";
            case YELLOW -> "&e";
            case LIME -> "&a";
            case PINK -> "&d";
            case GRAY -> "&8";
            case LIGHT_GRAY -> "&7";
            case CYAN -> "&3";
            case PURPLE -> "&5";
            case BLUE -> "&1";
            case BROWN -> "&6";
            case GREEN -> "&2";
            case RED -> "&c";
            case BLACK -> "&0";
            default -> "&f";
        };
    }

    /**
     * Map Bukkit DyeColor to a stable java.awt.Color for UI rendering (MapEngine lobby board).
     * Note: these are approximate "Minecraft-like" tones, not exact map palette indices.
     */
    public static Color awtColor(DyeColor color) {
        if (color == null) color = DyeColor.WHITE;
        return switch (color) {
            case WHITE -> new Color(0xF0F0F0);
            case ORANGE -> new Color(0xF9801D);
            case MAGENTA -> new Color(0xC74EBD);
            case LIGHT_BLUE -> new Color(0x3AB3DA);
            case YELLOW -> new Color(0xFED83D);
            case LIME -> new Color(0x80C71F);
            case PINK -> new Color(0xF38BAA);
            case GRAY -> new Color(0x474F52);
            case LIGHT_GRAY -> new Color(0x9D9D97);
            case CYAN -> new Color(0x169C9C);
            case PURPLE -> new Color(0x8932B8);
            case BLUE -> new Color(0x3C44AA);
            case BROWN -> new Color(0x835432);
            case GREEN -> new Color(0x5E7C16);
            case RED -> new Color(0xB02E26);
            case BLACK -> new Color(0x1D1D21);
        };
    }

    /**
     * Translate a legacy Minecraft chat color code (0-9,a-f) to java.awt.Color.
     *
     * Accepts either the code itself (e.g. 'a') or the second char after '&'/'§'.
     * Returns null for non-color codes.
     */
    public static Color legacyChatColorToAwt(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> new Color(0x000000);
            case '1' -> new Color(0x0000AA);
            case '2' -> new Color(0x00AA00);
            case '3' -> new Color(0x00AAAA);
            case '4' -> new Color(0xAA0000);
            case '5' -> new Color(0xAA00AA);
            case '6' -> new Color(0xFFAA00);
            case '7' -> new Color(0xAAAAAA);
            case '8' -> new Color(0x555555);
            case '9' -> new Color(0x5555FF);
            case 'a' -> new Color(0x55FF55);
            case 'b' -> new Color(0x55FFFF);
            case 'c' -> new Color(0xFF5555);
            case 'd' -> new Color(0xFF55FF);
            case 'e' -> new Color(0xFFFF55);
            case 'f' -> new Color(0xFFFFFF);
            default -> null;
        };
    }
}
