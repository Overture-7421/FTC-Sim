package dev.ftcsim.worker;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** Sessions that are only a list of what was done to them, for the tests of the server. */
final class FakeSessions implements SessionOpener {

    static final SessionInfo INFO = new SessionInfo(
            new FieldDescription("fake", "Fake field", List.of(), List.of(), List.of(), List.of(), List.of(new HumanAction("feed", "Feed a piece"))),
            List.of(new OpModeDescriptor("Mecanum Drive", "Drive", OpModeKind.TELEOP, "x.Mecanum")),
            new RobotInfo("fake robot", 0.4, 0.4, 0.3, List.of(), List.of(), List.of()));

    static final SessionInfo INFO_WITHOUT_FIELD = new SessionInfo(null, INFO.opModes(), INFO.robot());

    static final Catalogue CATALOGUE = new Catalogue(
            List.of(
                    new FieldChoice("none", "Bare floor", List.of(), List.of()),
                    new FieldChoice("fake", "Fake field", List.of(new StartPose("origin", 0, 0, 0)), List.of(new HumanAction("feed", "Feed a piece")))),
            List.of("robot.json"));

    /** Everything that happened to any session, in the order it happened. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    final List<FakeSession> opened = new CopyOnWriteArrayList<>();

    /** When set, opening fails with it. */
    volatile IOException failToOpen;

    /** When set, the commands that the simulation can refuse (Init, Start, a human action, a match) are refused with it. */
    volatile RuntimeException refusal;

    /** When set, closing a session waits for this and so never ends unless the test lets it. */
    volatile CountDownLatch holdClose;

    volatile SessionInfo info = INFO;

    @Override
    public RunningSession open(SessionRequest request) throws IOException {
        calls.add("open " + request.profile() + " speed " + request.speed());
        if (failToOpen != null) {
            throw failToOpen;
        }
        FakeSession session = new FakeSession(opened.size() + 1);
        opened.add(session);
        return session;
    }

    static WorldSnapshot snapshot(long step) {
        return new WorldSnapshot(step, step * 4_166_666L, List.of(new BodyState(1, 1, step, 0, 0, 0, 0, 0, 1)));
    }

    static SessionStatus status(long simTimeNanos) {
        return new SessionStatus(
                simTimeNanos, RobotPhase.IDLE, new TelemetrySnapshot(List.of(), "", ""), new Score(List.of()), List.of(), List.of(), 1.0,
                1.0, false);
    }

    final class FakeSession implements RunningSession {
        final int number;
        private final SessionInfo ownInfo = info;
        volatile SessionStatus status;
        volatile WorldSnapshot snapshot;
        volatile Throwable failure;

        FakeSession(int number) {
            this.number = number;
        }

        @Override
        public SessionInfo info() {
            return ownInfo;
        }

        @Override
        public void init(String opMode) {
            calls.add("init " + opMode + " on " + number);
            refuseIfAsked();
        }

        @Override
        public void start() {
            calls.add("start on " + number);
            refuseIfAsked();
        }

        @Override
        public void stop() {
            calls.add("stop on " + number);
        }

        @Override
        public void humanAction(String actionId) {
            calls.add("human " + actionId + " on " + number);
            refuseIfAsked();
        }

        @Override
        public void armMatch(String autoOpMode, String teleOpMode) {
            calls.add("arm '" + autoOpMode + "' '" + teleOpMode + "' on " + number);
            refuseIfAsked();
        }

        @Override
        public void startMatch() {
            calls.add("start match on " + number);
            refuseIfAsked();
        }

        private void refuseIfAsked() {
            if (refusal != null) {
                throw refusal;
            }
        }

        @Override
        public void setGamepad(int gamepad, GamepadState state) {
            calls.add("gamepad " + gamepad + " " + state.leftStickX() + " on " + number);
        }

        @Override
        public void setPaused(boolean paused) {
            calls.add("paused " + paused + " on " + number);
        }

        @Override
        public void setSpeed(double speed) {
            calls.add("speed " + speed + " on " + number);
        }

        @Override
        public SessionStatus status() {
            return status;
        }

        @Override
        public WorldSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public Throwable failure() {
            return failure;
        }

        @Override
        public void close() {
            calls.add("close " + number);
            CountDownLatch hold = holdClose;
            if (hold != null) {
                try {
                    hold.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
