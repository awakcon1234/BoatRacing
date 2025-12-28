package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class UiContainer extends UiElement {
	protected final List<UiElement> children = new ArrayList<>();

	public List<UiElement> children() {
		return Collections.unmodifiableList(children);
	}

	public <T extends UiElement> T add(T child) {
		if (child != null) children.add(child);
		return child;
	}

	public void clear() {
		children.clear();
	}
}
