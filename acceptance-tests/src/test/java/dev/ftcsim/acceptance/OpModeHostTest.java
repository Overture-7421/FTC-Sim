package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.contracts.robot.RobotHost;
import dev.ftcsim.contracts.robot.RobotPhase;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OpModeHostTest {
    private static final String FIXTURES = "dev.ftcsim.scenarios.opmodes.";

    @Test
    void listsTheEnabledOpModesOfTheRobotProgramWithoutRunningAnyOfTheirCode() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            assertEquals(RobotPhase.IDLE, world.host().phase());
            List<OpModeDescriptor> listed = world.host().opModes();

            // Representative entries: ungrouped, teleop and autonomous, and an OpMode whose class is abstract-based.
            List<OpModeDescriptor> expected = List.of(
                    new OpModeDescriptor("PlainTeleOp", "", OpModeKind.TELEOP, FIXTURES + "PlainTeleOp"),
                    new OpModeDescriptor("Sleeping Auto", "Ending", OpModeKind.AUTONOMOUS, FIXTURES + "SleepingAuto"),
                    new OpModeDescriptor("Gamepad Echo", "Input", OpModeKind.TELEOP, FIXTURES + "GamepadEchoTeleOp"),
                    new OpModeDescriptor("Bulk Cache Manual", "Hardware", OpModeKind.TELEOP, FIXTURES + "ManualBulkCacheTeleOp"));
            assertTrue(listed.containsAll(expected), listed.toString());
            // The Driver Station lists ungrouped OpModes first, then groups alphabetically, each by name.
            Comparator<OpModeDescriptor> driverStationOrder =
                    Comparator.comparing((OpModeDescriptor opMode) -> !opMode.group().isEmpty())
                            .thenComparing(OpModeDescriptor::group)
                            .thenComparing(OpModeDescriptor::name);
            assertEquals(listed.stream().sorted(driverStationOrder).toList(), listed);
            assertFalse(listed.stream().anyMatch(opMode -> opMode.name().equals("Disabled TeleOp")), "disabled OpModes are not listed");

            Map<String, Integer> counts = world.run("dev.ftcsim.scenarios.ReadProbe");
            assertEquals(Map.of(), counts, "finding OpModes must not construct them or initialise their classes");
        }
    }

    @Test
    void refusesToInitOpModesThatAreDisabledSystemOwnedOrUnknownAndToStartWithoutInit() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();

            assertThrows(IllegalArgumentException.class, () -> host.init("Disabled TeleOp"));
            assertThrows(IllegalArgumentException.class, () -> host.init("$Stop$Robot$"));
            assertThrows(IllegalArgumentException.class, () -> host.init("No Such OpMode"));
            assertThrows(IllegalStateException.class, host::start);

            assertEquals(RobotPhase.IDLE, host.phase());
            Map<String, Integer> counts = world.run("dev.ftcsim.scenarios.ReadProbe");
            assertFalse(counts.containsKey("disabled-ran"), counts.toString());
        }
    }

    @Test
    void anIterativeOpModeRunsInitInitLoopStartLoopAndStopThroughTheRealOpModeManager() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();

            host.init("Counting");
            assertEquals(RobotPhase.INIT, host.phase());
            world.awaitTelemetry("init_loop to run", snapshot -> snapshot.lines().contains("callback : init_loop"));

            host.start();
            assertEquals(RobotPhase.RUNNING, host.phase());
            world.awaitTelemetry("loop to run", snapshot -> snapshot.lines().contains("callback : loop"));

            host.stop();
            assertEquals(RobotPhase.IDLE, host.phase());

            Map<String, Integer> counts = world.run("dev.ftcsim.scenarios.ReadProbe");
            assertEquals(1, counts.get("init"));
            assertEquals(1, counts.get("start"));
            assertEquals(1, counts.get("stop"));
            assertTrue(counts.get("init_loop") >= 1, counts.toString());
            assertTrue(counts.get("loop") >= 1, counts.toString());
            world.awaitTelemetry("the idle status", snapshot -> snapshot.lines().equals(List.of("Status : Robot is stopped")));
        }
    }

    @Test
    void aLinearOpModeWaitsForStartRunsItsStepsAndEndsByItself() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();

            host.init("Linear Auto");
            world.awaitTelemetry("the OpMode to wait for start", snapshot -> snapshot.lines().contains("status : waiting for start"));
            assertNull(world.<Map<String, Integer>>run("dev.ftcsim.scenarios.ReadProbe").get("started"));

            host.start();
            world.awaitPhase(RobotPhase.IDLE);

            Map<String, Integer> counts = world.run("dev.ftcsim.scenarios.ReadProbe");
            assertEquals(1, counts.get("started"));
            assertEquals(3, counts.get("step"));
            assertEquals(1, counts.get("completed"));
        }
    }

    @Test
    void anOpModeCanAskForItsOwnStopAndStopRunsBeforeTheControllerIsIdle() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Self Stopper");
            world.host().start();
            world.awaitPhase(RobotPhase.IDLE);

            Map<String, Integer> counts = world.run("dev.ftcsim.scenarios.ReadProbe");
            assertTrue(counts.get("self-stopper-loop") >= 5, counts.toString());
            assertEquals(1, counts.get("self-stopper-stop"));
        }
    }

    @Test
    void terminateOpModeNowEndsTheOpModeQuietlyAndNoFurtherUserCodeRuns() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Terminator");
            world.host().start();
            world.awaitPhase(RobotPhase.IDLE);

            Map<String, Integer> counts = world.run("dev.ftcsim.scenarios.ReadProbe");
            assertEquals(1, counts.get("terminator-loop"));
            assertNull(counts.get("terminator-ran-past-terminate"));
            assertEquals("", world.host().telemetry().error());
            assertTrue(world.log().stream().noneMatch(line -> line.contains("uncaught exception")), world.log().toString());
        }
    }

    @Test
    void anExceptionInUserCodeShowsAsAnErrorAndTheControllerRecoversToIdle() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();

            host.init("Crasher");
            host.start();
            world.awaitPhase(RobotPhase.IDLE);

            String error = host.telemetry().error();
            assertTrue(error.contains("java.lang.IllegalStateException: boom from user code"), error);
            assertTrue(error.contains("CrashingTeleOp.loop"), error);
            assertTrue(
                    world.log().stream().anyMatch(line -> line.startsWith("E/OpModeManager: User code threw an uncaught exception")),
                    world.log().toString());

            host.init("Counting");
            assertEquals(RobotPhase.INIT, host.phase());
            assertEquals("", host.telemetry().error());
        }
    }

    @Test
    void aClassMissingFromUserCodeShowsAsAnErrorAndTheControllerStillRecoversToIdle() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();

            host.init("Missing Class");
            host.start();
            world.awaitPhase(RobotPhase.IDLE);

            assertTrue(host.telemetry().error().contains("java.lang.NoClassDefFoundError: android/view/NoSuchView"), host.telemetry().error());
            host.init("Counting");
            assertEquals(RobotPhase.INIT, host.phase());
            assertEquals("", host.telemetry().error());
            world.awaitTelemetry("the next OpMode to run", snapshot -> snapshot.lines().contains("callback : init_loop"));
        }
    }

    @Test
    void anErrorThatKillsTheOpModeThreadShowsAsAnErrorAndStopsTheOpMode() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();

            host.init("Erroring");
            host.start();
            world.awaitPhase(RobotPhase.IDLE);

            assertTrue(
                    host.telemetry().error().contains("java.lang.UnsatisfiedLinkError: no native method for the robot program"),
                    host.telemetry().error());
            host.init("Counting");
            world.awaitTelemetry("the next OpMode to run", snapshot -> snapshot.lines().contains("callback : init_loop"));
        }
    }

    @Test
    void stopInterruptsALinearOpModeThatIsBlockedInSleep() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();
            host.init("Sleeping Auto");
            host.start();
            world.awaitTelemetry("the OpMode to be asleep", snapshot -> snapshot.lines().contains("status : sleeping"));

            long begun = System.nanoTime();
            host.stop();

            assertTrue(System.nanoTime() - begun < 5_000_000_000L, "stop() waited for the sleep to run out");
            assertEquals(RobotPhase.IDLE, host.phase());
            Map<String, Integer> counts = world.run("dev.ftcsim.scenarios.ReadProbe");
            assertEquals(1, counts.get("woke-by-stop"));
            assertNull(counts.get("woke-by-timeout"));
        }
    }

    @Test
    void stopOnAnIdleControllerLeavesItIdleAndShowingItsStatus() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().stop();

            assertEquals(RobotPhase.IDLE, world.host().phase());
            world.awaitTelemetry("the idle status", snapshot -> snapshot.lines().equals(List.of("Status : Robot is stopped")));
        }
    }

    @Test
    void aRobotWorldHostsOneRobotControllerOnly() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            var second = assertThrows(java.lang.reflect.InvocationTargetException.class, world::createAnotherHost);

            assertTrue(second.getCause() instanceof IllegalStateException, String.valueOf(second.getCause()));
        }
    }

    @Test
    void anOpModeThatAsksTheHardwareMapForADeviceNoProviderMakesGetsTheSdksOwnError() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Unsupported Device");

            var snapshot = world.awaitTelemetry("the SDK's missing device error", s -> !s.error().isEmpty());

            assertTrue(
                    snapshot.error().contains("java.lang.IllegalArgumentException: Unable to find a hardware device with name \"mystery\" and type MysteryDevice"),
                    snapshot.error());
            assertTrue(snapshot.error().contains("The simulator creates devices on demand for:"), snapshot.error());
            world.awaitPhase(RobotPhase.IDLE);
        }
    }

    @Test
    void anOpModeThatAsksForOneNameAsTwoTypesGetsTheSdksErrorAndWhatTheNameAlreadyIs() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Wrong Device Type");

            var snapshot = world.awaitTelemetry("the SDK's wrong type error", s -> !s.error().isEmpty());

            assertTrue(
                    snapshot.error().contains("Unable to find a hardware device with name \"arm\" and type Servo: \"arm\" already exists as DcMotorImplEx"),
                    snapshot.error());
            world.awaitPhase(RobotPhase.IDLE);
        }
    }

    @Test
    void theSdksGlobalWarningReachesTheDisplayAndClearsAtTheNextInit() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            RobotHost host = world.host();

            host.init("Forgetful Auto");
            var snapshot = world.awaitTelemetry("the SDK's warning", s -> s.warnings().contains("waitForStart"));
            assertTrue(snapshot.warnings().startsWith("The OpMode which was just initialized ended prematurely"), snapshot.warnings());

            host.init("Counting");
            assertEquals("", host.telemetry().warnings());
        }
    }

    @Test
    void theSdkAndTheRobotProgramLogThroughTheSinkTheHostGave() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Counting");

            assertTrue(world.log().stream().anyMatch(line -> line.startsWith("V/AppUtil: initializing: getExternalStorageDirectory()=")), world.log().toString());
            assertTrue(world.log().stream().anyMatch(line -> line.startsWith("I/RobotCore: Attempting to switch to OpMode Counting")), world.log().toString());
        }
    }

    @Test
    void closingTheHostStopsTheRunningOpModeAndLeavesNoOpModeThreadsBehind() throws Exception {
        // An OpMode thread that another test's runaway got stuck is abandoned by design and lives on, so only threads
        // this world made count.
        Set<Thread> before = opModeThreads().collect(Collectors.toSet());
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Counting");
            world.host().start();
            world.awaitTelemetry("loop to run", snapshot -> snapshot.lines().contains("callback : loop"));
        }

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (newOpModeThreads(before).findAny().isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of(), newOpModeThreads(before).map(Thread::getName).toList());
    }

    private static Stream<Thread> opModeThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().contains("OpMode") && thread.isAlive());
    }

    private static Stream<Thread> newOpModeThreads(Set<Thread> before) {
        return opModeThreads().filter(thread -> !before.contains(thread));
    }
}
