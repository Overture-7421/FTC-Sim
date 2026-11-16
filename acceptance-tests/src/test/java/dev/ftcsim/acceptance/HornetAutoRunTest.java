package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.session.Readout;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hornet's autonomous OpModes played as a timed match on the DECODE field, second by second: where the robot is, how
 * fast its flywheels and rollers turn, how many pieces it holds and what the field scored. The whole chain is in it:
 * the team's code, Pedro Pathing, the SDK, the hubs, the camera, the drivetrain, the intake and shooter, the physics and
 * the field's scoring. It reports what it sees and asserts only that each routine scores some classified artifacts, a
 * deliberately loose bar that still fails when any link of the chain breaks. It runs with
 * {@code -Pftcsim.hornetPackage=<folder>} (see {@link HornetFixture}).
 */
@Timeout(3600)
class HornetAutoRunTest {
    private static final long SECOND = 1_000_000_000L;
    private static final double INCH = 0.0254;
    // The close routines shoot from a short distance and the far ones from the edge of the field; with the launcher
    // fitted to Hornet's shooter table (docs/robots/hornet-profile.md) both score alike on both sides. They reach 15
    // and 9, the far routine having filled the ramp's nine places; the bars keep a margin.
    private static final int CLOSE_AT_LEAST = 12;
    private static final int FAR_AT_LEAST = 7;
    private static final int ATTEMPTS = 3;
    // Piece kinds of the DECODE field: purple and green artifacts.
    private static final int PURPLE = 101;
    private static final int GREEN = 102;

    private static void report(String line) {
        System.out.println("HORNET-AUTO> " + line);
    }

    /**
     * Where a team puts the robot before a match is where its code believes it starts. Hornet's code gives its start
     * poses in Pedro Pathing's frame (inches, 0 to 144 on both axes, heading from the +x axis); the field's frame has
     * its origin at the centre, so x is 72 - y and y is x - 72, and a heading is 90 degrees more.
     */
    private static double[] fieldPoseOfPedroPose(double pedroXInches, double pedroYInches, double pedroHeadingDegrees) {
        return new double[] {
            (72 - pedroYInches) * INCH, (pedroXInches - 72) * INCH, Math.toRadians(pedroHeadingDegrees + 90)
        };
    }

    /** Hornet's start pose for the blue side as its path classes write it, mirrored for red as its MirrorPaths does. */
    private static double[] hornetStart(String autoName, Alliance alliance) {
        double x = autoName.startsWith("Close") ? 27.393 : 56.0;
        double y = autoName.startsWith("Close") ? 131.862 : 8.0;
        double heading = autoName.startsWith("Close") ? 144.0 : 90.0;
        if (alliance == Alliance.RED) {
            x = 144 - x;
            heading = 180 - heading;
        }
        return fieldPoseOfPedroPose(x, y, heading);
    }

    private static String readouts(SimulationSession session, String... names) {
        List<String> wanted = List.of(names);
        return session.hardware().stream()
                .filter(readout -> wanted.contains(readout.name()))
                .map((Readout readout) -> readout.name() + " " + readout.value().split("  ")[0])
                .collect(Collectors.joining(", "));
    }

    private static long piecesNearTheRobot(SimulationSession session, BodyPose chassis) {
        return session.snapshot().bodies().stream()
                .filter((BodyState body) -> body.kind() == PURPLE || body.kind() == GREEN)
                .filter(body -> Math.hypot(body.x() - chassis.x(), body.y() - chassis.y()) < 0.35 && body.z() < 0.5)
                .count();
    }

    /** Plays the autonomous OpMode for the given seconds after Start and returns how many artifacts it classified. */
    private static int playTheAuto(String autoName, Alliance alliance, int seconds) throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = HornetMapping.spec();
        DecodeField field = new DecodeField();
        double[] start = hornetStart(autoName, alliance);
        try (TestSessions.Handle handle = TestSessions.open(
                profile, program, field, new FieldSetup(1, alliance), start[0], start[1], start[2])) {
            SimulationSession session = handle.session;
            report(String.format(
                    Locale.ROOT, "=== %s on %s, start (%.3f, %.3f) heading %.1f degrees", autoName, alliance,
                    start[0], start[1], Math.toDegrees(start[2])));
            session.armMatch(autoName, alliance == Alliance.BLUE ? "Teleop - Blue" : "Teleop - Red");
            session.runFor(2 * SECOND);
            java.util.Map<Integer, double[]> initial = new java.util.HashMap<>();
            session.snapshot().bodies().stream()
                    .filter(body -> body.kind() == PURPLE || body.kind() == GREEN)
                    .forEach(body -> initial.put(body.entityId(), new double[] {body.x(), body.y()}));
            session.startMatch();
            for (int second = 1; second <= seconds; second++) {
                session.runFor(SECOND);
                BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
                report(String.format(
                        Locale.ROOT, "t=%2d s  pose (%.2f, %.2f, yaw %5.2f)  speed %.2f m/s  near robot: %d  %s  score %s",
                        second, pose.x(), pose.y(), pose.yaw(), Math.hypot(pose.vx(), pose.vy()), piecesNearTheRobot(session, pose),
                        readouts(session, "shooterLeft", "shooterRight", "intake", "upMotor"),
                        session.score().lines().stream()
                                .filter(line -> line.count() > 0)
                                .map(line -> line.label() + " " + line.count() + " (" + line.points() + ")")
                                .collect(Collectors.joining("; "))));
                if (second == 5 || second == 6 || second == 7 || second == 12) {
                    report("  artifacts in the snapshot at t=" + second + ": " + session.snapshot().bodies().stream()
                            .filter(body -> body.kind() == PURPLE || body.kind() == GREEN).count() + "; moved: "
                            + session.snapshot().bodies().stream()
                            .filter(body -> body.kind() == PURPLE || body.kind() == GREEN)
                            .filter(body -> {
                                double[] was = initial.get(body.entityId());
                                return was == null || Math.hypot(body.x() - was[0], body.y() - was[1]) > 0.05;
                            })
                            .limit(12)
                            .map(body -> String.format(Locale.ROOT, "#%d(%.2f, %.2f, %.2f)", body.entityId(), body.x(), body.y(), body.z()))
                            .collect(Collectors.joining(" ")));
                }
            }
            session.problems().stream().limit(10).forEach(problem -> report("  problem: " + problem));
            if (!session.telemetry().warnings().isBlank()) {
                report("  warnings: " + session.telemetry().warnings().replace("\n", " | "));
            }
            return session.score().lines().stream()
                    .filter(line -> line.label().equals("AUTO CLASSIFIED"))
                    .mapToInt(line -> line.count())
                    .sum();
        }
    }

    /**
     * Runs are not repeatable (a library thread that a robot program waits on makes a loop cost a different amount of
     * simulated time each run, and an autonomous is a chaotic system), so the same auto classifies 6 or 9 artifacts on
     * different runs. What the test asks is that the auto can reach its bar: it gets up to {@value #ATTEMPTS} runs, and a
     * broken robot reaches it in none.
     */
    private static void assertScores(String autoName, Alliance alliance, int atLeast) throws Exception {
        int best = 0;
        for (int attempt = 1; attempt <= ATTEMPTS && best < atLeast; attempt++) {
            int classified = playTheAuto(autoName, alliance, 30);
            report(autoName + " on " + alliance + " attempt " + attempt + " classified " + classified);
            best = Math.max(best, classified);
        }
        assertTrue(best >= atLeast, autoName + " on " + alliance + " classified only " + best + " artifacts in " + ATTEMPTS + " runs");
    }

    @Test
    void closeAutoBlueScores() throws Exception {
        assertScores("Close Auto - Blue", Alliance.BLUE, CLOSE_AT_LEAST);
    }

    @Test
    void closeAutoRedScores() throws Exception {
        assertScores("Close Auto - Red", Alliance.RED, CLOSE_AT_LEAST);
    }

    @Test
    void farAutoBlueScores() throws Exception {
        assertScores("Far Auto - Blue", Alliance.BLUE, FAR_AT_LEAST);
    }

    @Test
    void farAutoRedScores() throws Exception {
        assertScores("Far Auto - Red", Alliance.RED, FAR_AT_LEAST);
    }
}
