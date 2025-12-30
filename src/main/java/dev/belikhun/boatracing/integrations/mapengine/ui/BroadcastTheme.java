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
		public Color accentSoft(int alpha) {
			Color a = accent;
			if (a == null)
				return null;
			int al = Math.max(0, Math.min(255, alpha));
			return new Color(a.getRed(), a.getGreen(), a.getBlue(), al);
		}

		public Color textDimSoft(int alpha) {
			Color a = textDim;
			if (a == null)
				return null;
			int al = Math.max(0, Math.min(255, alpha));
			return new Color(a.getRed(), a.getGreen(), a.getBlue(), al);
		}

		public Color panelTint(double t) {
			return mix(panel2, accent, t);
		}
	}

	// Base colors (warm, natural dark UI)
	public static final Color BASE_BG0 = new Color(0x10, 0x0F, 0x0D);
	public static final Color BASE_PANEL = new Color(0x18, 0x16, 0x13);
	public static final Color BASE_PANEL2 = new Color(0x1D, 0x1A, 0x16);
	public static final Color BASE_BORDER = new Color(0x2F, 0x2B, 0x25);
	public static final Color BASE_TEXT = new Color(0xFF, 0xFF, 0xFF);
	public static final Color BASE_TEXT_DIM = new Color(0xB0, 0xA8, 0x9E);

	// Accent colors (mirrors LobbyBoard status accents)
	public static final Color ACCENT_RUNNING = new Color(0x56, 0xF2, 0x7A);
	public static final Color ACCENT_COUNTDOWN = new Color(0xFF, 0xB8, 0x4D);
	public static final Color ACCENT_REGISTERING = new Color(0xFF, 0xD7, 0x5E);
	public static final Color ACCENT_READY = new Color(0x5E, 0xA8, 0xFF);
	public static final Color ACCENT_OFF = new Color(0x8A, 0x8A, 0x8A);

	public static Palette palette(Color accent) {
		Color a = accent != null ? accent : ACCENT_OFF;
		return new Palette(
				BASE_BG0,
				BASE_PANEL,
				BASE_PANEL2,
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
