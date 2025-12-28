package dev.belikhun.boatracing.integrations.mapengine.ui;

/**
 * Flex-like column container.
 */
public final class ColumnContainer extends UiContainer {
	private int gapPx = 0;
	private UiAlign alignItems = UiAlign.START;
	private UiJustify justifyContent = UiJustify.START;

	public ColumnContainer gap(int px) { this.gapPx = Math.max(0, px); return this; }
	public ColumnContainer alignItems(UiAlign align) { this.alignItems = align == null ? UiAlign.START : align; return this; }
	public ColumnContainer justifyContent(UiJustify j) { this.justifyContent = j == null ? UiJustify.START : j; return this; }

	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		UiRect content = new UiRect(0, 0, maxWidth, maxHeight);
		int usableW = Math.max(0, content.w() - style.padding().horizontal());
		int totalH = 0;
		int maxChildW = 0;
		int count = 0;
		for (UiElement ch : children) {
			if (ch == null || !ch.style().display()) continue;
			UiInsets m = ch.style().margin();
			UiMeasure cm = ch.measure(ctx, Math.max(0, usableW - m.horizontal()), Integer.MAX_VALUE);
			maxChildW = Math.max(maxChildW, cm.w() + m.horizontal());
			totalH += cm.h() + m.vertical();
			count++;
		}
		if (count > 1) totalH += gapPx * (count - 1);
		int w = style.padding().horizontal() + maxChildW;
		int h = style.padding().vertical() + totalH;
		return UiMeasure.of(Math.min(w, maxWidth), Math.min(h, maxHeight));
	}

	@Override
	protected void onLayout(UiRenderContext ctx, UiRect rect) {
		UiRect content = contentRect();
		int count = 0;
		int totalH = 0;
		for (UiElement ch : children) {
			if (ch == null || !ch.style().display()) continue;
			UiInsets m = ch.style().margin();
			UiMeasure cm = ch.measure(ctx, Math.max(0, content.w() - m.horizontal()), Integer.MAX_VALUE);
			totalH += cm.h() + m.vertical();
			count++;
		}
		if (count > 1) totalH += gapPx * (count - 1);

		int y = content.y();
		if (justifyContent == UiJustify.CENTER) y += Math.max(0, (content.h() - totalH) / 2);
		else if (justifyContent == UiJustify.END) y += Math.max(0, (content.h() - totalH));

		int laidOut = 0;
		for (UiElement ch : children) {
			if (ch == null || !ch.style().display()) continue;
			UiInsets m = ch.style().margin();
			UiMeasure cm = ch.measure(ctx, Math.max(0, content.w() - m.horizontal()), Integer.MAX_VALUE);

			int childW;
			if (alignItems == UiAlign.STRETCH && ch.style().widthPx() == null) {
				childW = Math.max(0, content.w() - m.horizontal());
			} else {
				childW = Math.min(cm.w(), Math.max(0, content.w() - m.horizontal()));
			}
			int childH = cm.h();

			int x = content.x() + m.left();
			if (alignItems == UiAlign.CENTER) x = content.x() + Math.max(0, (content.w() - childW) / 2);
			else if (alignItems == UiAlign.END) x = content.x() + Math.max(0, (content.w() - childW)) - m.right();

			int cy = y + m.top();
			ch.layout(ctx, x, cy, childW, childH);
			y += m.top() + childH + m.bottom();
			laidOut++;
			if (laidOut < count) y += gapPx;
		}
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		for (UiElement ch : children) {
			if (ch != null && ch.style().display()) ch.render(ctx);
		}
	}
}
