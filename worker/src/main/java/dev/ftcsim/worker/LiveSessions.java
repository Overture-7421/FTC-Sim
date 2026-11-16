package dev.ftcsim.worker;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.link.WorkerEnvironment;
import dev.ftcsim.simulator.RealtimeRunner;
import dev.ftcsim.simulator.SessionConfig;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Opens sessions that run for real: the physics world, the robot controller and the robot program, on a thread of their own. */
final class LiveSessions implements SessionOpener {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final SessionFactory factory;
    private final Path storageParent;

    /** @param storageParent where each session gets a directory for the robot controller's external storage */
    LiveSessions(SessionFactory factory, Path storageParent) {
        this.factory = factory;
        this.storageParent = storageParent;
    }

    @Override
    public RunningSession open(SessionRequest request) throws IOException {
        Files.createDirectories(storageParent);
        Path storage = Files.createTempDirectory(storageParent, "session-");
        try {
            SessionConfig config = factory.config(request, storage);
            RealtimeRunner runner = RealtimeRunner.open(config, request.speed());
            try {
                SessionInfo info = new SessionInfo(
                        config.field() == null ? null : config.field().description(), opModes(runner),
                        SessionFactory.robotInfo(config.robot()));
                return new LiveSession(runner, info, storage);
            } catch (Throwable failure) {
                runner.close();
                throw failure;
            }
        } catch (Throwable failure) {
            WorkerEnvironment.deleteTree(storage);
            throw failure;
        }
    }

    /** Asks the simulation thread, which is the only one that may touch the session, what the program declares. */
    private static List<OpModeDescriptor> opModes(RealtimeRunner runner) throws IOException {
        try {
            return onSimulationThread(runner, SimulationSession::opModes, "list the OpModes");
        } catch (RuntimeException failure) {
            throw new IOException("the robot program could not list its OpModes: " + failure, failure);
        }
    }

    /**
     * Has the simulation thread, the only one that may touch the session, do something, and waits for the answer.
     * What the work throws as a {@code RuntimeException} is thrown on to the caller as it is.
     *
     * @param what what the simulation is asked to do, for the messages
     * @throws IOException if the simulation stops, or does not answer in time
     */
    private static <T> T onSimulationThread(RealtimeRunner runner, Function<SimulationSession, T> work, String what) throws IOException {
        CompletableFuture<T> answer = new CompletableFuture<>();
        runner.post(session -> {
            try {
                answer.complete(work.apply(session));
            } catch (RuntimeException failure) {
                answer.completeExceptionally(failure);
            }
        });
        long deadline = System.nanoTime() + CALL_TIMEOUT.toNanos();
        try {
            while (true) {
                try {
                    return answer.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillWaiting) {
                    Throwable stopped = runner.failure();
                    if (stopped != null) {
                        throw new IOException("the simulation stopped when it was asked to " + what + ": " + stopped, stopped);
                    }
                    if (System.nanoTime() - deadline > 0) {
                        throw new IOException("the simulation did not " + what + " within " + CALL_TIMEOUT.toSeconds() + " seconds");
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while the simulation was asked to " + what, interrupted);
        } catch (ExecutionException failed) {
            throw (RuntimeException) failed.getCause();
        }
    }

    private static final class LiveSession implements RunningSession {
        private final RealtimeRunner runner;
        private final SessionInfo info;
        private final Path storage;

        LiveSession(RealtimeRunner runner, SessionInfo info, Path storage) {
            this.runner = runner;
            this.info = info;
            this.storage = storage;
        }

        @Override
        public SessionInfo info() {
            return info;
        }

        @Override
        public void init(String opMode) {
            callSimulation(session -> session.init(opMode), "press Init");
        }

        @Override
        public void start() {
            callSimulation(session -> session.start(), "press Start");
        }

        @Override
        public void stop() {
            runner.post(session -> session.stop());
        }

        @Override
        public void humanAction(String actionId) {
            callSimulation(session -> session.humanAction(actionId), "carry out the human action");
        }

        @Override
        public void armMatch(String autoOpMode, String teleOpMode) {
            callSimulation(session -> session.armMatch(autoOpMode, teleOpMode), "arm the match");
        }

        @Override
        public void startMatch() {
            callSimulation(session -> session.startMatch(), "start the match");
        }

        @Override
        public void reset() {
            callSimulation(session -> session.resetWorld(), "reset the world");
        }

        /** What the simulation refuses is thrown as it is; trouble reaching it is an {@code IllegalStateException}. */
        private void callSimulation(Consumer<SimulationSession> command, String what) {
            try {
                onSimulationThread(runner, session -> {
                    command.accept(session);
                    return null;
                }, what);
            } catch (IOException unreachable) {
                throw new IllegalStateException(unreachable.getMessage(), unreachable);
            }
        }

        @Override
        public void setGamepad(int gamepad, GamepadState state) {
            runner.setGamepad(gamepad, state);
        }

        @Override
        public void setPaused(boolean paused) {
            runner.setPaused(paused);
        }

        @Override
        public void setSpeed(double speed) {
            runner.setSpeed(speed);
        }

        @Override
        public SessionStatus status() {
            return runner.status();
        }

        @Override
        public WorldSnapshot snapshot() {
            return runner.snapshot();
        }

        @Override
        public Throwable failure() {
            return runner.failure();
        }

        @Override
        public void close() {
            try {
                runner.close();
            } finally {
                WorkerEnvironment.deleteTree(storage);
            }
        }
    }
}
