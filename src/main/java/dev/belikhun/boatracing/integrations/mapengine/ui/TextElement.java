package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;

/**
 * Single-line text element.
 */
public final class TextElement extends UiElement {
	public enum Align {
		LEFT, CENTER, RIGHT
	}

	private String text;
	private Font font;
	private Color color;
	private Align align = Align.LEFT;
	private boolean ellipsis = true;

	public TextElement(String text) {
		this.text = text;
	}

	public TextElement text(String t) { this.text = t; return this; }
	public TextElement font(Font f) { this.font = f; return this; }
	public TextElement color(Color c) { this.color = c; return this; }
	public TextElement align(Align a) { this.align = a == null ? Align.LEFT : a; return this; }
	public TextElement ellipsis(boolean e) { this.ellipsis = e; return this; }

	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		if (ctx == null || ctx.g == null) return UiMeasure.of(0, 0);
		Font use = font != null ? font : ctx.defaultFont;
		if (use != null) ctx.g.setFont(use);
		FontMetrics fm = ctx.g.getFontMetrics();
		String s = text == null ? "" : text;
		int w = Math.min(maxWidth, fm.stringWidth(s));
		int h = Math.min(maxHeight, fm.getAscent() + fm.getDescent());
		w += style.padding().horizontal();
		h += style.padding().vertical();
		return UiMeasure.of(w, h);
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		if (ctx == null || ctx.g == null) return;
		UiRect content = contentRect();
		Font use = font != null ? font : ctx.defaultFont;
		if (use != null) ctx.g.setFont(use);
		FontMetrics fm = ctx.g.getFontMetrics();
		String raw = text == null ? "" : text;
		String s = ellipsis ? trimToWidth(raw, fm, content.w()) : raw;

		int textW = fm.stringWidth(s);
		int x = content.x();
		if (align == Align.CENTER) x = content.x() + Math.max(0, (content.w() - textW) / 2);
		else if (align == Align.RIGHT) x = content.x() + Math.max(0, (content.w() - textW));

		int y = content.y() + fm.getAscent();
		ctx.g.setColor(color != null ? color : ctx.defaultTextColor);
		drawStringWithFallback(ctx, s, x, y, use);
	}

	private static String trimToWidth(String s, FontMetrics fm, int maxWidth) {
		if (s == null) return "";
		if (maxWidth <= 0) return "";
		if (fm.stringWidth(s) <= maxWidth) return s;
		String ell = "…";
		int ellW = fm.stringWidth(ell);
		if (ellW >= maxWidth) return "";
		int lo = 0;
		int hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			String sub = s.substring(0, mid);
			if (fm.stringWidth(sub) + ellW <= maxWidth) lo = mid;
			else hi = mid - 1;
		}
		return s.substring(0, lo) + ell;
	}

	private static void drawStringWithFallback(UiRenderContext ctx, String s, int x, int y, Font primary) {
		if (s == null || s.isEmpty()) return;
		Font fallback = ctx.fallbackFont;
		if (primary == null || fallback == null) {
			ctx.g.drawString(s, x, y);
			return;
		}

		int curX = x;
		StringBuilder run = new StringBuilder();
		Font curFont = primary;
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			Font want = primary.canDisplay(ch) ? primary : (fallback.canDisplay(ch) ? fallback : primary);
			if (!want.equals(curFont) && !run.isEmpty()) {
				ctx.g.setFont(curFont);
				ctx.g.drawString(run.toString(), curX, y);
				curX += ctx.g.getFontMetrics().stringWidth(run.toString());
				run.setLength(0);
				curFont = want;
			}
			if (!want.equals(curFont)) curFont = want;
			run.append(ch);
		}
		if (!run.isEmpty()) {
			ctx.g.setFont(curFont);
			ctx.g.drawString(run.toString(), curX, y);
		}
		ctx.g.setFont(primary);
	}
}
