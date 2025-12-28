package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Color;

/**
 * Small CSS-like style bag. All lengths are pixels.
 */
public final class UiStyle {
	// When false, the element is removed from both rendering and layout (takes no space).
	private boolean display = true;
	private UiInsets margin = UiInsets.none();
	private UiInsets padding = UiInsets.none();
	private Integer widthPx;
	private Integer heightPx;
	// Minimal flexbox-like grow factor (main-axis only, handled by RowContainer/ColumnContainer).
	// 0 = no grow (default). Any positive value participates in distributing remaining space.
	private int flexGrow = 0;
	private Color background;
	private Color borderColor;
	private int borderWidthPx = 0;

	public boolean display() { return display; }
	public UiInsets margin() { return margin; }
	public UiInsets padding() { return padding; }
	public Integer widthPx() { return widthPx; }
	public Integer heightPx() { return heightPx; }
	public int flexGrow() { return flexGrow; }
	public Color background() { return background; }
	public Color borderColor() { return borderColor; }
	public int borderWidthPx() { return borderWidthPx; }

	public UiStyle display(boolean v) { this.display = v; return this; }
	public UiStyle margin(UiInsets v) { this.margin = v == null ? UiInsets.none() : v; return this; }
	public UiStyle padding(UiInsets v) { this.padding = v == null ? UiInsets.none() : v; return this; }
	public UiStyle widthPx(Integer v) { this.widthPx = v; return this; }
	public UiStyle heightPx(Integer v) { this.heightPx = v; return this; }
	public UiStyle flexGrow(int v) { this.flexGrow = Math.max(0, v); return this; }
	public UiStyle background(Color c) { this.background = c; return this; }
	public UiStyle border(Color color, int widthPx) {
		this.borderColor = color;
		this.borderWidthPx = Math.max(0, widthPx);
		return this;
	}
}
