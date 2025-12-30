package dev.belikhun.boatracing.integrations.mapengine.board;

import org.bukkit.World;
import org.bukkit.entity.Player;

public final class BoardViewers {
	private BoardViewers() {}

	public static boolean isWithinRadiusChunks(Player p, BoardPlacement pl, int radiusChunks) {
		if (p == null || pl == null || !pl.isValid())
			return false;
		World w = p.getWorld();
		if (w == null || pl.world == null || !w.getName().equals(pl.world))
			return false;

		int pcx = p.getLocation().getBlockX() >> 4;
		int pcz = p.getLocation().getBlockZ() >> 4;
		int bcx = pl.centerBlockX() >> 4;
		int bcz = pl.centerBlockZ() >> 4;
		int dx = pcx - bcx;
		int dz = pcz - bcz;
		return (dx * dx + dz * dz) <= (radiusChunks * radiusChunks);
	}
}
