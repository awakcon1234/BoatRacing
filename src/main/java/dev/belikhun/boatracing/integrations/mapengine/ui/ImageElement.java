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
		// If the caller didn't force size, prefer natural size but respect bounds.
		int w = 0;
		int h = 0;
		if (image != null) {
			w = image.getWidth();
			h = image.getHeight();
		}

		w = Math.min(Math.max(0, maxWidth), Math.max(0, w));
		h = Math.min(Math.max(0, maxHeight), Math.max(0, h));
		w += style.padding().horizontal();
		h += style.padding().vertical();
		return UiMeasure.of(w, h);
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
