package dev.belikhun.boatracing.cinematic;

import org.bukkit.Sound;

public class CinematicSoundEvent {
	public final int atTick;
	public final Sound sound;
	public final float volume;
	public final float pitch;

	public CinematicSoundEvent(int atTick, Sound sound, float volume, float pitch) {
		this.atTick = Math.max(0, atTick);
		this.sound = sound;
		this.volume = Math.max(0.0f, volume);
		this.pitch = Math.max(0.0f, pitch);
	}
}
