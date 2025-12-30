package dev.belikhun.boatracing.integrations.mapengine.board;

import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.BlockVector;

import java.util.Locale;

public final class BoardPlacement {
	public final String world;
	public final BlockVector a;
	public final BlockVector b;
	public final BlockFace facing;
	public final int mapsWide;
	public final int mapsHigh;

	public BoardPlacement(String world, BlockVector a, BlockVector b, BlockFace facing, int mapsWide, int mapsHigh) {
		this.world = world;
		this.a = a;
		this.b = b;
		this.facing = facing;
		this.mapsWide = mapsWide;
		this.mapsHigh = mapsHigh;
	}

	public boolean isValid() {
		return world != null && !world.isBlank() && a != null && b != null && facing != null && mapsWide > 0 && mapsHigh > 0;
	}

	public int pixelWidth() {
		return mapsWide * 128;
	}

	public int pixelHeight() {
		return mapsHigh * 128;
	}

	public int centerBlockX() {
		return (a.getBlockX() + b.getBlockX()) / 2;
	}

	public int centerBlockZ() {
		return (a.getBlockZ() + b.getBlockZ()) / 2;
	}

	public static BoardPlacement load(ConfigurationSection root) {
		if (root == null)
			return null;
		ConfigurationSection p = root.getConfigurationSection("placement");
		if (p == null)
			return null;
		try {
			String world = p.getString("world");
			String facingRaw = p.getString("facing", "NORTH");
			BlockFace facing;
			try {
				facing = BlockFace.valueOf(facingRaw.toUpperCase(Locale.ROOT));
			} catch (Throwable ignored) {
				facing = BlockFace.NORTH;
			}

			int ax = p.getInt("a.x");
			int ay = p.getInt("a.y");
			int az = p.getInt("a.z");
			int bx = p.getInt("b.x");
			int by = p.getInt("b.y");
			int bz = p.getInt("b.z");

			BlockVector a = new BlockVector(ax, ay, az);
			BlockVector b = new BlockVector(bx, by, bz);

			int mapsWide = p.getInt("maps.wide", 1);
			int mapsHigh = p.getInt("maps.high", 1);
			if (mapsWide <= 0 || mapsHigh <= 0) {
				int[] computed = computeMaps(a, b, facing);
				mapsWide = computed[0];
				mapsHigh = computed[1];
			}

			BoardPlacement out = new BoardPlacement(world, a, b, facing, mapsWide, mapsHigh);
			return out.isValid() ? out : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	public void save(ConfigurationSection sec) {
		if (sec == null)
			return;
		sec.set("world", world);
		sec.set("facing", facing.name());

		sec.set("a.x", a.getBlockX());
		sec.set("a.y", a.getBlockY());
		sec.set("a.z", a.getBlockZ());

		sec.set("b.x", b.getBlockX());
		sec.set("b.y", b.getBlockY());
		sec.set("b.z", b.getBlockZ());

		sec.set("maps.wide", mapsWide);
		sec.set("maps.high", mapsHigh);
	}

	public static BoardPlacement fromSelection(World w, org.bukkit.util.BoundingBox box, BlockFace facing) {
		if (w == null || box == null || facing == null)
			return null;

		BlockFace dir = normalizeCardinal(facing);
		if (dir == null)
			return null;

		int minX = (int) Math.floor(Math.min(box.getMinX(), box.getMaxX()));
		int maxX = (int) Math.floor(Math.max(box.getMinX(), box.getMaxX()));
		int minY = (int) Math.floor(Math.min(box.getMinY(), box.getMaxY()));
		int maxY = (int) Math.floor(Math.max(box.getMinY(), box.getMaxY()));
		int minZ = (int) Math.floor(Math.min(box.getMinZ(), box.getMaxZ()));
		int maxZ = (int) Math.floor(Math.max(box.getMinZ(), box.getMaxZ()));

		int ax;
		int ay;
		int az;
		int bx;
		int by;
		int bz;
		if (dir == BlockFace.NORTH) {
			int z = minZ;
			ax = minX;
			ay = minY;
			az = z;
			bx = maxX;
			by = maxY;
			bz = z;
		} else if (dir == BlockFace.SOUTH) {
			int z = maxZ;
			ax = minX;
			ay = minY;
			az = z;
			bx = maxX;
			by = maxY;
			bz = z;
		} else if (dir == BlockFace.WEST) {
			int x = minX;
			ax = x;
			ay = minY;
			az = minZ;
			bx = x;
			by = maxY;
			bz = maxZ;
		} else {
			int x = maxX;
			ax = x;
			ay = minY;
			az = minZ;
			bx = x;
			by = maxY;
			bz = maxZ;
		}

		int offX = 0;
		int offZ = 0;
		if (dir == BlockFace.NORTH)
			offZ = -1;
		else if (dir == BlockFace.SOUTH)
			offZ = 1;
		else if (dir == BlockFace.WEST)
			offX = -1;
		else if (dir == BlockFace.EAST)
			offX = 1;
		ax += offX;
		bx += offX;
		az += offZ;
		bz += offZ;

		BlockVector a = new BlockVector(ax, ay, az);
		BlockVector b = new BlockVector(bx, by, bz);
		int[] maps = computeMaps(a, b, dir);
		return new BoardPlacement(w.getName(), a, b, dir, maps[0], maps[1]);
	}

	private static BlockFace normalizeCardinal(BlockFace face) {
		if (face == null)
			return null;
		return switch (face) {
			case NORTH, SOUTH, EAST, WEST -> face;
			default -> null;
		};
	}

	private static int[] computeMaps(BlockVector a, BlockVector b, BlockFace facing) {
		int wide;
		int high;

		if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
			wide = Math.abs(a.getBlockX() - b.getBlockX()) + 1;
			high = Math.abs(a.getBlockY() - b.getBlockY()) + 1;
		} else {
			wide = Math.abs(a.getBlockZ() - b.getBlockZ()) + 1;
			high = Math.abs(a.getBlockY() - b.getBlockY()) + 1;
		}

		wide = Math.max(1, wide);
		high = Math.max(1, high);
		return new int[] { wide, high };
	}
}
