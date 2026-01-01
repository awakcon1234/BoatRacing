package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;

/**
 * Wrapper container that applies alpha and translation to its single child.
 * Useful for transitions/animations.
 */
public final class FxContainer extends UiElement {
	private UiElement child;
	private float alpha = 1.0f;
	private int offsetXPx = 0;
	private int offsetYPx = 0;

	public FxContainer child(UiElement child) {
		this.child = child;
		return this;
	}

	public FxContainer alpha(double v) {
		double a = Math.max(0.0, Math.min(1.0, v));
		this.alpha = (float) a;
		return this;
	}

	public FxContainer offset(int xPx, int yPx) {
		this.offsetXPx = xPx;
		this.offsetYPx = yPx;
		return this;
	}

	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		if (child == null)
			return UiMeasure.of(0, 0);
		return child.measure(ctx, maxWidth, maxHeight);
	}

	@Override
	protected void onLayout(UiRenderContext ctx, UiRect rect) {
		if (child == null)
			return;
		UiRect content = contentRect();
		child.layout(ctx, content.x(), content.y(), content.w(), content.h());
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		if (ctx == null || ctx.g == null)
			return;
		if (child == null)
			return;

		Graphics2D g = ctx.g;
		Composite prev = g.getComposite();
		try {
			if (alpha < 1.0f) {
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			}
			if (offsetXPx != 0 || offsetYPx != 0) {
				g.translate(offsetXPx, offsetYPx);
			}
			child.render(ctx);
		} finally {
			try {
				if (offsetXPx != 0 || offsetYPx != 0) {
					g.translate(-offsetXPx, -offsetYPx);
				}
			} catch (Throwable ignored) {
			}
			try {
				g.setComposite(prev);
			} catch (Throwable ignored) {
			}
		}
	}
}
