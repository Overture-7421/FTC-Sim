package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotMapping;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SimulationSession;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hornet, unmodified, on the simulated robot and the DECODE field: what each of its OpModes does when a driver
 * initialises and starts it. A harness for bringing Hornet up, so it reports what it sees and asserts only that the
 * simulator itself never fails; it runs with {@code -Pftcsim.hornetPackage=<folder>} (see {@link HornetFixture}).
 */
@Timeout(3600)
class HornetBringupTest {
    private static final long SECOND = 1_000_000_000L;

    private static void report(String line) {
        System.out.println("HORNET-BRINGUP> " + line);
    }

    private static StartPose startPose(DecodeField field, Alliance alliance) {
        String wanted = alliance.name() + " GOAL SIDE";
        return field.description().startPoses().stream()
                .filter(pose -> pose.name().equals(wanted))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void everyOpModeIsInitialisedStartedAndStoppedOnTheDecodeField() throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = RobotDesign.decode().spec();
        DecodeField field = new DecodeField();

        List<OpModeDescriptor> opModes;
        StartPose first = startPose(field, Alliance.BLUE);
        try (TestSessions.Handle handle = TestSessions.open(
                profile, program, field, new FieldSetup(1, Alliance.BLUE), first.x(), first.y(), first.headingRadians())) {
            opModes = handle.session.opModes();
        }
        report("the SDK host lists " + opModes.size() + " OpModes: " + opModes.stream().map(OpModeDescriptor::name).toList());

        for (OpModeDescriptor opMode : opModes) {
            Alliance alliance = opMode.name().toLowerCase(java.util.Locale.ROOT).contains("red") ? Alliance.RED : Alliance.BLUE;
            StartPose start = startPose(field, alliance);
            try (TestSessions.Handle handle = TestSessions.open(
                    profile, program, field, new FieldSetup(1, alliance), start.x(), start.y(), start.headingRadians())) {
                SimulationSession session = handle.session;
                report("=== " + opMode.name() + " (" + opMode.kind() + ", " + opMode.className() + ") on " + alliance);
                session.init(opMode.name());
                session.runFor(2 * SECOND);
                describe(session, handle, "after Init");
                if (session.phase() == dev.ftcsim.contracts.robot.RobotPhase.INIT) {
                    session.start();
                    session.runFor(6 * SECOND);
                    describe(session, handle, "6 s after Start");
                    session.stop();
                    session.runFor(SECOND);
                    describe(session, handle, "after Stop");
                } else {
                    report("  (not started: the OpMode did not reach Init)");
                }
            }
        }
    }

    /**
     * A robot with no motor placed: the Devices page learns the hardware from an
     * OpMode's Init on it: Hornet asks for eight motors, the wheels among them, so every one is reported and the robot has
     * room for them or the OpMode dies in its constructor. Its colour sensor, which robot code reaches by name, is the one
     * thing the robot must declare.
     */
    @Test
    void anOpModeOfHornetInitialisesOnARobotWithNothingPlacedAndReportsEveryMotorItAsksFor() throws Exception {
        RobotProgram program = HornetFixture.program(HornetFixture.prepared());
        RobotSpec undescribed = RobotDesign.decode().spec(RobotMapping.empty("Undescribed")
                .withColorSensors(List.of(new RobotMapping.ColorSensorMapping("upSensor", 0.0, 0.0, 0.0, 0.0))));

        try (TestSessions.Handle handle = TestSessions.open(undescribed, program, null, null, 0, 0, 0)) {
            handle.session.init("Teleop - Blue");
            handle.session.runFor(2 * SECOND);

            org.junit.jupiter.api.Assertions.assertEquals(
                    dev.ftcsim.contracts.robot.RobotPhase.INIT, handle.session.phase(), handle.log.toString());
            List<String> motors = handle.session.unmapped().stream()
                    .filter(device -> device.kind() == dev.ftcsim.contracts.hardware.UnmappedDevice.Kind.MOTOR)
                    .map(dev.ftcsim.contracts.hardware.UnmappedDevice::name)
                    .toList();
            org.junit.jupiter.api.Assertions.assertEquals(8, motors.size(), motors.toString());
            org.junit.jupiter.api.Assertions.assertTrue(
                    motors.containsAll(List.of("frontLeft", "frontRight", "backLeft", "backRight", "intake", "upMotor", "shooterLeft", "shooterRight")),
                    motors.toString());
        }
    }

    /**
     * The SDK's scan of the program reports every class that cannot be loaded here with the whole stack of the failure,
     * about a hundred lines each: Hornet carries 72 such classes of the SDK and of libraries, which used to fill the log
     * with 8,000 lines. They are one line now, and the team's own classes would still be reported in full.
     */
    @Test
    void theClassesOfTheSdkThatCannotBeLoadedAreOneLineInTheLogAndNotEightThousand() throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = RobotDesign.decode().spec();

        try (TestSessions.Handle handle = TestSessions.open(profile, program, null, null, 0, 0, 0)) {
            List<String> classManager;
            synchronized (handle.log) {
                classManager = handle.log.stream().filter(line -> line.contains("/ClassManager:")).toList();
            }
            classManager.forEach(line -> report("  " + line));
            org.junit.jupiter.api.Assertions.assertEquals(1, classManager.size(), classManager.toString());
            org.junit.jupiter.api.Assertions.assertTrue(
                    classManager.get(0).contains("classes of the SDK and its libraries could not be loaded here"), classManager.get(0));
        }
    }

    private static GamepadState pad(boolean down, boolean rightBumper) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, false, down, false, false, false, false, false, false, false, rightBumper, false, false, false,
                false, false);
    }

    /** One press of a button as a person makes it: down for a tenth of a second, then up for a tenth. */
    private static void tap(SimulationSession session, GamepadState pressed) {
        session.setGamepad(1, pressed);
        session.runFor(SECOND / 10);
        session.setGamepad(1, GamepadState.NEUTRAL);
        session.runFor(SECOND / 10);
    }

    /** Initialises Tuning and selects one of the tuners in its Automatic folder, ready to be started. */
    private static void selectAutomaticTuner(SimulationSession session, int index) {
        session.init("Tuning");
        session.runFor(SECOND);
        tap(session, pad(true, false));
        tap(session, pad(false, true));
        for (int step = 0; step < index; step++) {
            tap(session, pad(true, false));
        }
        tap(session, pad(false, true));
    }

    /** What the Forward Velocity Tuner shows 3.3 s after Start, however time was sliced. */
    private static String telemetryAfterStart(RobotSpec profile, RobotProgram program, long sliceNanos) throws Exception {
        try (TestSessions.Handle handle = TestSessions.open(profile, program, null, null, 0, 0, 0)) {
            SimulationSession session = handle.session;
            selectAutomaticTuner(session, 0);
            session.start();
            for (long elapsed = 0; elapsed < 3_300_000_000L; elapsed += sliceNanos) {
                session.runFor(sliceNanos);
            }
            report("  slice " + sliceNanos + " ns: problems=" + session.problems() + " handoffs=" + session.diagnostics().handoffs());
            return String.join(" | ", session.telemetry().lines());
        }
    }

    private static double forwardVelocity(String telemetry) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Forward Velocity: (-?[0-9.]+)").matcher(telemetry);
        org.junit.jupiter.api.Assertions.assertTrue(matcher.find(), "the tuner should have reported by now: " + telemetry);
        return Double.parseDouble(matcher.group(1));
    }

    @Test
    void theSameTunerMeasuresTheSameVelocityWhateverTheSlicingOfSimulatedTime() throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = RobotDesign.decode().spec();

        String coarse = telemetryAfterStart(profile, program, SECOND / 10);
        String fine = telemetryAfterStart(profile, program, 3_300_000L);

        report("coarse slices: " + coarse);
        report("fine slices:   " + fine);
        double velocity = forwardVelocity(coarse);
        org.junit.jupiter.api.Assertions.assertTrue(velocity > 10, "the simulated robot should be driving: " + coarse);
        org.junit.jupiter.api.Assertions.assertEquals(velocity, forwardVelocity(fine), 0.05 * velocity);
    }

    /**
     * Pedro Pathing's own tuners, chosen from Hornet's Tuning menu with the d-pad and the right bumper, measure the
     * constants that Hornet's {@code Constants} holds for the real robot: a velocity at full power and the speed lost
     * while coasting, forward and sideways. Here they measure the simulated drivetrain, which tells how far it is
     * from the real one. Hornet's own figures are 76.84 and 58.91 in/s, and -32.88 and -72.06 in/s^2.
     */
    @Test
    void pedrosOwnTunersMeasureTheSimulatedDrivetrain() throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = RobotDesign.decode().spec();
        String[] tuners = {
            "Forward Velocity Tuner", "Lateral Velocity Tuner", "Forward Zero Power Acceleration Tuner",
            "Lateral Zero Power Acceleration Tuner"
        };
        String[] shows = {
            "Forward Velocity: ", "Strafe Velocity: ", "Forward Zero Power Acceleration (Deceleration): ",
            "Lateral Zero Power Acceleration (Deceleration): "
        };
        for (int i = 0; i < tuners.length; i++) {
            try (TestSessions.Handle handle = TestSessions.open(profile, program, null, null, 0, 0, 0)) {
                SimulationSession session = handle.session;
                session.init("Tuning");
                session.runFor(SECOND);
                tap(session, pad(true, false));
                tap(session, pad(false, true));
                for (int step = 0; step < i; step++) {
                    tap(session, pad(true, false));
                }
                tap(session, pad(false, true));
                session.start();
                long wallStart = System.nanoTime();
                long simStart = session.now();
                String found = null;
                for (int tenth = 0; tenth < 400 && found == null; tenth++) {
                    session.runFor(SECOND / 10);
                    for (String line : session.telemetry().lines()) {
                        if (line.startsWith(shows[i])) {
                            found = line;
                        }
                    }
                }
                double speedUp = (session.now() - simStart) / (double) (System.nanoTime() - wallStart);
                BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
                report(String.format(
                        java.util.Locale.ROOT, "%s -> %s (after %.1f s; chassis at %.2f, %.2f; ran %.1f times faster than real time)",
                        tuners[i], found == null ? "no result; telemetry " + session.telemetry().lines() : found, session.now() / 1e9,
                        pose.x(), pose.y(), speedUp));
                if (!session.telemetry().error().isBlank()) {
                    report("  ERROR: " + session.telemetry().error().lines().limit(8).toList());
                }
            }
        }
    }

    private static void describe(SimulationSession session, TestSessions.Handle handle, String when) {
        BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
        report(String.format(
                java.util.Locale.ROOT, "%s: phase=%s pose=(%.3f, %.3f, yaw %.3f) speed=%.2f m/s", when, session.phase(), pose.x(), pose.y(),
                pose.yaw(), Math.hypot(pose.vx(), pose.vy())));
        var telemetry = session.telemetry();
        telemetry.lines().stream().limit(14).forEach(line -> report("  telemetry: " + line));
        if (!telemetry.warnings().isBlank()) {
            report("  warnings: " + telemetry.warnings().replace("\n", " | "));
        }
        if (!telemetry.error().isBlank()) {
            report("  ERROR: " + telemetry.error().lines().limit(12).toList());
        }
        session.problems().stream().limit(10).forEach(problem -> report("  problem: " + problem));
        java.util.List<String> log = handle.log;
        java.util.Set<String> shown = new java.util.HashSet<>();
        synchronized (log) {
            for (int i = 0; i < log.size(); i++) {
                String line = log.get(i);
                if (line.startsWith("E/UncaughtException") && line.contains("Thread '") && shown.add(line)) {
                    report("  log: " + line);
                    for (int next = i + 1; next < Math.min(log.size(), i + 14); next++) {
                        report("      " + log.get(next));
                    }
                }
            }
        }
        log.stream()
                .filter(line -> line.startsWith("E/") && !line.contains("ClassManager") && !line.contains("\tat ") && !line.contains("UncaughtException"))
                .distinct()
                .limit(10)
                .forEach(line -> report("  log: " + line));
    }
}
