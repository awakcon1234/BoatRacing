package dev.belikhun.boatracing.util;

import java.util.Locale;

/**
 * Centralized time formatting utilities.
 *
 * Conventions used across the plugin:
 * - Race/stopwatch times (finish time, race timer, deltas) use {@link #formatStopwatchMillis(long)}
 *   (mm:ss.SSS, or h:mm:ss.SSS when hours > 0).
 * - Long-lived durations (e.g. total time raced) use {@link #formatDurationShort(long)}
 *   (mm:ss, or h:mm:ss when hours > 0).
 * - Countdowns use {@link #formatCountdownSeconds(int)} (Xs for < 60, else m:ss).
 *
 * These methods intentionally return uncolored strings. Callers should apply colors in
 * their own output format (legacy '&' or MiniMessage) consistently.
 */
public final class Time {
	private Time() {}

	public static String formatStopwatchMillis(long ms) {
		long t = Math.max(0L, ms);

		long totalSec = t / 1000L;
		long hours = totalSec / 3600L;
		long minutes = (totalSec % 3600L) / 60L;
		long seconds = totalSec % 60L;
		long msPart = t % 1000L;

		if (hours > 0L) {
			return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, msPart);
		}

		return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, msPart);
	}

	public static String formatDurationShort(long ms) {
		long t = Math.max(0L, ms);

		long totalSec = t / 1000L;
		long hours = totalSec / 3600L;
		long minutes = (totalSec % 3600L) / 60L;
		long seconds = totalSec % 60L;

		if (hours > 0L) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
		}

		return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
	}

	public static String formatCountdownSeconds(int sec) {
		int s = Math.max(0, sec);
		if (s >= 60) {
			int m = s / 60;
			int r = s % 60;
			return String.format(Locale.ROOT, "%d:%02d", m, r);
		}
		return s + "s";
	}
}
