package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.hooks.RobotHooks;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.robot.RobotHost;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.runtime.RobotClassLoader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A robot world with a simulated robot controller running in it, driven the way the simulator drives it: the
 * test thread plays the Driver Station and the event loop, calling {@link RobotHost#eventLoop} about once per
 * real millisecond while it waits for the robot program to react.
 */
final class HostedRobotWorld extends RobotWorld {
    private static final String FACTORY = "dev.ftcsim.sdk.SdkRobotHostFactory";
    private static final long PATIENCE_NANOS = 10_000_000_000L;

    private final Path storage;
    private final RobotHost host;
    private final List<String> log;
    private final RobotEnvironment environment;
    private GamepadState first = GamepadState.NEUTRAL;
    private GamepadState second = GamepadState.NEUTRAL;

    private HostedRobotWorld(RobotClassLoader loader, Path storage, RobotHost host, List<String> log, RobotEnvironment environment) {
        super(loader);
        this.storage = storage;
        this.host = host;
        this.log = log;
        this.environment = environment;
    }

    /** A robot controller with only its Control Hub, whose clock is the real one. */
    static HostedRobotWorld launch(RobotHooks hooks) throws Exception {
        return launch(hooks, TestRobot.controlHubOnly(System::nanoTime).environment());
    }

    static HostedRobotWorld launch(RobotHooks hooks, RobotEnvironment environment) throws Exception {
        RobotClassLoader loader = newLoader(hooks);
        Path storage = Files.createTempDirectory("ftcsim-sdcard");
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        try {
            return new HostedRobotWorld(loader, storage, createHost(loader, storage, log::add, environment), log, environment);
        } catch (Throwable failure) {
            loader.close();
            throw failure;
        }
    }

    private static RobotHost createHost(
            RobotClassLoader loader, Path storage, Consumer<String> log, RobotEnvironment environment) throws Exception {
        try {
            return (RobotHost) callFactory(loader.loadClass(FACTORY), storage, log, environment);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    private static Object callFactory(Class<?> factory, Path storage, Consumer<String> log, RobotEnvironment environment)
            throws Exception {
        return factory.getMethod("create", Path.class, List.class, Consumer.class, RobotEnvironment.class)
                .invoke(null, storage, classpath("ftcsim.programClasspath"), log, environment);
    }

    /** Asks the robot world for a second robot controller, which it must refuse; the refusal arrives as an InvocationTargetException. */
    Object createAnotherHost() throws Exception {
        return callFactory(load(FACTORY), storage, line -> {}, environment);
    }

    RobotHost host() {
        return host;
    }

    /** Every line the SDK and the robot program logged so far, in logcat style. */
    List<String> log() {
        synchronized (log) {
            return List.copyOf(log);
        }
    }

    /** What the two gamepads report from now on; the event loop notices at once, as the simulator's own loop would. */
    void hold(GamepadState firstGamepad, GamepadState secondGamepad) throws InterruptedException {
        first = firstGamepad;
        second = secondGamepad;
        pass();
    }

    /** One pass of the event loop with the gamepads as they are held, followed by a short real-time pause. */
    TelemetrySnapshot pass() throws InterruptedException {
        host.eventLoop(first, second);
        Thread.sleep(1);
        return host.telemetry();
    }

    TelemetrySnapshot awaitTelemetry(String expectation, Predicate<TelemetrySnapshot> condition) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE_NANOS;
        TelemetrySnapshot snapshot = pass();
        while (!condition.test(snapshot)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Gave up waiting for " + expectation + ". The robot is " + host.phase()
                        + " and shows " + snapshot + "\nLog:\n" + String.join("\n", log()));
            }
            snapshot = pass();
        }
        return snapshot;
    }

    void awaitPhase(RobotPhase expected) throws InterruptedException {
        awaitTelemetry("the robot to become " + expected, snapshot -> host.phase() == expected);
    }

    @Override
    public void close() throws IOException {
        try {
            host.close();
        } finally {
            try {
                super.close();
            } finally {
                deleteTree(storage);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
