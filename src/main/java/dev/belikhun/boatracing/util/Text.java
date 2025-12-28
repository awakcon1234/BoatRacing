package dev.belikhun.boatracing.util;

import dev.belikhun.boatracing.BoatRacingPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.util.Locale;

import java.util.List;
import java.util.stream.Collectors;

public final class Text {
	private Text() {}

	private static final Locale LOCALE = Locale.US;

	private static String fmtCoord(double v) {
		double r = Math.rint(v);
		if (Math.abs(v - r) < 1e-9) return String.valueOf((long) r);
		return String.format(LOCALE, "%.1f", v);
	}

	public static String fmtPos(org.bukkit.Location loc) {
		if (loc == null) return "(unset)";
		String w = (loc.getWorld() != null ? loc.getWorld().getName() : "?");
		return w + " (" + fmtCoord(loc.getX()) + ", " + fmtCoord(loc.getY()) + ", " + fmtCoord(loc.getZ()) + ")";
	}

	public static String fmtBlock(org.bukkit.block.Block b) {
		if (b == null) return "(unset)";
		String w = (b.getWorld() != null ? b.getWorld().getName() : "?");
		return w + " (" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")";
	}

	public static String fmtArea(org.bukkit.util.BoundingBox box) {
		if (box == null) return "[unset]";
		return "[" + fmtCoord(box.getMinX()) + ", " + fmtCoord(box.getMinY()) + ", " + fmtCoord(box.getMinZ())
				+ " -> " + fmtCoord(box.getMaxX()) + ", " + fmtCoord(box.getMaxY()) + ", " + fmtCoord(box.getMaxZ()) + "]";
	}

	public static String fmtArea(String worldName, org.bukkit.util.BoundingBox box) {
		String w = (worldName == null || worldName.isEmpty()) ? "?" : worldName;
		return w + " " + fmtArea(box);
	}

	public static String fmtArea(dev.belikhun.boatracing.track.Region region) {
		if (region == null) return "(unset)";
		return fmtArea(region.getWorldName(), region.getBox());
	}

	// String-based color (for quick sendMessage(String))
	public static String colorize(String s) {
		if (s == null) return "";
		return s.replace('&', '§');
	}

	// Centralized prefix provider
	public static String prefix() {
		BoatRacingPlugin pl = BoatRacingPlugin.getInstance();
		return pl != null ? pl.pref() : colorize("&6[BoatRacing] ");
	}

	// Send a colored, prefixed message to any CommandSender
	public static void msg(CommandSender to, String legacyAmpersand) {
		if (to == null) return;
		String base = legacyAmpersand == null ? "" : legacyAmpersand;
		to.sendMessage(colorize(prefix() + base));
	}

	// Send a colored message without prefix (utility for list/help lines)
	public static void tell(CommandSender to, String legacyAmpersand) {
		if (to == null) return;
		String base = legacyAmpersand == null ? "" : legacyAmpersand;
		to.sendMessage(colorize(base));
	}

	// Send a Component (Adventure) message
	public static void send(CommandSender to, Component component) {
		if (to == null || component == null) return;
		to.sendMessage(component);
	}

	// Components (Adventure) from &-codes
	public static Component c(String legacyAmpersand) {
		if (legacyAmpersand == null) return Component.empty();
		return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyAmpersand);
	}

	public static List<Component> lore(List<String> lines) {
		if (lines == null) return java.util.Collections.emptyList();
		return lines.stream()
			.map((String s) -> Text.c("&r" + s).decoration(TextDecoration.ITALIC, false))
			.collect(Collectors.toList());
	}

	public static String plain(Component component) {
		if (component == null) return "";
		return PlainTextComponentSerializer.plainText().serialize(component);
	}

	// Inventory titles: vanilla style by resetting with &r
	public static Component title(String text) {
		if (text == null) text = "";
		return c("&r" + text).decoration(TextDecoration.ITALIC, false);
	}

	// Item names: vanilla style by resetting with &r
	public static Component item(String text) {
		if (text == null) text = "";
		return c("&r" + text).decoration(TextDecoration.ITALIC, false);
	}

	// Clickable command helper: blue label, suggest command on click, hover shows the command
	public static Component cmd(String labelLegacy, String command) {
		if (labelLegacy == null) labelLegacy = "";
		if (command == null) command = "";
		Component base = c(labelLegacy).decoration(TextDecoration.ITALIC, false);
		return base
			.clickEvent(ClickEvent.suggestCommand(command))
			.hoverEvent(HoverEvent.showText(c("&7Bấm để dán: &b" + command)));
	}
}

