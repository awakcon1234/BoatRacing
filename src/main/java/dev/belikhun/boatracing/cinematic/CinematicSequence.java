package dev.belikhun.boatracing.cinematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CinematicSequence {
	public final List<CinematicKeyframe> keyframes;
	public final List<CinematicSoundEvent> soundEvents;
	public final boolean linearEasing;

	public CinematicSequence(List<CinematicKeyframe> keyframes, List<CinematicSoundEvent> soundEvents) {
		this(keyframes, soundEvents, false);
	}

	public CinematicSequence(List<CinematicKeyframe> keyframes, List<CinematicSoundEvent> soundEvents, boolean linearEasing) {
		this.keyframes = (keyframes == null) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(keyframes));
		this.soundEvents = (soundEvents == null) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(soundEvents));
		this.linearEasing = linearEasing;
	}

	public int totalTicks() {
		if (keyframes.size() < 2)
			return 0;
		int sum = 0;
		for (int i = 0; i < keyframes.size() - 1; i++) {
			CinematicKeyframe k = keyframes.get(i);
			if (k == null)
				continue;
			sum += Math.max(0, k.durationTicks);
		}
		return Math.max(0, sum);
	}
}
