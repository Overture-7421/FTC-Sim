package dev.ftcsim.simulator;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.contracts.hooks.NetworkHooks;
import dev.ftcsim.contracts.hooks.RobotHooks;
import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.robot.RobotHost;
import dev.ftcsim.runtime.HookRewriter;
import dev.ftcsim.runtime.RobotClassLoader;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Starts the robot world: loads the robot program and the SDK in a class loader of their own, with the JDK's clock,
 * sleep and wait calls redirected to the given hooks, and boots the simulated robot controller in it. The FTC SDK
 * keeps its state in singletons, so one JVM runs one robot world at a time.
 *
 * <p>Whatever the robot controller does runs with the robot world's class loader as the thread's context class loader,
 * so every thread that the robot program, the SDK or a library starts inherits it, and the threads of the robot world
 * are the threads that have it. That is how closing the world finds the threads that a library started and left running.
 */
public final class RobotWorldLauncher {

    private static final String FACTORY = "dev.ftcsim.sdk.SdkRobotHostFactory";

    /** How long closing the world waits, in real time, for its threads to end after they were interrupted. */
    private static final long THREADS_END_WITHIN_NANOS = 1_000_000_000L;

    /** A running robot controller and the class loader that holds everything it loaded. */
    public static final class RobotWorld implements AutoCloseable {
        private final RobotClassLoader loader;
        private final RobotHost scopedHost;
        private final List<String> shutdownProblems = new CopyOnWriteArrayList<>();
        private volatile Supplier<Collection<String>> reportedThreads = List::of;

        private RobotWorld(RobotClassLoader loader, RobotHost host) {
            this.loader = loader;
            this.scopedHost = (RobotHost) Proxy.newProxyInstance(
                    RobotHost.class.getClassLoader(), new Class<?>[] {RobotHost.class}, new InWorld(loader, host));
        }

        /** The robot controller, which runs with the robot world as the context class loader of the thread that calls it. */
        public RobotHost host() {
            return scopedHost;
        }

        /**
         * Names of threads of the robot world that were reported as problems already, such as the robot thread that
         * would not end and was abandoned, and which closing the world does not report again.
         */
        public void alreadyReported(Supplier<Collection<String>> threadNames) {
            reportedThreads = threadNames;
        }

        /** What was found wrong when the world was closed: threads of the robot world that would not end. */
        public List<String> shutdownProblems() {
            return List.copyOf(shutdownProblems);
        }

        /**
         * Stops the controller and drops the robot world. Threads that a library started and left running are
         * interrupted and given a few seconds to end; one that ignores the interrupt cannot be killed, so it is left
         * and recorded in {@link #shutdownProblems()}.
         */
        @Override
        public void close() throws IOException {
            try {
                scopedHost.close();
            } finally {
                try {
                    endThreads();
                } finally {
                    Hooks.installDefault();
                    loader.close();
                }
            }
        }

        private void endThreads() {
            NetworkHooks.closeSimulatedListeners();
            Thread self = Thread.currentThread();
            Collection<String> reported = reportedThreads.get();
            List<Thread> threads = threadsOf(loader).stream()
                    .filter(thread -> thread != self && !reported.contains(thread.getName()))
                    .toList();
            threads.forEach(Thread::interrupt);
            long deadline = System.nanoTime() + THREADS_END_WITHIN_NANOS;
            while (System.nanoTime() < deadline && threads.stream().anyMatch(Thread::isAlive)) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    shutdownProblems.add("the robot-world thread '" + thread.getName() + "' did not end when the session closed, though it"
                            + " was interrupted (" + describe(thread) + "); it cannot be killed, so it keeps running outside the simulation");
                }
            }
        }

        private static String describe(Thread thread) {
            StackTraceElement[] stack = thread.getStackTrace();
            return thread.getState() + (stack.length == 0 ? "" : " in " + stack[0]);
        }
    }

    /** Runs each call of the host with the robot world's class loader as the thread's context class loader. */
    private record InWorld(ClassLoader loader, RobotHost host) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            Thread thread = Thread.currentThread();
            ClassLoader before = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                return method.invoke(host, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            } finally {
                thread.setContextClassLoader(before);
            }
        }
    }

    private RobotWorldLauncher() {}

    /** The live threads whose context class loader is {@code loader}, which is to say the ones started by what runs in it. */
    private static List<Thread> threadsOf(ClassLoader loader) {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] threads = new Thread[root.activeCount() + 16];
        int count = root.enumerate(threads, true);
        List<Thread> found = new ArrayList<>();
        for (Thread thread : Arrays.copyOf(threads, count)) {
            if (thread.isAlive() && thread.getContextClassLoader() == loader) {
                found.add(thread);
            }
        }
        return found;
    }

    /**
     * @param storageRoot where the controller's external storage lives
     * @param log         receives every log line the SDK and the robot program produce
     */
    public static RobotWorld launch(
            RobotProgram program, RobotHooks hooks, Path storageRoot, Consumer<String> log, RobotEnvironment environment)
            throws IOException {
        Hooks.install(hooks);
        RobotClassLoader loader = new RobotClassLoader(
                program.robotClasspath(),
                RobotWorldLauncher.class.getClassLoader(),
                name -> name.startsWith("dev.ftcsim.contracts."),
                new HookRewriter());
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Class<?> factory = loader.loadClass(FACTORY);
            RobotHost host = (RobotHost) factory.getMethod(
                            "create", Path.class, List.class, List.class, Consumer.class, RobotEnvironment.class)
                    .invoke(null, storageRoot, program.programClasspath(), program.teamClasspath(), log, environment);
            return new RobotWorld(loader, host);
        } catch (InvocationTargetException failure) {
            closeAfterFailure(loader);
            throw failure.getCause() instanceof RuntimeException cause
                    ? cause
                    : new IllegalStateException("the robot controller did not start", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            closeAfterFailure(loader);
            throw new IllegalStateException("the robot world is missing its SDK adapters (" + FACTORY + "): is sdk-common on the robot class path?", failure);
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    private static void closeAfterFailure(RobotClassLoader loader) throws IOException {
        Hooks.installDefault();
        loader.close();
    }
}
