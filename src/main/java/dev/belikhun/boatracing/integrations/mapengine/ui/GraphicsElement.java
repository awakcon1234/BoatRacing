package dev.belikhun.boatracing.integrations.mapengine.ui;

/**
 * A bridge element that allows incremental migrations from imperative Graphics2D drawing
 * into the UI composer.
 */
public final class GraphicsElement extends UiElement {
	@FunctionalInterface
	public interface Painter {
		void paint(UiRenderContext ctx, UiRect rect);
	}

	private final Painter painter;

	public GraphicsElement(Painter painter) {
		this.painter = painter;
	}

	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		return UiMeasure.of(maxWidth, maxHeight);
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		if (painter == null) return;
		painter.paint(ctx, rect());
	}
}
