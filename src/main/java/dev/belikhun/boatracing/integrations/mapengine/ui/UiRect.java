package dev.belikhun.boatracing.integrations.mapengine.ui;

public record UiRect(int x, int y, int w, int h) {
	public int right() { return x + w; }
	public int bottom() { return y + h; }
}
