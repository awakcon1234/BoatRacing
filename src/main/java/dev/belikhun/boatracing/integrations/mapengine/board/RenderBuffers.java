package dev.belikhun.boatracing.integrations.mapengine.board;

import java.awt.image.BufferedImage;

public final class RenderBuffers {
	private final BufferedImage[] renderBuffers = new BufferedImage[2];
	private int renderBufferW = -1;
	private int renderBufferH = -1;
	private int renderBufferCursor = 0;

	public BufferedImage acquire(int w, int h) {
		int ww = Math.max(1, w);
		int hh = Math.max(1, h);
		if (ww != renderBufferW || hh != renderBufferH || renderBuffers[0] == null || renderBuffers[1] == null) {
			renderBufferW = ww;
			renderBufferH = hh;
			renderBuffers[0] = new BufferedImage(ww, hh, BufferedImage.TYPE_INT_ARGB);
			renderBuffers[1] = new BufferedImage(ww, hh, BufferedImage.TYPE_INT_ARGB);
		}
		renderBufferCursor = (renderBufferCursor + 1) & 1;
		return renderBuffers[renderBufferCursor];
	}
}
