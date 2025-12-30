package dev.belikhun.boatracing.integrations.mapengine.board;

import dev.belikhun.boatracing.BoatRacingPlugin;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BoardFontLoader {
	private BoardFontLoader() {}

	public static Font tryLoadBoardFont(BoatRacingPlugin plugin, String fontFile, Consumer<String> dbg) {
		if (plugin == null)
			return null;

		List<Supplier<InputStream>> candidates = new ArrayList<>();

		String ff = fontFile;
		if (ff != null) {
			ff = ff.trim();
			if (ff.isEmpty())
				ff = null;
		}

		if (ff != null) {
			final String finalFf = ff;
			candidates.add(() -> {
				try {
					File f = new File(finalFf);
					if (!f.isAbsolute())
						f = new File(plugin.getDataFolder(), finalFf);
					if (!f.exists() || !f.isFile())
						return null;
					return new FileInputStream(f);
				} catch (Throwable ignored) {
					return null;
				}
			});
		}

		candidates.add(() -> {
			try {
				File f = new File(plugin.getDataFolder(), "fonts/minecraft.ttf");
				if (!f.exists())
					f = new File(plugin.getDataFolder(), "fonts/minecraft.otf");
				if (!f.exists())
					return null;
				return new FileInputStream(f);
			} catch (Throwable ignored) {
				return null;
			}
		});

		candidates.add(() -> {
			try {
				File f = new File(plugin.getDataFolder(), "minecraft.ttf");
				if (!f.exists())
					f = new File(plugin.getDataFolder(), "minecraft.otf");
				if (!f.exists())
					return null;
				return new FileInputStream(f);
			} catch (Throwable ignored) {
				return null;
			}
		});

		candidates.add(() -> {
			try {
				InputStream is = plugin.getResource("fonts/minecraft.ttf");
				if (is == null)
					is = plugin.getResource("fonts/minecraft.otf");
				return is;
			} catch (Throwable ignored) {
				return null;
			}
		});

		for (var sup : candidates) {
			InputStream is = null;
			try {
				is = sup.get();
				if (is == null)
					continue;
				Font f = Font.createFont(Font.TRUETYPE_FONT, is);
				try {
					GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
				} catch (Throwable ignored) {
				}
				if (dbg != null) {
					try {
						dbg.accept("Loaded board font: " + f.getFontName());
					} catch (Throwable ignored) {
					}
				}
				return f;
			} catch (Throwable t) {
				if (dbg != null) {
					try {
						dbg.accept("Failed to load board font: "
								+ (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
					} catch (Throwable ignored) {
					}
				}
			} finally {
				try {
					if (is != null)
						is.close();
				} catch (Throwable ignored) {
				}
			}
		}

		return null;
	}
}
