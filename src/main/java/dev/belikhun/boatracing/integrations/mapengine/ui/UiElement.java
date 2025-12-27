package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.BasicStroke;
import java.awt.Color;

public abstract class UiElement {
	protected final UiStyle style = new UiStyle();
	private UiRect layoutRect = new UiRect(0, 0, 0, 0);

	public UiStyle style() { return style; }

	public UiRect rect() { return layoutRect; }

	public final UiMeasure measure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		UiMeasure m = onMeasure(ctx, Math.max(0, maxWidth), Math.max(0, maxHeight));
		Integer forcedW = style.widthPx();
		Integer forcedH = style.heightPx();
		int w = forcedW != null ? Math.max(0, forcedW) : m.w();
		int h = forcedH != null ? Math.max(0, forcedH) : m.h();
		return UiMeasure.of(w, h);
	}

	protected abstract UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight);

	public final void layout(UiRenderContext ctx, int x, int y, int w, int h) {
		layoutRect = new UiRect(x, y, Math.max(0, w), Math.max(0, h));
		onLayout(ctx, layoutRect);
	}

	protected void onLayout(UiRenderContext ctx, UiRect rect) {
		// default: no-op
	}

	public final void render(UiRenderContext ctx) {
		paintBackgroundAndBorder(ctx);
		onRender(ctx);
	}

	protected void onRender(UiRenderContext ctx) {
		// default: no-op
	}

	protected final UiRect contentRect() {
		UiInsets p = style.padding();
		int x = layoutRect.x() + p.left();
		int y = layoutRect.y() + p.top();
		int w = Math.max(0, layoutRect.w() - p.horizontal());
		int h = Math.max(0, layoutRect.h() - p.vertical());
		return new UiRect(x, y, w, h);
	}

	protected void paintBackgroundAndBorder(UiRenderContext ctx) {
		if (ctx == null || ctx.g == null) return;
		Color bg = style.background();
		if (bg != null) {
			ctx.g.setColor(bg);
			ctx.g.fillRect(layoutRect.x(), layoutRect.y(), layoutRect.w(), layoutRect.h());
		}
		int bw = style.borderWidthPx();
		Color bc = style.borderColor();
		if (bw > 0 && bc != null) {
			ctx.g.setColor(bc);
			ctx.g.setStroke(new BasicStroke(bw));
			int half = bw / 2;
			ctx.g.drawRect(layoutRect.x() + half, layoutRect.y() + half, Math.max(0, layoutRect.w() - bw), Math.max(0, layoutRect.h() - bw));
		}
	}
}
