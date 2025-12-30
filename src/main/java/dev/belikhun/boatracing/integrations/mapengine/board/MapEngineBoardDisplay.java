package dev.belikhun.boatracing.integrations.mapengine.board;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.util.Converter;
import org.bukkit.entity.Player;

import java.awt.image.BufferedImage;

/**
 * Handles MapEngine display + drawing pipeline with best-effort compatibility across MapEngine versions.
 */
public final class MapEngineBoardDisplay {
	private IMapDisplay display;
	private IDrawingSpace drawing;

	public boolean isReady() {
		return display != null && drawing != null;
	}

	public void ensure(MapEngineApi api, BoardPlacement placement, boolean buffering, boolean bundling) {
		if (api == null || placement == null || !placement.isValid())
			return;
		if (isReady())
			return;

		try {
			display = api.displayProvider().createBasic(placement.a, placement.b, placement.facing);
			drawing = api.pipeline().createDrawingSpace(display);
			try {
				drawing.ctx().converter(Converter.DIRECT);
			} catch (Throwable ignored) {
			}
			applyPipelineToggles(buffering, bundling);
		} catch (Throwable t) {
			display = null;
			drawing = null;
		}
	}

	public void destroy() {
		if (display != null) {
			tryInvoke(display, "destroy");
		}
		display = null;
		drawing = null;
	}

	public void ensureViewer(Player p) {
		if (p == null || !isReady())
			return;

		try {
			display.spawn(p);
		} catch (Throwable ignored) {
			tryInvoke(display, "spawn", p);
			tryInvoke(display, "show", p);
			tryInvoke(display, "spawnTo", p);
			tryInvoke(display, "addViewer", p);
		}

		try {
			drawing.ctx().addReceiver(p);
		} catch (Throwable ignored) {
			tryInvoke(drawing.ctx(), "addReceiver", p);
			tryInvoke(drawing.ctx(), "add", p);
		}
	}

	public void removeViewer(Player p) {
		if (p == null || !isReady())
			return;

		tryInvoke(drawing.ctx(), "removeReceiver", p);
		tryInvoke(drawing.ctx(), "remove", p);

		tryInvoke(display, "destroy", p);
		tryInvoke(display, "despawn", p);
		tryInvoke(display, "remove", p);
		tryInvoke(display, "hide", p);
		tryInvoke(display, "removeViewer", p);
	}

	public void renderAndFlush(BufferedImage img) {
		if (!isReady() || img == null)
			return;
		try {
			drawing.image(img, 0, 0);
			drawing.flush();
		} catch (Throwable ignored) {
		}
	}

	private void applyPipelineToggles(boolean buffering, boolean bundling) {
		if (!isReady())
			return;

		Object ctx;
		try {
			ctx = drawing.ctx();
		} catch (Throwable ignored) {
			return;
		}

		if (buffering) {
			try {
				drawing.ctx().buffering(true);
			} catch (Throwable ignored) {
				tryInvoke(ctx, "buffering", true);
				tryInvoke(ctx, "setBuffering", true);
			}
		}

		if (!bundling) {
			tryInvoke(ctx, "bundling", false);
			tryInvoke(ctx, "setBundling", false);
			tryInvoke(ctx, "bundle", false);
			tryInvoke(ctx, "setBundle", false);
			tryInvoke(ctx, "bundled", false);
			tryInvoke(ctx, "setBundled", false);
		}
	}

	private static void tryInvoke(Object target, String method, Object... args) {
		if (target == null || method == null)
			return;
		try {
			Class<?>[] sig = new Class<?>[args.length];
			for (int i = 0; i < args.length; i++)
				sig[i] = args[i].getClass();

			try {
				var m = target.getClass().getMethod(method, sig);
				m.invoke(target, args);
				return;
			} catch (Throwable ignored) {
			}

			for (var m : target.getClass().getMethods()) {
				if (!m.getName().equals(method))
					continue;
				if (m.getParameterCount() != args.length)
					continue;
				m.invoke(target, args);
				return;
			}
		} catch (Throwable ignored) {
		}
	}
}
