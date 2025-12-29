package dev.belikhun.boatracing.event;

public final class MarioKartPoints {
	private MarioKartPoints() {}

	// Mario Kart style points for up to 12 racers.
	// 1st..12th
	private static final int[] MK12 = new int[] { 15, 12, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 };

	public static int pointsForPlace(int oneBasedPlace) {
		int p = Math.max(1, oneBasedPlace);
		if (p > MK12.length)
			return 0;
		return MK12[p - 1];
	}

	public static int pointsForDnf() {
		// Per user requirement: DNF counts as 0 points.
		return 0;
	}

	public static int pointsForPlace(int oneBasedPlace, int participantsCount) {
		// participantsCount is currently informational; we still apply MK12 as a prefix.
		return pointsForPlace(oneBasedPlace);
	}
}
