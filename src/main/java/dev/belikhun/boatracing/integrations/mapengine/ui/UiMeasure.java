package dev.belikhun.boatracing.integrations.mapengine.ui;

public record UiMeasure(int w, int h) {
	public static UiMeasure of(int w, int h) {
		return new UiMeasure(Math.max(0, w), Math.max(0, h));
	}
}
