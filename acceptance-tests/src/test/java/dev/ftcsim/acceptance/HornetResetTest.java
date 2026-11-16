package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.robot.RobotSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Resetting the world of a running session with Hornet: the program stops, the robot and the pieces go back where they
 * began, the program is still loaded and runs again. It runs with {@code -Pftcsim.hornetPackage=<folder>}
 * (see {@link HornetFixture}).
 */
@Timeout(3600)
class HornetResetTest {
    private static final long SECOND = 1_000_000_000L;

    private static BodyState chassis(SimulationSession session) {
        return session.snapshot().bodies().stream().filter(body -> body.entityId() == EntityIds.CHASSIS).findFirst().orElseThrow();
    }

    private static double distance(BodyState from, BodyState to) {
        return Math.hypot(to.x() - from.x(), to.y() - from.y());
    }

    private static GamepadState forward() {
        return GamepadState.sticks(0f, -1f, 0f, 0f);
    }

    @Test
    void theRobotProgramStopsTheRobotAndThePiecesGoBackAndTheProgramRunsAgainWithoutBeingLoadedAgain() throws Exception {
        RobotProgram program = HornetFixture.program(HornetFixture.prepared());
        RobotSpec profile = HornetMapping.spec();
        DecodeField field = new DecodeField();

        try (TestSessions.Handle handle = TestSessions.open(
                profile, program, field, new FieldSetup(1, Alliance.BLUE), 0.3, -0.3, Math.toRadians(90))) {
            SimulationSession session = handle.session;
            BodyState before = chassis(session);
            int bodiesBefore = session.snapshot().bodies().size();

            session.init("Teleop - Blue");
            session.runFor(2 * SECOND);
            session.start();
            session.runFor(SECOND);
            session.setGamepad(1, forward());
            session.runFor(3 * SECOND);
            BodyState moved = chassis(session);
            assertTrue(distance(before, moved) > 0.2, "the robot drove away from its start: " + before + " -> " + moved);

            long began = System.nanoTime();
            session.resetWorld();
            long millis = (System.nanoTime() - began) / 1_000_000L;
            System.out.println("HORNET-RESET> the world was reset in " + millis + " ms");

            assertEquals(RobotPhase.IDLE, session.phase(), "the program was stopped");
            BodyState after = chassis(session);
            assertEquals(before.x(), after.x(), 1e-3f, "back at its start");
            assertEquals(before.y(), after.y(), 1e-3f);
            assertEquals(bodiesBefore, session.snapshot().bodies().size(), "the pieces are where they began");

            session.setGamepad(1, GamepadState.NEUTRAL);
            session.init("Teleop - Blue");
            session.runFor(2 * SECOND);
            assertEquals(RobotPhase.INIT, session.phase(), "the same program initialises again: " + handle.log);
            session.start();
            session.runFor(SECOND);
            session.setGamepad(1, forward());
            session.runFor(3 * SECOND);
            assertTrue(distance(before, chassis(session)) > 0.2, "and drives the robot again");
        }
    }
}
