package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Render context for UI composition.
 */
public final class UiRenderContext {
	public final Graphics2D g;
	public final Font defaultFont;
	public final Font fallbackFont;
	public final Color defaultTextColor;

	public UiRenderContext(Graphics2D g, Font defaultFont, Font fallbackFont, Color defaultTextColor) {
		this.g = g;
		this.defaultFont = defaultFont;
		this.fallbackFont = fallbackFont;
		this.defaultTextColor = defaultTextColor;
	}

	public void applyDefaultHints() {
		if (g == null) return;
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}
}
