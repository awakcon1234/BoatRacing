package dev.belikhun.boatracing.integrations.mapengine.ui;

import de.pianoman911.mapengine.api.drawing.IDrawingSpace;

import java.awt.Font;
import java.awt.image.BufferedImage;

/**
 * Thin adapter: render a UI tree to an image and push to a MapEngine drawing space.
 *
 * This makes it trivial to manage multiple boards by owning multiple instances of this class.
 */
public final class MapEngineUiBoard {
	private final IDrawingSpace drawing;
	private final int widthPx;
	private final int heightPx;
	private final Font defaultFont;
	private final Font fallbackFont;

	public MapEngineUiBoard(IDrawingSpace drawing, int widthPx, int heightPx, Font defaultFont, Font fallbackFont) {
		this.drawing = drawing;
		this.widthPx = widthPx;
		this.heightPx = heightPx;
		this.defaultFont = defaultFont;
		this.fallbackFont = fallbackFont;
	}

	public void renderAndFlush(UiElement root) {
		if (drawing == null) return;
		BufferedImage img = UiComposer.render(root, widthPx, heightPx, defaultFont, fallbackFont);
		drawing.image(img, 0, 0);
		drawing.flush();
	}
}
