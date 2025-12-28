package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Renders a UI tree into a BufferedImage (intended to be sent to MapEngine).
 */
public final class UiComposer {
	private UiComposer() {}

	private static volatile boolean perfDebug = false;
	private static volatile java.util.function.Consumer<String> perfLogger = null;
	private static volatile long lastPerfLogNs = 0L;
	private static final long PERF_LOG_INTERVAL_NS = 1_000_000_000L;

	/**
	 * Enable/disable perf logging for {@link #render(UiElement, int, int, Font, Font)}.
	 * Intended to be wired to the plugin's debug config from the caller.
	 */
	public static void setPerfDebug(boolean enabled, java.util.function.Consumer<String> logger) {
		perfDebug = enabled;
		perfLogger = logger;
		lastPerfLogNs = 0L;
	}

	private static boolean shouldLogPerf(long nowNs) {
		if (!perfDebug || perfLogger == null) return false;
		synchronized (UiComposer.class) {
			if (lastPerfLogNs != 0L && (nowNs - lastPerfLogNs) < PERF_LOG_INTERVAL_NS) return false;
			lastPerfLogNs = nowNs;
			return true;
		}
	}

	private static String fmtMs(long ns) {
		if (ns <= 0L) return "0.00";
		return String.format(java.util.Locale.ROOT, "%.2f", (double) ns / 1_000_000.0);
	}

	public static BufferedImage render(UiElement root, int widthPx, int heightPx, Font defaultFont, Font fallbackFont) {
		int w = Math.max(1, widthPx);
		int h = Math.max(1, heightPx);

		final long nowNs = System.nanoTime();
		final boolean doPerfLog = shouldLogPerf(nowNs);
		final long t0 = doPerfLog ? nowNs : 0L;
		long tCtx = 0L;
		long tLayout = 0L;

		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			UiRenderContext ctx = new UiRenderContext(
					g,
					defaultFont,
					fallbackFont,
					Color.WHITE
			);
			ctx.applyDefaultHints();
			if (doPerfLog) tCtx = System.nanoTime();

			if (defaultFont != null) g.setFont(defaultFont);
			if (root != null) {
				root.layout(ctx, 0, 0, w, h);
				if (doPerfLog) tLayout = System.nanoTime();
				root.render(ctx);
			}
		} finally {
			try { g.dispose(); } catch (Throwable ignored) {}
		}

		if (doPerfLog) {
			long tEnd = System.nanoTime();
			long ctxNs = (tCtx > 0L) ? (tCtx - t0) : 0L;
			long layoutNs = (tLayout > 0L && tCtx > 0L) ? (tLayout - tCtx) : 0L;
			long renderNs = (tLayout > 0L) ? (tEnd - tLayout) : (tEnd - (tCtx > 0L ? tCtx : t0));
			long totalNs = tEnd - t0;
			try {
				java.util.function.Consumer<String> logger = perfLogger;
				if (logger != null) {
					logger.accept("render " + w + "x" + h
							+ " total=" + fmtMs(totalNs) + "ms"
							+ " ctx=" + fmtMs(ctxNs) + "ms"
							+ " layout=" + fmtMs(layoutNs) + "ms"
							+ " render=" + fmtMs(renderNs) + "ms");
				}
			} catch (Throwable ignored) {}
		}
		return img;
	}
}
