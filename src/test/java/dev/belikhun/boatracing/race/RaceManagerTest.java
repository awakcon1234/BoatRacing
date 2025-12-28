package dev.belikhun.boatracing.race;

import org.junit.jupiter.api.Test;
import dev.belikhun.boatracing.track.TrackConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class RaceManagerTest {

	@Test
	public void testCheckpointSequenceAdvancesLap() {
		TrackConfig tc = new TrackConfig(new File("build/tmp"));

		RaceManager rm = new RaceManager(tc);
		UUID u = UUID.randomUUID();
		rm.addParticipantForTests(u);
		// simulate track with 2 checkpoints
		rm.setTestCheckpointCount(2);

		// pass through both checkpoints in order
		rm.checkpointReached(u, 0);
		assertEquals(0, rm.getParticipantState(u).currentLap);
		rm.checkpointReached(u, 1);

		// last checkpoint reached -> waiting for finish
		assertEquals(0, rm.getParticipantState(u).currentLap);
		assertTrue(rm.getParticipantState(u).awaitingFinish);

		// crossing finish completes the lap
		rm.finishCrossedForTests(u);
		assertEquals(1, rm.getParticipantState(u).currentLap);
	}

	// Pit mechanic removed: penalty tests omitted

	@Test
	public void testFinishPlayerSetsFinished() {
		TrackConfig tc = new TrackConfig(new File("build/tmp"));
		RaceManager rm = new RaceManager(tc);
		UUID u = UUID.randomUUID();
		rm.addParticipantForTests(u);
		rm.finishPlayer(u);
		assertTrue(rm.getParticipantState(u).finished);
		assertTrue(rm.getParticipantState(u).finishPosition > 0);
	}

	@Test
	public void testLapProgressDoesNotResetAfterLastCheckpointBeforeFinish() throws Exception {
		// Regression: after passing the last checkpoint, racers are awaiting the finish.
		// Progress must keep increasing (monotonic) and must not snap back to ~0% before finish.
		TrackConfig tc = new TrackConfig(new File("build/tmp"));
		RaceManager rm = new RaceManager(tc);

		// Force progress computation to use test checkpoint count.
		rm.setTestCheckpointCount(2);

		UUID u = UUID.randomUUID();
		rm.addParticipantForTests(u);
		RaceManager.ParticipantState st = rm.getParticipantState(u);
		assertNotNull(st);

		// Prepare a fake path with wrap-around finish gate.
		// Path indices: 0..999
		int n = 1000;
		List<org.bukkit.Location> path = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
			path.add(null);

		int cp1 = 200;
		int cp2 = 800;
		int finish = 50; // finish near start -> wrap-around segment from cp2 -> finish
		int[] gates = new int[] { cp1, cp2, finish };

		setPrivateField(rm, "pathReady", true);
		setPrivateField(rm, "path", path);
		setPrivateField(rm, "gateIndex", gates);

		// State: last checkpoint passed, awaiting finish.
		st.nextCheckpointIndex = 2;
		st.awaitingFinish = true;

		st.lastPathIndex = 820;
		double a = rm.getLapProgressRatio(u);

		// Move forward across wrap-around: near the finish gate.
		st.lastPathIndex = 30;
		double b = rm.getLapProgressRatio(u);

		assertTrue(a > 0.0, "expected some progress after last checkpoint");
		assertTrue(b >= a, "progress must be monotonic after last checkpoint");
		assertTrue(b > 0.5, "should be well past halfway near finish");
	}

	private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
		java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(target, value);
	}
}

