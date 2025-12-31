package dev.belikhun.boatracing.cinematic;

import dev.belikhun.boatracing.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A small service that drives a cinematic camera for players by teleporting them each tick.
 *
 * Implementation notes:
 * - Uses SPECTATOR mode during playback.
 * - Avoids entity-based camera to stay Paper-version tolerant.
 */
public class CinematicCameraService {
	private final Plugin plugin;

	private static final class Running {
		final String id;
		final Set<UUID> players;
		final Map<UUID, GameMode> previousGameModes = new HashMap<>();
		final CinematicSequence sequence;
		final Runnable onComplete;
		BukkitTask tickTask;
		int tick;

		Running(String id, Set<UUID> players, CinematicSequence sequence, Runnable onComplete) {
			this.id = id;
			this.players = players;
			this.sequence = sequence;
			this.onComplete = onComplete;
			this.tick = 0;
		}
	}

	private final Map<String, Running> runningById = new HashMap<>();
	private final Map<UUID, String> idByPlayer = new HashMap<>();
	private final Map<UUID, GameMode> pendingRestoreGameModes = new HashMap<>();

	public CinematicCameraService(Plugin plugin) {
		this.plugin = plugin;
	}

	public synchronized boolean isRunningFor(UUID playerId) {
		if (playerId == null)
			return false;
		return idByPlayer.containsKey(playerId);
	}

	public synchronized boolean start(String id, Collection<Player> players, CinematicSequence sequence, Runnable onComplete) {
		if (plugin == null)
			return false;
		if (id == null || id.isBlank())
			return false;
		if (sequence == null || sequence.keyframes == null || sequence.keyframes.size() < 2)
			return false;

		List<Player> online = new ArrayList<>();
		if (players != null) {
			for (Player p : players) {
				if (p == null || !p.isOnline())
					continue;
				online.add(p);
			}
		}
		if (online.isEmpty())
			return false;

		// Detach these players from any previous sequences.
		for (Player p : online) {
			stopForPlayer(p.getUniqueId(), true);
		}

		Set<UUID> ids = new HashSet<>();
		for (Player p : online) {
			ids.add(p.getUniqueId());
		}

		Running r = new Running(id, ids, sequence, onComplete);

		// Save previous modes and switch to spectator.
		for (Player p : online) {
			try {
				r.previousGameModes.put(p.getUniqueId(), p.getGameMode());
			} catch (Throwable ignored) {
			}
			try {
				if (p.isInsideVehicle())
					p.leaveVehicle();
			} catch (Throwable ignored) {
			}
			try {
				p.setGameMode(GameMode.SPECTATOR);
			} catch (Throwable ignored) {
			}
		}

		runningById.put(id, r);
		for (UUID pid : ids) {
			idByPlayer.put(pid, id);
		}

		// Start at first keyframe.
		tryTeleportAllToFrame(r, sequence.keyframes.get(0));

		r.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			tick(r.id);
		}, 0L, 1L);

		return true;
	}

	private static float wrapAngleDeg(float a) {
		float x = a % 360.0f;
		if (x < 0.0f)
			x += 360.0f;
		return x;
	}

	private static float lerpAngleDeg(float a, float b, double t) {
		float aa = wrapAngleDeg(a);
		float bb = wrapAngleDeg(b);
		float d = (bb - aa) % 360.0f;
		if (d > 180.0f)
			d -= 360.0f;
		if (d < -180.0f)
			d += 360.0f;
		return (float) (aa + d * t);
	}

	private static double easeInOut(double t) {
		double x = Math.max(0.0, Math.min(1.0, t));
		// smoothstep
		return x * x * (3.0 - 2.0 * x);
	}

	private synchronized void tick(String id) {
		Running r = runningById.get(id);
		if (r == null)
			return;

		// Prune offline players.
		for (UUID pid : new HashSet<>(r.players)) {
			Player p = Bukkit.getPlayer(pid);
			if (p == null || !p.isOnline()) {
				removePlayerFromRunning(r, pid, true);
			}
		}
		if (r.players.isEmpty()) {
			stopSequenceInternal(id, true, false);
			return;
		}

		int total = r.sequence.totalTicks();
		int now = r.tick;

		// Sound events.
		try {
			for (CinematicSoundEvent se : r.sequence.soundEvents) {
				if (se == null)
					continue;
				if (se.atTick != now)
					continue;
				playSoundTo(r.players, se.sound, se.volume, se.pitch);
			}
		} catch (Throwable ignored) {
		}

		// Position interpolation.
		Location cam = sample(r.sequence, now);
		if (cam != null) {
			teleportAll(r.players, cam);
		}

		r.tick++;

		if (r.tick > total) {
			stopSequenceInternal(id, true, true);
			try {
				if (r.onComplete != null)
					r.onComplete.run();
			} catch (Throwable ignored) {
			}
		}
	}

	private static Location sample(CinematicSequence seq, int tick) {
		if (seq == null || seq.keyframes == null || seq.keyframes.size() < 2)
			return null;
		int t = Math.max(0, tick);

		int idx = 0;
		int local = t;
		for (; idx < seq.keyframes.size() - 1; idx++) {
			CinematicKeyframe k = seq.keyframes.get(idx);
			int dur = (k == null ? 0 : Math.max(0, k.durationTicks));
			if (idx == seq.keyframes.size() - 2)
				break;
			if (local <= dur)
				break;
			local -= dur;
		}

		CinematicKeyframe a = seq.keyframes.get(Math.max(0, Math.min(idx, seq.keyframes.size() - 2)));
		CinematicKeyframe b = seq.keyframes.get(Math.max(1, Math.min(idx + 1, seq.keyframes.size() - 1)));
		if (a == null || b == null || a.location == null || b.location == null)
			return null;

		Location la = a.location;
		Location lb = b.location;
		if (la.getWorld() == null || lb.getWorld() == null || !la.getWorld().equals(lb.getWorld())) {
			return la.clone();
		}

		// durationTicks == 0 is a hard cut: teleport immediately to the next keyframe.
		if (a.durationTicks <= 0) {
			return lb.clone();
		}

		int dur = Math.max(1, a.durationTicks);
		double raw = Math.max(0.0, Math.min(1.0, (double) local / (double) dur));
		double u = (seq.linearEasing ? raw : easeInOut(raw));

		double x = la.getX() + (lb.getX() - la.getX()) * u;
		double y = la.getY() + (lb.getY() - la.getY()) * u;
		double z = la.getZ() + (lb.getZ() - la.getZ()) * u;

		float yaw = lerpAngleDeg(la.getYaw(), lb.getYaw(), u);
		float pitch = (float) (la.getPitch() + (lb.getPitch() - la.getPitch()) * u);
		pitch = Math.max(-89.9f, Math.min(89.9f, pitch));

		Location out = new Location(la.getWorld(), x, y, z, yaw, pitch);
		return out;
	}

	private void playSoundTo(Set<UUID> players, Sound sound, float volume, float pitch) {
		if (sound == null)
			return;
		for (UUID id : new HashSet<>(players)) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				p.playSound(p.getLocation(), sound, volume, pitch);
			} catch (Throwable ignored) {
			}
		}
	}

	private void teleportAll(Set<UUID> players, Location loc) {
		if (loc == null || loc.getWorld() == null)
			return;
		for (UUID id : new HashSet<>(players)) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline())
				continue;
			try {
				p.teleport(loc);
			} catch (Throwable ignored) {
			}
		}
	}

	private void tryTeleportAllToFrame(Running r, CinematicKeyframe k) {
		if (r == null || k == null || k.location == null)
			return;
		teleportAll(r.players, k.location);
	}

	public synchronized void stopForPlayer(UUID playerId, boolean restoreMode) {
		if (playerId == null)
			return;
		String id = idByPlayer.get(playerId);
		if (id == null)
			return;
		Running r = runningById.get(id);
		if (r == null)
			return;
		removePlayerFromRunning(r, playerId, restoreMode);
		if (r.players.isEmpty()) {
			stopSequenceInternal(id, false, false);
		}
	}

	public synchronized void stopForPlayers(Collection<UUID> playerIds, boolean restoreMode) {
		if (playerIds == null || playerIds.isEmpty())
			return;
		for (UUID pid : new HashSet<>(playerIds)) {
			stopForPlayer(pid, restoreMode);
		}
	}

	private void removePlayerFromRunning(Running r, UUID playerId, boolean restoreMode) {
		if (r == null || playerId == null)
			return;
		if (!r.players.remove(playerId))
			return;
		idByPlayer.remove(playerId);

		if (restoreMode) {
			GameMode gm = r.previousGameModes.remove(playerId);
			Player p = Bukkit.getPlayer(playerId);
			if (p != null && p.isOnline()) {
				try {
					if (gm != null)
						p.setGameMode(gm);
				} catch (Throwable ignored) {
				}
			} else {
				// If the player disconnected while in spectator, their gamemode persists.
				// Restore it safely on next join.
				if (gm != null)
					pendingRestoreGameModes.put(playerId, gm);
			}
		}
	}

	/**
	 * Restore a player's previous gamemode if they disconnected mid-cinematic.
	 */
	public synchronized void restorePendingGameMode(Player player) {
		if (player == null)
			return;
		UUID id = player.getUniqueId();
		if (id == null)
			return;
		GameMode gm = pendingRestoreGameModes.remove(id);
		if (gm == null)
			return;
		try {
			player.setGameMode(gm);
		} catch (Throwable ignored) {
			// If we fail, keep the entry so we can try again next join.
			pendingRestoreGameModes.put(id, gm);
		}
	}

	private synchronized void stopSequenceInternal(String id, boolean restoreMode, boolean finished) {
		Running r = runningById.remove(id);
		if (r == null)
			return;

		// Optional: tiny UX cue when a cinematic ends naturally.
		if (finished) {
			for (UUID pid : new HashSet<>(r.players)) {
				Player p = Bukkit.getPlayer(pid);
				if (p == null || !p.isOnline())
					continue;
				try {
					Text.msg(p, "&7⮎ Kết thúc giới thiệu.");
				} catch (Throwable ignored) {
				}
			}
		}

		if (r.tickTask != null) {
			try {
				r.tickTask.cancel();
			} catch (Throwable ignored) {
			}
			r.tickTask = null;
		}

		for (UUID pid : new HashSet<>(r.players)) {
			removePlayerFromRunning(r, pid, restoreMode);
		}
	}

	public static float[] lookAt(Location from, Location to) {
		if (from == null || to == null)
			return new float[] { 0.0f, 0.0f };
		double dx = to.getX() - from.getX();
		double dy = to.getY() - from.getY();
		double dz = to.getZ() - from.getZ();

		double xz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, xz));
		pitch = Math.max(-89.9f, Math.min(89.9f, pitch));
		return new float[] { yaw, pitch };
	}

	public static List<CinematicSoundEvent> defaultArcadeIntroTune() {
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

		// Pitch Table (0.5 - 2.0)
		// F#=0.5, G=0.53, G#=0.56, A=0.6, A#=0.63, B=0.67, C=0.7, C#=0.75, D=0.8, D#=0.85, E=0.9, F=0.95
		// F#=1.0, G=1.06, G#=1.12, A=1.19, A#=1.26, B=1.33, C=1.41, C#=1.5, D=1.59, D#=1.68, E=1.78, F=1.89

		float Fs3 = 0.5f;
		float Gs3 = 0.56f;
		float A3 = 0.6f;
		float B3 = 0.67f;
		float Cs4 = 0.75f;
		float D4 = 0.8f;
		float E4 = 0.9f;
		float F4 = 0.95f; // E#
		float Fs4 = 1.0f;
		float Gs4 = 1.12f;
		float A4 = 1.19f;
		float B4 = 1.33f;
		float Cs5 = 1.5f;
		float D5 = 1.59f;
		float E5 = 1.78f;
		float F5 = 1.89f;
		float Fs5 = 2.0f;

		// 4 Bars of 8 steps (16th notes)
		// Progression: F#m - D - E - C#7

		float[] melody = new float[32];
		float[] bassLine = new float[32];

		// Bar 1: F#m (F# A C#)
		// Pattern: Root - 5th - 8ve - 5th - 3rd - 5th - Root - 5th
		// F#4, C#5, F#5(too high?), C#5, A4, C#5, F#4, C#5
		// Let's keep it simple: Pedal point
		// F#4 A4 C#5 A4 F#4 A4 C#5 A4
		float[] bar1 = {Fs4, A4, Cs5, A4, Fs4, A4, Cs5, A4};
		System.arraycopy(bar1, 0, melody, 0, 8);
		Arrays.fill(bassLine, 0, 8, Fs3);

		// Bar 2: D (D F# A)
		// D4 F#4 A4 F#4 D4 F#4 A4 F#4
		float[] bar2 = {D4, Fs4, A4, Fs4, D4, Fs4, A4, Fs4};
		System.arraycopy(bar2, 0, melody, 8, 8);
		Arrays.fill(bassLine, 8, 16, D4); // D3 not avail, use D4 low volume? Or just A3 (5th)? Let's use A3 as inverted bass or just D4. D4 is 0.8.

		// Bar 3: E (E G# B)
		// E4 G#4 B4 G#4 E4 G#4 B4 G#4
		float[] bar3 = {E4, Gs4, B4, Gs4, E4, Gs4, B4, Gs4};
		System.arraycopy(bar3, 0, melody, 16, 8);
		Arrays.fill(bassLine, 16, 24, E4);

		// Bar 4: C#7 (C# F G#) (F natural is E#)
		// C#4 F4 G#4 F4 C#4 F4 G#4 F4
		float[] bar4 = {Cs4, F4, Gs4, F4, Cs4, F4, Gs4, F4};
		System.arraycopy(bar4, 0, melody, 24, 8);
		Arrays.fill(bassLine, 24, 32, Cs4);

		// Loop 4 times
		int loops = 4;
		int loopLen = melody.length * step;

		for (int l = 0; l < loops; l++) {
			int offset = l * loopLen;

			for (int i = 0; i < melody.length; i++) {
				int t = offset + (i * step);

				// Lead (Arpeggio)
				out.add(new CinematicSoundEvent(t, lead, master * 0.7f, melody[i]));

				// Bass (Steady 8ths)
				// Lower volume for high-pitched "bass" notes
				out.add(new CinematicSoundEvent(t, bass, master * 0.5f, bassLine[i]));

				// Drums
				// Kick on beats 1 and 3 (0, 4 in 8-step bar)
				if (i % 4 == 0) {
					out.add(new CinematicSoundEvent(t, kick, master * 0.8f, 1.0f));
				}
				// Snare on beats 2 and 4 (2, 6)
				if (i % 4 == 2) {
					out.add(new CinematicSoundEvent(t, snare, master * 0.8f, 1.0f));
				}
				// Hat every step
				out.add(new CinematicSoundEvent(t, hat, master * 0.3f, 1.4f));
			}
		}

		// Outro: 2 Bars (16 steps)
		// Resolution to F#m
		int outroStart = loops * loopLen;
		float[] outroMelody = {
			Fs4, A4, Cs5, Fs5, Cs5, A4, Fs4, 0, // Arpeggio up and down
			Fs4, 0, Fs4, 0, Fs4, 0, 0, 0         // Final hits
		};

		for (int i = 0; i < outroMelody.length; i++) {
			int t = outroStart + (i * step);
			float p = outroMelody[i];

			if (p > 0) {
				out.add(new CinematicSoundEvent(t, lead, master * 0.8f, p));
				// Layer with PLING for final shimmer
				out.add(new CinematicSoundEvent(t, lead2, master * 0.5f, p));
			}

			// Simple bass root
			if (i % 4 == 0) {
				out.add(new CinematicSoundEvent(t, bass, master * 0.6f, Fs3));
				out.add(new CinematicSoundEvent(t, kick, master * 0.8f, 1.0f));
			}

			// Cymbal crash on first beat of outro
			if (i == 0) {
				out.add(new CinematicSoundEvent(t, Sound.ENTITY_GENERIC_EXPLODE, master * 0.5f, 1.2f));
			}
		}

		// Final hit at the very end
		int end = outroStart + (outroMelody.length * step);
		out.add(new CinematicSoundEvent(end, Sound.ENTITY_GENERIC_EXPLODE, master * 0.6f, 1.0f));
		out.add(new CinematicSoundEvent(end, lead, master, Fs4));

		return out;
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
}
