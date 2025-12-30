package dev.belikhun.boatracing.cinematic;

import org.bukkit.Location;

/**
 * A camera keyframe.
 *
 * durationTicks: how long it takes to move from this keyframe to the next keyframe.
 */
public class CinematicKeyframe {
	public final Location location;
	public final int durationTicks;

	public CinematicKeyframe(Location location, int durationTicks) {
		this.location = location;
		this.durationTicks = Math.max(0, durationTicks);
	}
}
