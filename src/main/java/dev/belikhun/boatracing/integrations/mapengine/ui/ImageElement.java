package dev.belikhun.boatracing.integrations.mapengine.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Image element for the UI composer.
 *
 * Supports simple fit modes (CONTAIN/COVER/STRETCH) and optional centering.
 */
public final class ImageElement extends UiElement {
	public enum Fit {
		CONTAIN,
		COVER,
		STRETCH
	}

	private BufferedImage image;
	private Fit fit = Fit.CONTAIN;
	private boolean smoothing = true;

	public ImageElement(BufferedImage image) {
		this.image = image;
	}

	public ImageElement image(BufferedImage img) {
		this.image = img;
		return this;
	}

	public ImageElement fit(Fit fit) {
		this.fit = fit == null ? Fit.CONTAIN : fit;
		return this;
	}

	public ImageElement smoothing(boolean smoothing) {
		this.smoothing = smoothing;
		return this;
	}

	@Override
	protected UiMeasure onMeasure(UiRenderContext ctx, int maxWidth, int maxHeight) {
		// Prefer natural size but respect bounds.
		// If only one dimension is forced (e.g. height capped), derive the other
		// dimension from the image aspect ratio so "width:auto" behaves as expected.
		int iw = 0;
		int ih = 0;
		if (image != null) {
			iw = Math.max(0, image.getWidth());
			ih = Math.max(0, image.getHeight());
		}

		int padW = style.padding().horizontal();
		int padH = style.padding().vertical();
		int maxW = Math.max(0, maxWidth - padW);
		int maxH = Math.max(0, maxHeight - padH);

		Integer forcedW = style.widthPx();
		Integer forcedH = style.heightPx();

		int w;
		int h;
		if (iw <= 0 || ih <= 0) {
			w = 0;
			h = 0;
		} else if (forcedW == null && forcedH != null) {
			// Height constrained, width auto.
			int contentH = Math.max(0, forcedH - padH);
			contentH = Math.min(contentH, maxH);
			long scaledW = Math.round((double) iw * ((double) contentH / (double) ih));
			w = (int) Math.min((long) maxW, Math.max(0L, scaledW));
			h = contentH;
		} else if (forcedW != null && forcedH == null) {
			// Width constrained, height auto.
			int contentW = Math.max(0, forcedW - padW);
			contentW = Math.min(contentW, maxW);
			long scaledH = Math.round((double) ih * ((double) contentW / (double) iw));
			w = contentW;
			h = (int) Math.min((long) maxH, Math.max(0L, scaledH));
		} else {
			// No single-axis constraint: use natural size clamped to bounds.
			w = Math.min(maxW, iw);
			h = Math.min(maxH, ih);
		}

		return UiMeasure.of(Math.max(0, w) + padW, Math.max(0, h) + padH);
	}

	@Override
	protected void onRender(UiRenderContext ctx) {
		if (ctx == null || ctx.g == null)
			return;
		if (image == null)
			return;

		UiRect r = contentRect();
		if (r.w() <= 0 || r.h() <= 0)
			return;

		int iw = Math.max(1, image.getWidth());
		int ih = Math.max(1, image.getHeight());

		double sx = (double) r.w() / (double) iw;
		double sy = (double) r.h() / (double) ih;

		double s;
		if (fit == Fit.STRETCH) {
			s = 0.0;
		} else if (fit == Fit.COVER) {
			s = Math.max(sx, sy);
		} else {
			s = Math.min(sx, sy);
		}

		int dw;
		int dh;
		if (fit == Fit.STRETCH) {
			dw = r.w();
			dh = r.h();
		} else {
			dw = Math.max(1, (int) Math.round(iw * s));
			dh = Math.max(1, (int) Math.round(ih * s));
		}

		int dx = r.x() + Math.max(0, (r.w() - dw) / 2);
		int dy = r.y() + Math.max(0, (r.h() - dh) / 2);

		Graphics2D g = ctx.g;
		Object prevHint = null;
		try {
			if (smoothing) {
				prevHint = g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			}
		} catch (Throwable ignored) {
			prevHint = null;
		}

		try {
			g.drawImage(image, dx, dy, dw, dh, null);
		} catch (Throwable ignored) {
		}

		try {
			if (smoothing && prevHint != null) {
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, prevHint);
			}
		} catch (Throwable ignored) {
		}
	}
}
