package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;

import dev.belikhun.boatracing.util.ColorTranslator;

/**
 * Single-line text element that supports Minecraft legacy formatting codes
 * (colors + bold/italic) using either '&' or '§'.
 */
public final class LegacyTextElement extends UiElement {
	public enum Align {
		LEFT, CENTER, RIGHT
	}

	private String text;
	private Font font;
	private Color defaultColor;
	private Align align = Align.LEFT;
	private boolean trimToFit = true;

	public LegacyTextElement(String text) {
		this.text = text;
	}

	public LegacyTextElement text(String t) { this.text = t; return this; }
	public LegacyTextElement font(Font f) { this.font = f; return this; }
	public LegacyTextElement defaultColor(Color c) { this.defaultColor = c; return this; }
	public LegacyTextElement align(Align a) { this.align = a == null ? Align.LEFT : a; return this; }
	public LegacyTextElement trimToFit(boolean v) { this.trimToFit = v; return this; }

	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		if (ctx == null || ctx.g == null) return UiMeasure.of(0, 0);
		Font use = font != null ? font : ctx.defaultFont;
		if (use != null) ctx.g.setFont(use);
		FontMetrics fm = ctx.g.getFontMetrics();
		int h = Math.min(maxHeight, fm.getAscent() + fm.getDescent());

		String s = (text == null ? "" : text);
		int contentW = Math.max(0, maxWidth - style.padding().horizontal());
		if (trimToFit) {
			s = trimLegacyToWidthWithFallback(ctx.g, s, contentW, use, ctx.fallbackFont);
		}
		int w = legacyRenderedWidthWithFallback(ctx.g, s, use, ctx.fallbackFont);
		w = Math.min(maxWidth, w + style.padding().horizontal());
		h = Math.min(maxHeight, h + style.padding().vertical());
		return UiMeasure.of(w, h);
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		if (ctx == null || ctx.g == null) return;
		UiRect content = contentRect();
		Font use = font != null ? font : ctx.defaultFont;
		if (use != null) ctx.g.setFont(use);
		FontMetrics fm = ctx.g.getFontMetrics();

		Color base = (defaultColor != null ? defaultColor : ctx.defaultTextColor);
		String raw = (text == null ? "" : text);
		String s = trimToFit ? trimLegacyToWidthWithFallback(ctx.g, raw, content.w(), use, ctx.fallbackFont) : raw;

		int textW = legacyRenderedWidthWithFallback(ctx.g, s, use, ctx.fallbackFont);
		int x = content.x();
		if (align == Align.CENTER) x = content.x() + Math.max(0, (content.w() - textW) / 2);
		else if (align == Align.RIGHT) x = content.x() + Math.max(0, (content.w() - textW));

		int y = content.y() + fm.getAscent();
		drawLegacyStringWithFallback(ctx.g, s, x, y, use, ctx.fallbackFont, base);
	}

	// ===================== Legacy color rendering =====================
	// Supports Minecraft legacy formatting codes using either '&' or '§'.
	// We implement color + bold/italic and ignore other formatting codes.

	private static int legacyRenderedWidthWithFallback(Graphics2D g, String s, Font baseFont, Font fallbackFont) {
		if (g == null) return 0;
		if (s == null || s.isEmpty()) return 0;
		Font base = (baseFont != null ? baseFont : g.getFont());
		int baseStyle = base.getStyle();

		boolean bold = false;
		boolean italic = false;

		int w = 0;
		for (int i = 0; i < s.length();) {
			char ch = s.charAt(i);
			if ((ch == '&' || ch == '§') && (i + 1) < s.length()) {
				char code = s.charAt(i + 1);
				char lc = Character.toLowerCase(code);
				Color c = ColorTranslator.legacyChatColorToAwt(lc);
				if (c != null) {
					bold = false;
					italic = false;
				} else if (lc == 'r') {
					bold = false;
					italic = false;
				} else if (lc == 'l') {
					bold = true;
				} else if (lc == 'o') {
					italic = true;
				}
				i += 2;
				continue;
			}

			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);
			int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);

			Font useBase;
			try {
				useBase = base.deriveFont(style);
			} catch (Throwable ignored) {
				useBase = base;
			}

			Font useFallback = (fallbackFont != null ? fallbackFont : monoMatch(useBase));
			try {
				useFallback = useFallback.deriveFont(style, useBase.getSize2D());
			} catch (Throwable ignored) {
			}

			boolean canPrimary;
			try {
				canPrimary = useBase.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}
			Font use = canPrimary ? useBase : useFallback;
			try {
				w += g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp)));
			} catch (Throwable ignored) {
			}
			i += len;
		}
		return w;
	}

	private static String trimLegacyToWidthWithFallback(Graphics2D g, String s, int maxWidth, Font baseFont,
			Font fallbackFont) {
		if (g == null) return "";
		if (s == null || s.isEmpty()) return "";
		if (maxWidth <= 0) return "";

		Font base = (baseFont != null ? baseFont : g.getFont());
		int baseStyle = base.getStyle();

		boolean bold = false;
		boolean italic = false;
		int w = 0;
		int cutIndex = 0;

		for (int i = 0; i < s.length();) {
			char ch = s.charAt(i);
			if ((ch == '&' || ch == '§') && (i + 1) < s.length()) {
				char code = s.charAt(i + 1);
				char lc = Character.toLowerCase(code);
				Color c = ColorTranslator.legacyChatColorToAwt(lc);
				if (c != null) {
					bold = false;
					italic = false;
				} else if (lc == 'r') {
					bold = false;
					italic = false;
				} else if (lc == 'l') {
					bold = true;
				} else if (lc == 'o') {
					italic = true;
				}
				i += 2;
				cutIndex = i;
				continue;
			}

			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);
			int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);

			Font useBase;
			try {
				useBase = base.deriveFont(style);
			} catch (Throwable ignored) {
				useBase = base;
			}

			Font useFallback = (fallbackFont != null ? fallbackFont : monoMatch(useBase));
			try {
				useFallback = useFallback.deriveFont(style, useBase.getSize2D());
			} catch (Throwable ignored) {
			}

			boolean canPrimary;
			try {
				canPrimary = useBase.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}
			Font use = canPrimary ? useBase : useFallback;

			int cw;
			try {
				cw = g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp)));
			} catch (Throwable ignored) {
				cw = 0;
			}

			if (w + cw > maxWidth) break;
			w += cw;
			i += len;
			cutIndex = i;
		}

		if (cutIndex <= 0) return "";
		return s.substring(0, Math.min(cutIndex, s.length()));
	}

	private static void drawLegacyStringWithFallback(Graphics2D g, String s, int x, int y, Font baseFont,
			Font fallbackFont, Color defaultColor) {
		if (g == null) return;
		if (s == null || s.isEmpty()) return;

		Font base = (baseFont != null ? baseFont : g.getFont());
		int baseStyle = base.getStyle();
		Font baseFallback = (fallbackFont != null ? fallbackFont : monoMatch(base));

		boolean bold = false;
		boolean italic = false;
		Color curColor = (defaultColor != null ? defaultColor : Color.WHITE);

		int cx = x;
		StringBuilder run = new StringBuilder();
		Color runColor = curColor;
		int runStyle = baseStyle;

		for (int i = 0; i < s.length();) {
			char ch = s.charAt(i);
			if ((ch == '&' || ch == '§') && (i + 1) < s.length()) {
				char code = s.charAt(i + 1);
				char lc = Character.toLowerCase(code);

				Color nextColor = ColorTranslator.legacyChatColorToAwt(lc);
				boolean styleChanged = false;

				if (nextColor != null) {
					bold = false;
					italic = false;
					curColor = nextColor;
					styleChanged = true;
				} else if (lc == 'r') {
					bold = false;
					italic = false;
					curColor = (defaultColor != null ? defaultColor : Color.WHITE);
					styleChanged = true;
				} else if (lc == 'l') {
					if (!bold) styleChanged = true;
					bold = true;
				} else if (lc == 'o') {
					if (!italic) styleChanged = true;
					italic = true;
				} else {
					// Ignore other formats (k, n, m, etc.)
				}

				int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);

				if (styleChanged) {
					if (!run.isEmpty()) {
						cx += flushLegacyRunWithFallback(g, run, cx, y, base, baseFallback, runColor, runStyle);
					}
					runColor = curColor;
					runStyle = style;
				}

				i += 2;
				continue;
			}

			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);

			int style = baseStyle | (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
			if (runColor != curColor || runStyle != style) {
				if (!run.isEmpty()) {
					cx += flushLegacyRunWithFallback(g, run, cx, y, base, baseFallback, runColor, runStyle);
				}
				runColor = curColor;
				runStyle = style;
			}

			run.appendCodePoint(cp);
			i += len;
		}

		if (!run.isEmpty()) {
			flushLegacyRunWithFallback(g, run, cx, y, base, baseFallback, runColor, runStyle);
		}
	}

	private static int flushLegacyRunWithFallback(Graphics2D g, StringBuilder run, int x, int y, Font baseFont,
			Font fallbackBase, Color color, int style) {
		if (run == null || run.isEmpty()) return 0;
		int w = drawLegacyRunWithFallback(g, run.toString(), x, y, baseFont, fallbackBase, color, style);
		try { run.setLength(0); } catch (Throwable ignored) {}
		return w;
	}

	private static int drawLegacyRunWithFallback(Graphics2D g, String text, int x, int y, Font baseFont,
			Font fallbackBase, Color color, int style) {
		if (g == null) return 0;
		if (text == null || text.isEmpty()) return 0;

		Font base = (baseFont != null ? baseFont : g.getFont());
		Font fallback = (fallbackBase != null ? fallbackBase : monoMatch(base));

		Font runFont;
		try {
			runFont = base.deriveFont(style);
		} catch (Throwable ignored) {
			runFont = base;
		}

		Font runFallback = fallback;
		try {
			runFallback = runFallback.deriveFont(style, runFont.getSize2D());
		} catch (Throwable ignored) {
		}

		try {
			g.setColor(color != null ? color : Color.WHITE);
		} catch (Throwable ignored) {
		}

		drawStringWithFallback(g, text, x, y, runFont, runFallback);
		return stringWidthWithFallback(g, text, runFont, runFallback);
	}

	private static int stringWidthWithFallback(Graphics2D g, String s, Font primary, Font fallback) {
		if (g == null) return 0;
		if (s == null || s.isEmpty()) return 0;
		Font p = (primary != null ? primary : g.getFont());
		Font f = (fallback != null ? fallback : monoMatch(p));

		int w = 0;
		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);
			boolean canPrimary;
			try {
				canPrimary = p != null && p.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}
			Font use = canPrimary ? p : f;
			try {
				w += g.getFontMetrics(use).stringWidth(new String(Character.toChars(cp)));
			} catch (Throwable ignored) {
			}
			i += len;
		}
		return w;
	}

	private static void drawStringWithFallback(Graphics2D g, String s, int x, int y, Font primary, Font fallback) {
		if (g == null) return;
		if (s == null || s.isEmpty()) return;
		Font p = (primary != null ? primary : g.getFont());
		Font f = (fallback != null ? fallback : monoMatch(p));

		// Ensure fallback matches size/style.
		try {
			f = f.deriveFont(p.getStyle(), p.getSize2D());
		} catch (Throwable ignored) {
		}

		int cx = x;
		StringBuilder run = new StringBuilder();
		Font runFont = null;

		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			int len = Character.charCount(cp);

			boolean canPrimary;
			try {
				canPrimary = p != null && p.canDisplay(cp);
			} catch (Throwable ignored) {
				canPrimary = true;
			}

			Font use = canPrimary ? p : f;
			if (runFont == null) runFont = use;

			if (use != runFont) {
				if (!run.isEmpty()) {
					g.setFont(runFont);
					g.drawString(run.toString(), cx, y);
					cx += g.getFontMetrics(runFont).stringWidth(run.toString());
					run.setLength(0);
				}
				runFont = use;
			}

			run.appendCodePoint(cp);
			i += len;
		}

		if (!run.isEmpty()) {
			g.setFont(runFont);
			g.drawString(run.toString(), cx, y);
		}
	}

	private static Font monoMatch(Font f) {
		if (f == null) return new Font(Font.MONOSPACED, Font.PLAIN, 12);
		return new Font(Font.MONOSPACED, f.getStyle(), f.getSize());
	}
}
