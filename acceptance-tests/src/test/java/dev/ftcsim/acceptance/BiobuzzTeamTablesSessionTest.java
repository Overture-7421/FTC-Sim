package dev.ftcsim.acceptance;

import static dev.ftcsim.acceptance.SessionTelemetry.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Pose;
import dev.ftcsim.scenarios.opmodes.BiobuzzShotSolver.Solution;
import dev.ftcsim.scenarios.opmodes.BiobuzzTeamTables;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The shot of the team's own BIOBUZZ code, its tables of flywheel speed and hood position against distance
 * ({@link BiobuzzTeamTables}), played into the real HIVE by the simulated robot: held from the places on the open side of the HIVE,
 * the tables' own numbers have to tip it.
 */
@Timeout(600)
class BiobuzzTeamTablesSessionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final double INCH = 0.0254;
    private static final BiobuzzField FIELD = new BiobuzzField();

    private static GamepadState shoot() {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, false, false, false, false, false, true, false, false, false, false,
                false);
    }

    private static int tips(SimulationSession session) {
        return session.score().lines().stream().filter(line -> line.label().equals("HIVE TIP in TELEOP")).findFirst().orElseThrow().count();
    }

    @Test
    void theTablesTargetIsTheCellTheSolverAimsAtAndTheTablesReadBackTheirOwnPoints() {
        double[] red = BiobuzzTeamTables.target(BiobuzzHive.Alliance.RED, BiobuzzHive.Tilt.AUDIENCE_UP);
        assertEquals(14.33 * INCH, red[0], 1e-9, "Pedro's (59.26, 57.67) is this field's x = 72 - 57.67");
        assertEquals(-12.74 * INCH, red[1], 1e-9, "and y = 59.26 - 72, the red HIVE's pivot line");
        double[] blue = BiobuzzTeamTables.target(BiobuzzHive.Alliance.BLUE, BiobuzzHive.Tilt.SCORING_UP);
        assertEquals(-red[0], blue[0], 1e-9, "the blue HIVE's scoring CELL is the red one's audience CELL turned half a way round");
        assertEquals(-red[1], blue[1], 1e-9);

        for (int i = 0; i < BiobuzzTeamTables.DISTANCES_IN.length; i++) {
            double inches = BiobuzzTeamTables.DISTANCES_IN[i];
            Pose pose = new Pose(red[0] + inches * INCH + 0.0894588, red[1], Math.PI);
            Solution solution = BiobuzzTeamTables.PLAN.solve(pose, BiobuzzHive.Alliance.RED, BiobuzzHive.Tilt.AUDIENCE_UP, Double.NaN);
            assertTrue(solution.feasible, inches + " in");
            assertEquals(BiobuzzTeamTables.FLYWHEEL_TICKS[i], solution.flywheelTicksPerSecond, 1e-6, "speed at " + inches + " in");
            assertEquals((BiobuzzTeamTables.HOOD_COMMAND[i] + 1.0) / 2.0, solution.hoodPosition, 1e-9, "hood at " + inches + " in");
        }
        Pose tooClose = new Pose(red[0] + 30 * INCH + 0.0894588, red[1], Math.PI);
        assertTrue(!BiobuzzTeamTables.PLAN.solve(tooClose, BiobuzzHive.Alliance.RED, BiobuzzHive.Tilt.AUDIENCE_UP, Double.NaN).feasible,
                "the team's table starts at 36 in; its code would throw nearer than that");
    }

    private static Stream<Arguments> places() {
        // Near, to the side, far and in the corner of the open (audience) side of the red HIVE, 41 to 72 in from its CELL. Further round
        // than y = 1.1 the camera never sees the HIVE's tags, so the pose stays the approximate start and nothing is fed.
        double[][] red = {{1.4, -0.5}, {1.5, -1.0}, {1.0, -1.5}, {1.55, -1.55}, {1.5, 0.8}, {1.5, 1.1}};
        return Stream.of(red).flatMap(place -> Stream.of(
                Arguments.of(Alliance.RED, place[0], place[1]),
                Arguments.of(Alliance.BLUE, -place[0], -place[1])));
    }

    @ParameterizedTest(name = "{0} from x={1} y={2}")
    @MethodSource("places")
    void theTeamsTablesTipTheAlliancesOwnHiveFromEveryPlaceOnTheOpenSide(Alliance alliance, double x, double y) throws IOException {
        double hiveY = alliance == Alliance.RED ? -0.3236 : 0.3236;
        int side = alliance == Alliance.RED ? 1 : -1;
        double heading = Math.atan2(hiveY - y, side * 0.4 - x);
        String opMode = alliance == Alliance.RED ? "BIOBUZZ Team Tables Red" : "BIOBUZZ Team Tables Blue";
        try (TestSessions.Handle handle = TestSessions.open(
                BiobuzzTestRobot.spec(), TestSessions.program(), FIELD, new FieldSetup(1, alliance), x, y, heading)) {
            SimulationSession session = handle.session;
            session.init(opMode);
            session.runFor(SECOND);
            assertEquals(RobotPhase.INIT, session.phase(), session.telemetry().toString());
            session.start();
            session.runFor(SECOND / 2);
            session.setGamepad(1, shoot());
            for (int i = 0; i < 250 && tips(session) == 0; i++) {
                session.runFor(SECOND / 10);
            }
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND);

            String summary = text(session, "Estimate") + " | " + text(session, "Aim") + " | " + text(session, "Shooter") + " | "
                    + text(session, "Mechanisms");
            assertEquals(1, tips(session), "the team's table tipped the HIVE: " + summary);
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }
}
