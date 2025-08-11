package es.jaie55.boatracing.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.stream.Collectors;

public final class Text {
    private Text() {}

    // String-based color (for quick sendMessage(String))
    public static String colorize(String s) {
        if (s == null) return "";
        return s.replace('&', '§');
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
}
