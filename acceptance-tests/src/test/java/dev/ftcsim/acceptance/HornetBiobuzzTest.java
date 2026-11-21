package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotProfiles;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SimulationSession;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hornet, written for DECODE, unmodified on the 2026-27 field, BIOBUZZ: each of its OpModes initialised, started and
 * stopped. Nothing here claims Hornet scores on this field, and it cannot: its camera's map is DECODE's, its intake is
 * built for 5 inch balls and its shooter aims at a corner that is not a goal. What it shows is that the program runs
 * as the code says on a field it was not written for, and that the simulator itself never fails. It runs with
 * {@code -Pftcsim.hornetPackage=<folder>} (see {@link HornetFixture}).
 */
@Timeout(3600)
class HornetBiobuzzTest {
    private static final long SECOND = 1_000_000_000L;
    private static final String PEDRO_MENU_WITHOUT_SELECTION = "No OpMode selected!";

    private static void report(String line) {
        System.out.println("HORNET-BIOBUZZ> " + line);
    }

    private static StartPose startPose(BiobuzzField field, Alliance alliance) {
        String wanted = alliance == Alliance.RED ? "RED wall" : "BLUE wall";
        return field.description().startPoses().stream().filter(pose -> pose.name().startsWith(wanted)).findFirst().orElseThrow();
    }

    @Test
    void everyOpModeIsInitialisedStartedAndStoppedOnTheBiobuzzField() throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = RobotDesign.biobuzz().spec(RobotProfiles.hornet());
        BiobuzzField field = new BiobuzzField();

        List<OpModeDescriptor> opModes;
        StartPose first = startPose(field, Alliance.BLUE);
        try (TestSessions.Handle handle = TestSessions.open(
                profile, program, field, new FieldSetup(1, Alliance.BLUE), first.x(), first.y(), first.headingRadians())) {
            opModes = handle.session.opModes();
        }
        report("the SDK host lists " + opModes.size() + " OpModes");

        for (OpModeDescriptor opMode : opModes) {
            Alliance alliance = opMode.name().toLowerCase(Locale.ROOT).contains("red") ? Alliance.RED : Alliance.BLUE;
            StartPose start = startPose(field, alliance);
            try (TestSessions.Handle handle = TestSessions.open(
                    profile, program, field, new FieldSetup(1, alliance), start.x(), start.y(), start.headingRadians())) {
                SimulationSession session = handle.session;
                report("=== " + opMode.name() + " (" + opMode.kind() + ") on " + alliance + " at " + start.name());
                session.init(opMode.name());
                session.runFor(2 * SECOND);
                describe(session, "after Init");
                assertEquals(RobotPhase.INIT, session.phase(), opMode.name() + " did not reach Init");
                session.start();
                session.runFor(8 * SECOND);
                describe(session, "8 s after Start");
                session.stop();
                session.runFor(SECOND);
                describe(session, "after Stop");
                assertEquals(RobotPhase.IDLE, session.phase(), opMode.name() + " did not stop");
                assertSimulatorIsSilent(opMode, session);
            }
        }
    }

    /**
     * The program's own errors are its business (Pedro's menu throws when it is started with nothing selected, as it
     * does on a robot), but the simulator reports nothing about itself: no warnings and no problems.
     */
    private static void assertSimulatorIsSilent(OpModeDescriptor opMode, SimulationSession session) {
        var telemetry = session.telemetry();
        assertTrue(telemetry.warnings().isBlank(), () -> opMode.name() + ": warnings " + telemetry.warnings());
        assertTrue(
                telemetry.error().isBlank() || telemetry.error().contains(PEDRO_MENU_WITHOUT_SELECTION),
                () -> opMode.name() + ": error " + telemetry.error());
        assertEquals(List.of(), session.problems(), opMode.name() + ": problems");
    }

    private static void describe(SimulationSession session, String when) {
        BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
        report(String.format(
                Locale.ROOT, "%s: phase=%s pose=(%.3f, %.3f, yaw %.3f) speed=%.2f m/s", when, session.phase(), pose.x(), pose.y(),
                pose.yaw(), Math.hypot(pose.vx(), pose.vy())));
        var telemetry = session.telemetry();
        telemetry.lines().stream().limit(6).forEach(line -> report("  telemetry: " + line));
        if (!telemetry.warnings().isBlank()) {
            report("  warnings: " + telemetry.warnings().replace("\n", " | "));
        }
        if (!telemetry.error().isBlank()) {
            report("  ERROR: " + telemetry.error().lines().limit(8).toList());
        }
        session.problems().stream().limit(6).forEach(problem -> report("  problem: " + problem));
    }
}
