package dev.belikhun.boatracing.cinematic;

import org.bukkit.Sound;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class CinematicMusicService {

	public static class IntroTune {
		public final String name;
		public final List<CinematicSoundEvent> events;

		public IntroTune(String name, List<CinematicSoundEvent> events) {
			this.name = name;
			this.events = events;
		}
	}

	private static IntroTune cachedNeoClassical;
	private static IntroTune cachedEpic;
	private static IntroTune cachedFunky;
	private static IntroTune cachedUndertale;
	private static IntroTune cachedRetro;
	private static IntroTune cachedEthereal;
	private static IntroTune cachedCyberpunk;
	private static IntroTune cachedWestern;

	public static IntroTune getRandomIntroTune() {
		int pick = ThreadLocalRandom.current().nextInt(8);
		switch (pick) {
			case 0: return introTuneNeoClassical();
			case 1: return introTuneEpic();
			case 2: return introTuneFunky();
			case 3: return introTuneUndertale();
			case 4: return introTuneRetro();
			case 5: return introTuneEthereal();
			case 6: return introTuneCyberpunk();
			case 7: return introTuneWestern();
			default: return introTuneNeoClassical();
		}
	}

	public static IntroTune defaultArcadeIntroTune() {
		return getRandomIntroTune();
	}

	public static IntroTune introTuneNeoClassical() {
		if (cachedNeoClassical != null) return cachedNeoClassical;
		// "Neo-Classical Speed" - High tempo arpeggios
		List<CinematicSoundEvent> out = new ArrayList<>();

		final float master = 0.85f;
		final Sound lead = Sound.BLOCK_NOTE_BLOCK_BIT;
		final Sound lead2 = Sound.BLOCK_NOTE_BLOCK_PLING;
		final Sound bass = Sound.BLOCK_NOTE_BLOCK_BASS;
		final Sound kick = Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
		final Sound snare = Sound.BLOCK_NOTE_BLOCK_SNARE;
		final Sound hat = Sound.BLOCK_NOTE_BLOCK_HAT;

		// Tempo: Very Fast. 2 ticks per step = 10 steps/sec.
		final int step = 2;

		// 4 Bars of 8 steps (16th notes)
		// Progression: F#m - D - E - C#7

		float[] melody = new float[32];
		float[] bassLine = new float[32];

		// Bar 1: F#m (F# A C#)
		float[] bar1 = {Pitches.Fs4, Pitches.A4, Pitches.Cs5, Pitches.A4, Pitches.Fs4, Pitches.A4, Pitches.Cs5, Pitches.A4};
		System.arraycopy(bar1, 0, melody, 0, 8);
		Arrays.fill(bassLine, 0, 8, Pitches.Fs3);

		// Bar 2: D (D F# A)
		float[] bar2 = {Pitches.D4, Pitches.Fs4, Pitches.A4, Pitches.Fs4, Pitches.D4, Pitches.Fs4, Pitches.A4, Pitches.Fs4};
		System.arraycopy(bar2, 0, melody, 8, 8);
		Arrays.fill(bassLine, 8, 16, Pitches.D4);

		// Bar 3: E (E G# B)
		float[] bar3 = {Pitches.E4, Pitches.Gs4, Pitches.B4, Pitches.Gs4, Pitches.E4, Pitches.Gs4, Pitches.B4, Pitches.Gs4};
		System.arraycopy(bar3, 0, melody, 16, 8);
		Arrays.fill(bassLine, 16, 24, Pitches.E4);

		// Bar 4: C#7 (C# F G#)
		float[] bar4 = {Pitches.Cs4, Pitches.F4, Pitches.Gs4, Pitches.F4, Pitches.Cs4, Pitches.F4, Pitches.Gs4, Pitches.F4};
		System.arraycopy(bar4, 0, melody, 24, 8);
		Arrays.fill(bassLine, 24, 32, Pitches.Cs4);

		// Loop 4 times
		int loops = 4;
		int loopLen = melody.length * step;

		for (int l = 0; l < loops; l++) {
			int offset = l * loopLen;

			for (int i = 0; i < melody.length; i++) {
				int t = offset + (i * step);

				// Lead (Arpeggio)
				addSound(out, t, lead, master * 0.7f, melody[i]);

				// Bass (Steady 8ths)
				addSound(out, t, bass, master * 0.5f, bassLine[i]);

				// Drums
				if (i % 4 == 0) {
					addSound(out, t, kick, master * 0.8f, 1.0f);
				}
				if (i % 4 == 2) {
					addSound(out, t, snare, master * 0.8f, 1.0f);
				}
				addSound(out, t, hat, master * 0.3f, 1.4f);
			}
		}

		// Outro: 2 Bars (16 steps)
		int outroStart = loops * loopLen;
		float[] outroMelody = {
			Pitches.Fs4, Pitches.A4, Pitches.Cs5, Pitches.Fs5, Pitches.Cs5, Pitches.A4, Pitches.Fs4, 0,
			Pitches.Fs4, 0, Pitches.Fs4, 0, Pitches.Fs4, 0, 0, 0
		};

		for (int i = 0; i < outroMelody.length; i++) {
			int t = outroStart + (i * step);
			float p = outroMelody[i];

			if (p > 0) {
				addSound(out, t, lead, master * 0.8f, p);
				addSound(out, t, lead2, master * 0.5f, p);
			}

			if (i % 4 == 0) {
				addSound(out, t, bass, master * 0.6f, Pitches.Fs3);
				addSound(out, t, kick, master * 0.8f, 1.0f);
			}

			if (i == 0) {
				addSound(out, t, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.5f, 1.2f);
			}
		}

		int end = outroStart + (outroMelody.length * step);
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);
		addSound(out, end, lead, master, Pitches.Fs4);

		return cachedNeoClassical = new IntroTune("Neo-Classical Speed", out);
	}

	public static IntroTune introTuneEpic() {
		if (cachedEpic != null) return cachedEpic;
		// "Epic Orchestral" - Slower, heavy hits
		List<CinematicSoundEvent> out = new ArrayList<>();
		final float master = 0.85f;
		final Sound strings = Sound.BLOCK_NOTE_BLOCK_PLING;
		final Sound brass = Sound.BLOCK_NOTE_BLOCK_BIT; // Synth brass
		final Sound timpani = Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
		final Sound snare = Sound.BLOCK_NOTE_BLOCK_SNARE;

		// Tempo: 4 ticks per step (slower)
		final int step = 4;

		// 4 Bars of 16 steps (16th notes at half tempo = 8th notes feel)
		// Bar 1: Gm
		// Bar 2: Eb
		// Bar 3: Bb
		// Bar 4: F

		for (int bar = 0; bar < 4; bar++) {
			int barOffset = bar * 16 * step;
			float root = (bar == 0) ? Pitches.G3 : (bar == 1) ? Pitches.Ds4 : (bar == 2) ? Pitches.Bb3 : Pitches.F4;
			float third = (bar == 0) ? Pitches.Bb3 : (bar == 1) ? Pitches.G4 : (bar == 2) ? Pitches.D4 : Pitches.A4;
			float fifth = (bar == 0) ? Pitches.D4 : (bar == 1) ? Pitches.Bb4 : (bar == 2) ? Pitches.F4 : Pitches.C5;

			for (int i = 0; i < 16; i++) {
				int t = barOffset + (i * step);

				// Chords on beat 1 (0) and 3 (8)
				if (i == 0 || i == 8) {
					addSound(out, t, strings, master * 0.6f, root);
					addSound(out, t, strings, master * 0.6f, third);
					addSound(out, t, strings, master * 0.6f, fifth);
					addSound(out, t, timpani, master * 0.8f, 1.0f);
				}

				// Melody / Ostinato
				// Simple arpeggio: Root - Fifth - Octave - Fifth
				float note = (i % 4 == 0) ? root : (i % 4 == 1) ? fifth : (i % 4 == 2) ? third : fifth;
				// Transpose melody up an octave for visibility
				addSound(out, t, strings, master * 0.5f, note * 2.0f > 2.0f ? note : note * 2.0f);

				// Marching Snare
				if (i % 2 == 0) {
					addSound(out, t, snare, master * 0.4f, 1.0f);
				}
			}
		}

		// Outro: 8 steps (32 ticks)
		int outroStart = 4 * 16 * step;
		for (int i = 0; i < 8; i++) {
			int t = outroStart + (i * step);
			// Big finish on Gm
			if (i == 0) {
				addSound(out, t, strings, master, Pitches.G3);
				addSound(out, t, strings, master, Pitches.Bb3);
				addSound(out, t, strings, master, Pitches.D4);
				addSound(out, t, timpani, master, 1.0f);
				addSound(out, t, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);
			}
		}

		// Final hit
		int end = outroStart + (8 * step);
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);

		return cachedEpic = new IntroTune("Epic Orchestral", out);
	}

	public static IntroTune introTuneFunky() {
		if (cachedFunky != null) return cachedFunky;
		List<CinematicSoundEvent> out = new ArrayList<>();
		final float master = 0.85f;
		final Sound bass = Sound.BLOCK_NOTE_BLOCK_BASS; // Slap bass feel
		final Sound keys = Sound.BLOCK_NOTE_BLOCK_BIT; // Synth keys
		final Sound kick = Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
		final Sound hat = Sound.BLOCK_NOTE_BLOCK_HAT;

		// Tempo: Fast. 2 ticks per step.
		final int step = 2;

		// Key: E Dorian (E G A B D)
		// Bass Riff: E . . E . G . E . . D . B . A G
		float[] riff = {Pitches.E4, 0, 0, Pitches.E4, 0, Pitches.G4, 0, Pitches.E4, 0, 0, Pitches.D5, 0, Pitches.B4, 0, Pitches.A4, Pitches.G4};

		for (int bar = 0; bar < 8; bar++) {
			int barOffset = bar * 16 * step;
			for (int i = 0; i < 16; i++) {
				int t = barOffset + (i * step);
				float p = riff[i];

				if (p > 0) {
					addSound(out, t, bass, master * 0.8f, p);
				}

				// Drums: Funky beat
				// Kick: 0, 10 (syncopated)
				if (i == 0 || i == 10) {
					addSound(out, t, kick, master * 0.8f, 1.0f);
				}
				// Hat: 0, 2, 4...
				if (i % 2 == 0) {
					addSound(out, t, hat, master * 0.4f, 1.5f);
				}

				// Keys: Stabs on off-beats
				if (i == 4 || i == 12) {
					addSound(out, t, keys, master * 0.5f, (bar % 2 == 0) ? Pitches.B3 : Pitches.D4);
				}
			}
		}

		// Outro: 1 Bar (16 steps)
		int outroStart = 8 * 16 * step;
		for (int i = 0; i < 16; i++) {
			int t = outroStart + (i * step);
			if (i == 0) {
				addSound(out, t, bass, master, Pitches.E4);
				addSound(out, t, keys, master, Pitches.E5);
				addSound(out, t, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.5f, 1.2f);
			}
		}

		// Final hit
		int end = outroStart + (16 * step);
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);

		return cachedFunky = new IntroTune("Funky Slap", out);
	}



	public static IntroTune introTuneUndertale() {
		if (cachedUndertale != null) return cachedUndertale;
		List<CinematicSoundEvent> out = new ArrayList<>();
		final float master = 0.85f;
		final Sound lead = Sound.BLOCK_NOTE_BLOCK_BIT; // 8-bit sound
		final Sound kick = Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
		final Sound snare = Sound.BLOCK_NOTE_BLOCK_SNARE;

		// Tempo: Fast (~150bpm with step=2)
		final int step = 2;

		// Roots: D4, C4, B3, Bb3
		float[] roots = {Pitches.D4, Pitches.C4, Pitches.B3, Pitches.Bb3};

		// Melody offsets (approximate quantization)
		// D D D5 A Ab G F D F G
		int[] offsets = {0, 2, 4, 8, 10, 12, 14, 16, 18, 20};

		// Length of one loop iteration in steps
		int loopLen = 24;

		for (int bar = 0; bar < 6; bar++) {
			int barOffset = bar * loopLen * step;

			for (int i = 0; i < offsets.length; i++) {
				int t = barOffset + (offsets[i] * step);
				float pitch;
				if (i < 2) {
					pitch = roots[bar % 4];
				} else {
					// Fixed melody tail
					switch(i) {
						case 2: pitch = Pitches.D5; break;
						case 3: pitch = Pitches.A4; break;
						case 4: pitch = Pitches.Gs4; break;
						case 5: pitch = Pitches.G4; break;
						case 6: pitch = Pitches.F4; break;
						case 7: pitch = Pitches.D4; break;
						case 8: pitch = Pitches.F4; break;
						case 9: pitch = Pitches.G4; break;
						default: pitch = Pitches.D4;
					}
				}
				addSound(out, t, lead, master, pitch);
			}

			// Simple drum beat
			for (int i = 0; i < loopLen; i += 4) {
				addSound(out, barOffset + (i * step), kick, master * 0.8f, 1.0f);
				addSound(out, barOffset + ((i + 2) * step), snare, master * 0.8f, 1.0f);
			}
		}

		int end = 6 * loopLen * step;
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);
		return cachedUndertale = new IntroTune("Megalovania", out);
	}

	public static IntroTune introTuneRetro() {
		if (cachedRetro != null) return cachedRetro;
		List<CinematicSoundEvent> out = new ArrayList<>();
		final float master = 0.85f;
		final Sound lead = Sound.BLOCK_NOTE_BLOCK_BIT;
		final Sound bass = Sound.BLOCK_NOTE_BLOCK_BIT; // Also bit for bass
		final Sound kick = Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
		final Sound snare = Sound.BLOCK_NOTE_BLOCK_SNARE;

		// Tempo: Fast (150bpm)
		final int step = 2;

		// C Major Arpeggios: C - Am - F - G
		float[] roots = {Pitches.C4, Pitches.A3, Pitches.F4, Pitches.G4};
		float[] thirds = {Pitches.E4, Pitches.C4, Pitches.A4, Pitches.B4};
		float[] fifths = {Pitches.G4, Pitches.E4, Pitches.C5, Pitches.D5};

		for (int bar = 0; bar < 9; bar++) {
			int barOffset = bar * 16 * step;
			float r = roots[bar % 4];
			float th = thirds[bar % 4];
			float f = fifths[bar % 4];

			for (int i = 0; i < 16; i++) {
				int t = barOffset + (i * step);

				// Fast Arpeggio: Root - Third - Fifth - Octave
				float note;
				switch (i % 4) {
					case 0: note = r; break;
					case 1: note = th; break;
					case 2: note = f; break;
					case 3: note = r * 2.0f; break; // Octave
					default: note = r;
				}
				// Keep pitch within reasonable bounds (0.5 - 2.0)
				if (note > 2.0f) note = note / 2.0f;

				addSound(out, t, lead, master * 0.6f, note);

				// Bass: Root notes on beats
				if (i % 4 == 0) {
					addSound(out, t, bass, master * 0.6f, r / 2.0f < 0.5f ? r : r / 2.0f);
				}

				// Drums: Basic Rock
				if (i % 4 == 0) addSound(out, t, kick, master * 0.8f, 1.0f);
				if (i % 4 == 2) addSound(out, t, snare, master * 0.8f, 1.0f);
			}
		}

		int end = 9 * 16 * step;
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);
		addSound(out, end, lead, master, Pitches.C5);
		return cachedRetro = new IntroTune("Retro 8-Bit", out);
	}

	public static IntroTune introTuneEthereal() {
		if (cachedEthereal != null) return cachedEthereal;
		List<CinematicSoundEvent> out = new ArrayList<>();
		final float master = 0.85f;
		final Sound chime = Sound.BLOCK_NOTE_BLOCK_CHIME;
		final Sound bell = Sound.BLOCK_NOTE_BLOCK_BELL;
		final Sound harp = Sound.BLOCK_NOTE_BLOCK_HARP;

		// Tempo: Slow (75bpm)
		final int step = 4;

		// Progression: Cmaj7 - Fmaj7
		// Cmaj7: C E G B
		// Fmaj7: F A C E

		for (int bar = 0; bar < 5; bar++) {
			int barOffset = bar * 16 * step;
			boolean isC = (bar % 2 == 0);

			// Chord pads (Harp) - Strummed
			float[] chord = isC
				? new float[]{Pitches.C4, Pitches.E4, Pitches.G4, Pitches.B4}
				: new float[]{Pitches.F4, Pitches.A4, Pitches.C5, Pitches.E5};

			for (int c = 0; c < chord.length; c++) {
				addSound(out, barOffset + (c * 2), harp, master * 0.5f, chord[c]);
			}

			// Melody (Chime) - Sparse
			// Random twinkling effect
			int[] twinkleTimes = {0, 6, 10, 14};
			float[] twinkleNotes = isC
				? new float[]{Pitches.G4, Pitches.B4, Pitches.D5, Pitches.E5}
				: new float[]{Pitches.C5, Pitches.E5, Pitches.G4, Pitches.A4};

			for (int i = 0; i < twinkleTimes.length; i++) {
				int t = barOffset + (twinkleTimes[i] * step);
				addSound(out, t, chime, master * 0.7f, twinkleNotes[i]);
			}

			// Bell accents on beat 1
			addSound(out, barOffset, bell, master * 0.6f, isC ? Pitches.C5 : Pitches.F5 > 2.0f ? Pitches.F4 : Pitches.F5);
		}

		int end = 5 * 16 * step;
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);
		addSound(out, end, chime, master, Pitches.C5);
		return cachedEthereal = new IntroTune("Ethereal Dream", out);
	}

	public static IntroTune introTuneCyberpunk() {
		if (cachedCyberpunk != null) return cachedCyberpunk;
		List<CinematicSoundEvent> out = new ArrayList<>();
		final float master = 0.85f;
		final Sound lead = Sound.BLOCK_NOTE_BLOCK_BIT;
		final Sound bass = Sound.BLOCK_NOTE_BLOCK_BASS;
		final Sound kick = Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
		final Sound snare = Sound.BLOCK_NOTE_BLOCK_SNARE;

		// Tempo: Fast (150bpm)
		final int step = 2;

		// Dm - Bb - C - Am
		float[] roots = {Pitches.D4, Pitches.Bb3, Pitches.C4, Pitches.A3};

		for (int bar = 0; bar < 10; bar++) {
			int barOffset = bar * 16 * step;
			float r = roots[bar % 4];

			for (int i = 0; i < 16; i++) {
				int t = barOffset + (i * step);

				// Driving Bass (16th notes)
				addSound(out, t, bass, master * 0.7f, r / 2.0f < 0.5f ? r : r / 2.0f);

				// Lead: Syncopated stabs
				if (i == 0 || i == 3 || i == 6 || i == 10 || i == 12) {
					addSound(out, t, lead, master * 0.6f, r);
					addSound(out, t, lead, master * 0.4f, r * 1.5f); // Fifth
				}

				// Drums: Four-on-the-floor
				if (i % 4 == 0) addSound(out, t, kick, master * 0.9f, 1.0f);
				if (i % 8 == 4) addSound(out, t, snare, master * 0.8f, 1.0f);
			}
		}

		int end = 10 * 16 * step;
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);
		return cachedCyberpunk = new IntroTune("Cyberpunk 2077", out);
	}

	public static IntroTune introTuneWestern() {
		if (cachedWestern != null) return cachedWestern;
		List<CinematicSoundEvent> out = new ArrayList<>();
		final float master = 0.85f;
		final Sound banjo = Sound.BLOCK_NOTE_BLOCK_PLING; // Banjo-ish
		final Sound bass = Sound.BLOCK_NOTE_BLOCK_BASS;
		final Sound hat = Sound.BLOCK_NOTE_BLOCK_HAT;

		// Tempo: Medium (100bpm)
		final int step = 3;

		// E - A - B7 - E
		float[] roots = {Pitches.E4, Pitches.A3, Pitches.B3, Pitches.E4};

		for (int bar = 0; bar < 6; bar++) {
			int barOffset = bar * 16 * step;
			float r = roots[bar % 4];

			for (int i = 0; i < 16; i++) {
				int t = barOffset + (i * step);

				// Galloping Bass: 1 & a 2 & a ...
				// 0, 2, 3, 4, 6, 7, 8...
				if (i % 4 != 1) {
					addSound(out, t, bass, master * 0.6f, r);
				}

				// Banjo Melody: Arpeggios
				if (i % 2 == 0) {
					float note = r;
					if (i % 4 == 2) note = r * 1.5f; // Fifth
					if (i % 8 == 4) note = r * 2.0f; // Octave
					addSound(out, t, banjo, master * 0.7f, note);
				}

				// Shaker
				if (i % 2 == 0) addSound(out, t, hat, master * 0.3f, 1.2f);
			}
		}

		int end = 6 * 16 * step;
		addSound(out, end, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, master * 0.6f, 1.0f);
		addSound(out, end, banjo, master, Pitches.E4);
		return cachedWestern = new IntroTune("Wild West", out);
	}

	public static Sound parseSound(String name) {
		if (name == null || name.isBlank())
			return null;
		String n = name.trim().toUpperCase(Locale.ROOT);
		try {
			return Sound.valueOf(n);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void addSound(List<CinematicSoundEvent> out, int tick, Sound sound, float volume, float pitch) {
		out.add(new CinematicSoundEvent(tick, sound, volume, pitch));
	}

	public static final class Pitches {
		public static final float Fs3 = 0.5f;
		public static final float G3 = 0.53f;
		public static final float Gs3 = 0.56f;
		public static final float A3 = 0.6f;
		public static final float Bb3 = 0.63f;
		public static final float B3 = 0.67f;
		public static final float C4 = 0.7f;
		public static final float Cs4 = 0.75f;
		public static final float D4 = 0.8f;
		public static final float Ds4 = 0.84f;
		public static final float E4 = 0.9f;
		public static final float F4 = 0.94f;
		public static final float Fs4 = 1.0f;
		public static final float G4 = 1.06f;
		public static final float Gs4 = 1.12f;
		public static final float A4 = 1.19f;
		public static final float Bb4 = 1.26f;
		public static final float B4 = 1.33f;
		public static final float C5 = 1.41f;
		public static final float Cs5 = 1.5f;
		public static final float D5 = 1.59f;
		public static final float Ds5 = 1.68f;
		public static final float E5 = 1.78f;
		public static final float F5 = 1.89f;
		public static final float Fs5 = 2.0f;
	}
}
