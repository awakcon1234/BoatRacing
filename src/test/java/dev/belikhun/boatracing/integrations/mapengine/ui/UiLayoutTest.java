package dev.belikhun.boatracing.integrations.mapengine.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

public class UiLayoutTest {
	private static UiRenderContext ctx() {
		BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		Font font = new Font("Dialog", Font.PLAIN, 16);
		UiRenderContext ctx = new UiRenderContext(g, font, font, Color.WHITE);
		ctx.applyDefaultHints();
		return ctx;
	}

	@Test
	public void columnLaysOutChildrenVertically() {
		UiRenderContext ctx = ctx();
		try {
			ColumnContainer col = new ColumnContainer().gap(10);
			col.style().padding(UiInsets.all(5));
			TextElement a = new TextElement("Hello");
			TextElement b = new TextElement("World");
			col.add(a);
			col.add(b);
			a.style().margin(UiInsets.all(2));
			b.style().margin(UiInsets.all(2));

			col.layout(ctx, 0, 0, 200, 200);
			assertTrue(a.rect().y() < b.rect().y(), "Second child should be below first child");
			assertEquals(a.rect().x(), b.rect().x(), "Default alignItems START keeps same x");
		} finally {
			try { ctx.g.dispose(); } catch (Throwable ignored) {}
		}
	}

	@Test
	public void rowLaysOutChildrenHorizontally() {
		UiRenderContext ctx = ctx();
		try {
			RowContainer row = new RowContainer().gap(8);
			row.style().padding(UiInsets.all(4));

			TextElement a = new TextElement("Left");
			TextElement b = new TextElement("Right");
			row.add(a);
			row.add(b);

			row.layout(ctx, 0, 0, 300, 80);
			assertTrue(a.rect().x() < b.rect().x(), "Second child should be to the right of first child");
			assertEquals(a.rect().y(), b.rect().y(), "Default alignItems START keeps same y");
		} finally {
			try { ctx.g.dispose(); } catch (Throwable ignored) {}
		}
	}
}
