package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.NetworkHooks;
import dev.ftcsim.contracts.hooks.NetworkRequest;
import dev.ftcsim.contracts.robot.RobotPhase;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the Robot Controller app does around the robot's life, as libraries see it through the SDK's annotated
 * hooks. The hook fixtures are added to the robot program for these tests only, because the SDK runs every hook it
 * finds in the program.
 */
class ControllerHooksTest {
    private static final String EVENTS = "ftcsim.test.hookEvents";
    private static final String PROGRAM = "ftcsim.programClasspath";
    private static final List<String> FLAGS = List.of(
            "ftcsim.test.failingHook", "ftcsim.test.missingClassHook", "ftcsim.test.listeningHook", "ftcsim.test.dyingThreadHook");
    private static final List<String> NORMAL_HOOKS = List.of(
            "OnCreate context=SimActivity",
            "OnCreateEventLoop manager=SimOpModeManager registeredForActivity=true",
            "WebHandlerRegistrar handler=true started=true");

    private final List<String> events = new CopyOnWriteArrayList<>();
    private String program;

    @BeforeEach
    void addTheHookFixturesToTheProgram() {
        program = System.getProperty(PROGRAM);
        System.setProperty(PROGRAM, program + File.pathSeparator + System.getProperty("ftcsim.hookClasspath"));
        System.getProperties().put(EVENTS, events);
        NetworkHooks.clearDeclinedRequests();
    }

    @AfterEach
    void removeThem() {
        System.setProperty(PROGRAM, program);
        System.getProperties().remove(EVENTS);
        FLAGS.forEach(System::clearProperty);
        NetworkHooks.clearDeclinedRequests();
    }

    @Test
    void hooksRunOnceInTheOrderTheAppCallsThemAndTheOnDestroyHookRunsWhenTheControllerShutsDown() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            assertEquals(NORMAL_HOOKS, events);
            assertEquals(RobotPhase.IDLE, world.host().phase());
            assertEquals("", world.host().telemetry().warnings());

            world.host().init("Counting");
            world.host().start();
            world.host().stop();
            assertEquals(NORMAL_HOOKS, events, "running OpModes calls no hook again");
        }

        assertEquals(List.of(NORMAL_HOOKS.get(0), NORMAL_HOOKS.get(1), NORMAL_HOOKS.get(2), "OnDestroy"), events);
        assertTrue(NetworkHooks.declinedRequests().isEmpty(), "the in-process web server never tried to listen");
        assertTrue(NetworkHooks.simulatedRequests().isEmpty(), "nor did it make a listener of its own");
    }

    @Test
    void aHookThatThrowsIsShownAsAWarningThatSurvivesInitialisingAnOpModeAndTheOtherHooksStillRun() throws Exception {
        System.setProperty("ftcsim.test.failingHook", "true");

        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            assertEquals(NORMAL_HOOKS, events);
            String warnings = world.host().telemetry().warnings();
            assertEquals(
                    List.of("failure while calling OnCreateMethods annotated method class "
                            + "dev.ftcsim.scenarios.hooks.FailingHooks.throwsAnException: java.lang.IllegalStateException: boom"),
                    warnings.lines().toList());

            world.host().init("Counting");
            assertEquals(warnings, world.host().telemetry().warnings(), "initialising an OpMode clears the SDK's own warnings, not this one");
        }
    }

    @Test
    void anErrorFromAHookIsShownTooAndTheHooksAfterItStillRun() throws Exception {
        System.setProperty("ftcsim.test.missingClassHook", "true");

        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            assertEquals(NORMAL_HOOKS, events, "the web handler hooks run even though an event loop hook failed");
            List<String> warnings = world.host().telemetry().warnings().lines().toList();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(
                    warnings.get(0).startsWith("failure while calling onCreateEventLoopMethods annotated method class "
                            + "dev.ftcsim.scenarios.hooks.FailingHooks.throwsAnError: java.lang.NoClassDefFoundError: android/missing/Thing"),
                    warnings.get(0));
        }
    }

    @Test
    void aLibraryThreadThatDiesIsShownAsAWarningAndTheControllerCarriesOn() throws Exception {
        System.setProperty("ftcsim.test.dyingThreadHook", "true");

        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            long deadline = System.nanoTime() + 10_000_000_000L;
            String warnings = world.host().telemetry().warnings();
            while (warnings.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
                warnings = world.host().telemetry().warnings();
            }
            assertEquals("Thread 'library-start' died: java.lang.IllegalStateException: the library gave up", warnings);

            world.host().init("Counting");
            world.awaitPhase(RobotPhase.INIT);
            assertEquals(warnings, world.host().telemetry().warnings());
        }
    }

    @Test
    void aLibraryThatStartsAServerGetsASimulatedListenerAndTheRequestIsRecorded() throws Exception {
        System.setProperty("ftcsim.test.listeningHook", "true");

        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            assertEquals("", world.host().telemetry().warnings());
            assertEquals(
                    List.of(new NetworkRequest(NetworkRequest.Kind.LISTEN, "*:18500")),
                    List.copyOf(NetworkHooks.simulatedRequests().keySet()));
            assertTrue(NetworkHooks.declinedRequests().isEmpty());
        }
    }
}
