package dev.belikhun.boatracing.integrations.mapengine.ui;

/**
 * Simple overlay container: all children are laid out to the same rect and rendered in order.
 */
public final class LayerContainer extends UiContainer {
	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		if (children.isEmpty())
			return UiMeasure.of(0, 0);
		int w = 0;
		int h = 0;
		for (UiElement c : children) {
			if (c == null)
				continue;
			UiMeasure m = c.measure(ctx, maxWidth, maxHeight);
			w = Math.max(w, m.w());
			h = Math.max(h, m.h());
		}
		return UiMeasure.of(Math.min(maxWidth, w), Math.min(maxHeight, h));
	}

	@Override
	protected void onLayout(UiRenderContext ctx, UiRect rect) {
		UiRect content = contentRect();
		for (UiElement c : children) {
			if (c == null)
				continue;
			c.layout(ctx, content.x(), content.y(), content.w(), content.h());
		}
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		for (UiElement c : children) {
			if (c == null)
				continue;
			c.render(ctx);
		}
	}
}
