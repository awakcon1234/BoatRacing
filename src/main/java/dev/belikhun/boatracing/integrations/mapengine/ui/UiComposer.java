package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Renders a UI tree into a BufferedImage (intended to be sent to MapEngine).
 */
public final class UiComposer {
	private UiComposer() {}

	public static BufferedImage render(UiElement root, int widthPx, int heightPx, Font defaultFont, Font fallbackFont) {
		int w = Math.max(1, widthPx);
		int h = Math.max(1, heightPx);

		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			UiRenderContext ctx = new UiRenderContext(
					g,
					defaultFont,
					fallbackFont,
					Color.WHITE
			);
			ctx.applyDefaultHints();

			if (defaultFont != null) g.setFont(defaultFont);
			if (root != null) {
				root.layout(ctx, 0, 0, w, h);
				root.render(ctx);
			}
		} finally {
			try { g.dispose(); } catch (Throwable ignored) {}
		}
		return img;
	}
}
