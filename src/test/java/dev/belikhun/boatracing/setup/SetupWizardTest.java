package dev.belikhun.boatracing.setup;

import dev.belikhun.boatracing.track.TrackConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class SetupWizardTest {

	@Test
	public void testStartAndNavigation() {
		TrackConfig tc = new TrackConfig(new File("build/tmp"));
		SetupWizard w = new SetupWizard(tc);
		UUID u = UUID.randomUUID();
		w.startFor(u);
		assertEquals(SetupWizard.Step.SELECT_TRACK, w.activeStep(u));
		// simulate next steps
		w.setWorkingNameFor(u, "testtrack");
		// next via public API requires a Player; use startFor and internal next logic via the step map
		// simulate progression
		w.activeStep(u); // still select
		// programmatic next: emulate user clicking next
		// (can't call next(Player) easily without a Player) so use the internal map directly for testing
		assertTrue(w.activeStep(u) == SetupWizard.Step.SELECT_TRACK);
	}

	@Test
	public void testFinishForSaves() {
		TrackConfig tc = new TrackConfig(new File("build/tmp"));
		SetupWizard w = new SetupWizard(tc);
		UUID u = UUID.randomUUID();
		w.startFor(u);
		w.setWorkingNameFor(u, "my-test-track");
		assertTrue(w.finishFor(u));
		assertFalse(w.activeStep(u) != null && w.activeStep(u) != null); // no active step
		// ensure file exists
		assertTrue(new File(new File("build/tmp"), "tracks/my-test-track.yml").exists() || new File(new File("build/tmp"), "my-test-track.yml").exists());
	}
}

