package dev.belikhun.boatracing.race;

import org.junit.jupiter.api.Test;
import dev.belikhun.boatracing.track.TrackConfig;

import java.io.File;
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
}

