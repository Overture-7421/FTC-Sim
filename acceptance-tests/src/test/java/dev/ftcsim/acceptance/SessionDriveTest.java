package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A team's mecanum teleop, written without any knowledge of the simulator, driving the simulated robot across a
 * simulated floor: the real SDK, the real hub protocol, the hub emulation, the wheel plants and the physics world,
 * with every instant decided by the simulation kernel.
 */
@Timeout(180)
class SessionDriveTest {
    private static final long SECOND = 1_000_000_000L;

    private static RobotProfile hornet;

    @BeforeAll
    static void loadProfile() throws IOException {
        hornet = RobotProfileLoader.loadBundled("hornet-2026.json");
    }

    /** Where the robot ends up after holding the sticks for a while, as the true pose in the field frame. */
    private static BodyPose drive(GamepadState sticks, long duration) throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
            handle.session.init("Mecanum Drive");
            handle.session.start();
            handle.session.setGamepad(1, sticks);
            handle.session.runFor(duration);
            assertEquals(RobotPhase.RUNNING, handle.session.phase(), handle.log.toString());
            assertEquals(List.of(), handle.session.problems());
            return handle.session.world().bodyPose(EntityIds.CHASSIS);
        }
    }

    @Test
    void pushingTheLeftStickForwardDrivesTheRobotForwardAlongItsHeading() throws IOException {
        BodyPose pose = drive(GamepadState.sticks(0f, -1f, 0f, 0f), 2 * SECOND);

        assertTrue(pose.x() > 1.5, "the robot should have driven forward: " + pose);
        assertEquals(0.0, pose.y(), 0.15);
        assertEquals(0.0, pose.yaw(), 0.1);
        assertTrue(pose.vx() > 1.0, "it should still be moving: " + pose.vx());
    }

    @Test
    void pushingTheLeftStickRightStrafesTheRobotToItsRightWithoutTurningIt() throws IOException {
        BodyPose pose = drive(GamepadState.sticks(1f, 0f, 0f, 0f), 2 * SECOND);

        assertTrue(pose.y() < -1.0, "the robot should have moved to its right (-Y): " + pose);
        assertEquals(0.0, pose.x(), 0.3);
        assertEquals(0.0, pose.yaw(), 0.15);
    }

    @Test
    void pushingTheRightStickRightTurnsTheRobotClockwiseOnTheSpot() throws IOException {
        BodyPose pose = drive(GamepadState.sticks(0f, 0f, 1f, 0f), SECOND);

        // A full second at this rate is more than a turn, so the heading has wrapped; the rate says which way it turns.
        assertTrue(pose.wz() < -3.0, "the robot should be turning clockwise: " + pose);
        assertEquals(0.0, Math.hypot(pose.x(), pose.y()), 0.15);
    }

    @Test
    void theSameRunGivesTheSameWorldWhateverTheSlicingOfSimulatedTime() throws IOException {
        String firstHash = null;
        for (long slice : new long[] {Long.MAX_VALUE / 4, 7_000_000L, 250_000_000L}) {
            try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
                handle.session.init("Mecanum Drive");
                handle.session.start();
                handle.session.setGamepad(1, GamepadState.sticks(0.3f, -0.8f, 0.2f, 0f));
                long end = handle.session.now() + SECOND;
                while (handle.session.now() < end) {
                    handle.session.runUntil(Math.min(end, handle.session.now() + slice));
                }
                String hash = handle.session.world().stateHash();
                if (firstHash == null) {
                    firstHash = hash;
                }
                assertEquals(firstHash, hash, "slice " + slice);
                assertEquals(List.of(), handle.session.problems());
            }
        }
    }
}
