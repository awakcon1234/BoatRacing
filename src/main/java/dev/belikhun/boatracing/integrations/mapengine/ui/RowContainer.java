package dev.belikhun.boatracing.integrations.mapengine.ui;

/**
 * Flex-like row container.
 */
public final class RowContainer extends UiContainer {
	private int gapPx = 0;
	private UiAlign alignItems = UiAlign.START;
	private UiJustify justifyContent = UiJustify.START;

	public RowContainer gap(int px) { this.gapPx = Math.max(0, px); return this; }
	public RowContainer alignItems(UiAlign align) { this.alignItems = align == null ? UiAlign.START : align; return this; }
	public RowContainer justifyContent(UiJustify j) { this.justifyContent = j == null ? UiJustify.START : j; return this; }

	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		int usableH = Math.max(0, maxHeight - style.padding().vertical());
		int totalW = 0;
		int maxChildH = 0;
		int count = 0;
		for (UiElement ch : children) {
			if (ch == null || !ch.style().display()) continue;
			UiInsets m = ch.style().margin();
			UiMeasure cm = ch.measure(ctx, Integer.MAX_VALUE, Math.max(0, usableH - m.vertical()));
			maxChildH = Math.max(maxChildH, cm.h() + m.vertical());
			totalW += cm.w() + m.horizontal();
			count++;
		}
		if (count > 1) totalW += gapPx * (count - 1);
		int w = style.padding().horizontal() + totalW;
		int h = style.padding().vertical() + maxChildH;
		return UiMeasure.of(Math.min(w, maxWidth), Math.min(h, maxHeight));
	}

	@Override
	protected void onLayout(UiRenderContext ctx, UiRect rect) {
		UiRect content = contentRect();
		int count = 0;
		int totalW = 0;
		int growSum = 0;
		UiElement lastGrow = null;
		for (UiElement ch : children) {
			if (ch == null || !ch.style().display()) continue;
			UiInsets m = ch.style().margin();
			UiMeasure cm = ch.measure(ctx, Integer.MAX_VALUE, Math.max(0, content.h() - m.vertical()));
			totalW += cm.w() + m.horizontal();
			int g = 0;
			try { g = ch.style().flexGrow(); } catch (Throwable ignored) { g = 0; }
			if (g > 0) {
				growSum += g;
				lastGrow = ch;
			}
			count++;
		}
		if (count > 1) totalW += gapPx * (count - 1);

		int remaining = Math.max(0, content.w() - totalW);
		boolean hasGrow = growSum > 0 && remaining > 0;

		int x = content.x();
		if (!hasGrow) {
			if (justifyContent == UiJustify.CENTER) x += Math.max(0, (content.w() - totalW) / 2);
			else if (justifyContent == UiJustify.END) x += Math.max(0, (content.w() - totalW));
		}

		int laidOut = 0;
		int allocatedExtra = 0;
		for (UiElement ch : children) {
			if (ch == null || !ch.style().display()) continue;
			UiInsets m = ch.style().margin();
			UiMeasure cm = ch.measure(ctx, Integer.MAX_VALUE, Math.max(0, content.h() - m.vertical()));

			int childH;
			if (alignItems == UiAlign.STRETCH && ch.style().heightPx() == null) {
				childH = Math.max(0, content.h() - m.vertical());
			} else {
				childH = Math.min(cm.h(), Math.max(0, content.h() - m.vertical()));
			}
			int childW = cm.w();
			if (hasGrow) {
				int g;
				try { g = ch.style().flexGrow(); } catch (Throwable ignored) { g = 0; }
				if (g > 0) {
					int extra;
					if (ch == lastGrow) {
						extra = Math.max(0, remaining - allocatedExtra);
					} else {
						extra = (int) (((long) remaining) * (long) g / (long) growSum);
						allocatedExtra += extra;
					}
					childW = Math.max(0, childW + extra);
				}
			}

			int y = content.y() + m.top();
			if (alignItems == UiAlign.CENTER) y = content.y() + Math.max(0, (content.h() - childH) / 2);
			else if (alignItems == UiAlign.END) y = content.y() + Math.max(0, (content.h() - childH)) - m.bottom();

			int cx = x + m.left();
			ch.layout(ctx, cx, y, childW, childH);
			x += m.left() + childW + m.right();
			laidOut++;
			if (laidOut < count) x += gapPx;
		}
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		for (UiElement ch : children) {
			if (ch != null && ch.style().display()) ch.render(ctx);
		}
	}
}
