package dev.belikhun.boatracing.integrations.mapengine.ui;

/**
 * Simple immutable insets in pixels.
 */
public record UiInsets(int top, int right, int bottom, int left) {
	public static UiInsets all(int v) {
		return new UiInsets(v, v, v, v);
	}

	public static UiInsets symmetric(int vertical, int horizontal) {
		return new UiInsets(vertical, horizontal, vertical, horizontal);
	}

	public static UiInsets none() {
		return new UiInsets(0, 0, 0, 0);
	}

	public int horizontal() {
		return left + right;
	}

	public int vertical() {
		return top + bottom;
	}
}
