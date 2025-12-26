package dev.belikhun.boatracing.util;

import org.bukkit.DyeColor;

/**
 * Centralized color formatting helpers.
 *
 * Keep all Bukkit {@link DyeColor} -> UI color mappings here so scoreboard/actionbar/chat
 * remain consistent across the plugin.
 */
public final class DyeColorFormats {
    private DyeColorFormats() {}

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
}
