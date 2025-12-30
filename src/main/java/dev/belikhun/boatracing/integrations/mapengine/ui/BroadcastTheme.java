package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Color;

/**
 * Shared "broadcast" palette for MapEngine boards.
 *
 * Intentionally small: provides a dark base theme plus an accent color.
 */
public final class BroadcastTheme {
	private BroadcastTheme() {
	}

	public record Palette(
			Color bg0,
			Color panel,
			Color panel2,
			Color border,
			Color accent,
			Color text,
			Color textDim) {
	}

	// Base colors (dark UI)
	public static final Color BASE_BG0 = new Color(0x0E, 0x10, 0x12);
	public static final Color BASE_PANEL = new Color(0x14, 0x16, 0x1A);
	public static final Color BASE_PANEL2 = new Color(0x12, 0x14, 0x17);
	public static final Color BASE_BORDER = new Color(0x3A, 0x3A, 0x3A);
	public static final Color BASE_TEXT = new Color(0xFF, 0xFF, 0xFF);
	public static final Color BASE_TEXT_DIM = new Color(0xA6, 0xA6, 0xA6);

	// Accent colors (mirrors LobbyBoard status accents)
	public static final Color ACCENT_RUNNING = new Color(0x56, 0xF2, 0x7A);
	public static final Color ACCENT_COUNTDOWN = new Color(0xFF, 0xB8, 0x4D);
	public static final Color ACCENT_REGISTERING = new Color(0xFF, 0xD7, 0x5E);
	public static final Color ACCENT_READY = new Color(0x5E, 0xA8, 0xFF);
	public static final Color ACCENT_OFF = new Color(0x8A, 0x8A, 0x8A);

	public static Palette palette(Color accent) {
		Color a = accent != null ? accent : ACCENT_OFF;
		return new Palette(
				mix(BASE_BG0, a, 0.10),
				mix(BASE_PANEL, a, 0.07),
				mix(BASE_PANEL2, a, 0.08),
				BASE_BORDER,
				a,
				BASE_TEXT,
				BASE_TEXT_DIM);
	}

	public static Color mix(Color a, Color b, double t) {
		if (a == null)
			return b;
		if (b == null)
			return a;
		double k = Math.max(0.0, Math.min(1.0, t));
		int r = (int) Math.round(a.getRed() * (1.0 - k) + b.getRed() * k);
		int g = (int) Math.round(a.getGreen() * (1.0 - k) + b.getGreen() * k);
		int bl = (int) Math.round(a.getBlue() * (1.0 - k) + b.getBlue() * k);
		return new Color(clamp(r), clamp(g), clamp(bl));
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
