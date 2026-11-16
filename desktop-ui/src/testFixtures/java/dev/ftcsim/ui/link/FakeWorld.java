package dev.ftcsim.ui.link;

import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.contracts.session.Readout;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The made-up simulation behind {@link FakeSimulatorLink}: a mecanum-like chassis driven by gamepad 1 while an
 * OpMode runs, a dozen pieces it can push about, and a turnstile that turns while an OpMode runs. It is a pure
 * function of the steps taken and the gamepad states given, never of the wall clock, and it is only ever touched by
 * one thread. A world with nothing running and nothing moving publishes the same snapshot over and over, as a real
 * one does, which is what lets the viewport rest.
 *
 * <p>A timed match runs in simulated time like the real simulator's: armed from idle (which initialises the first
 * OpMode), started, then autonomous, transition and driver-controlled periods whose ends are detected at the step
 * that reaches them, and ended. The commands that cannot be done return why, in words for a person, and change nothing.
 */
final class FakeWorld {
    static final int STEPS_PER_SECOND = 240;

    private static final double DT = 1.0 / STEPS_PER_SECOND;
    private static final double MAX_SPEED = 1.8;
    private static final double MAX_TURN = 3.0;
    private static final double RESPONSE_SECONDS = 0.12;
    private static final double ROBOT_HALF_LENGTH = 0.215;
    private static final double ROBOT_HALF_WIDTH = 0.20;
    private static final double ROBOT_RADIUS = 0.29;
    private static final double ROBOT_HEIGHT = 0.28;
    private static final double TURNSTILE_RADIANS_PER_SECOND = 0.8;
    private static final double SQUARE_SIDE_SECONDS = 2.1;
    private static final double SQUARE_DRIVE_SECONDS = 1.2;
    private static final double PIECE_DRAG_PER_SECOND = 1.4;
    private static final double RESTITUTION = 0.4;
    private static final double REST_SPEED = 1e-3;
    private static final int MAX_PIECES = 40;
    private static final double WHEEL_RADIUS = 0.048;
    private static final double TICKS_PER_REVOLUTION = 537.7;
    private static final long NANOS = 1_000_000_000L;

    private static final class Piece {
        final int id;
        final int kind;
        double x;
        double y;
        double vx;
        double vy;

        Piece(int id, int kind, double x, double y) {
            this.id = id;
            this.kind = kind;
            this.x = x;
            this.y = y;
        }
    }

    private final double responseGain = 1.0 - Math.exp(-DT / RESPONSE_SECONDS);
    private final List<Piece> pieces = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final List<OpModeDescriptor> opModes = FakeField.opModes();
    private final boolean hasField;
    private final MatchTimes matchTimes;
    private long step;
    private int nextPieceId = FakeField.FIRST_PIECE_ID;
    private double x;
    private double y;
    private double yaw;
    private double forward;
    private double left;
    private double turn;
    private double turnstile;
    private RobotPhase phase = RobotPhase.IDLE;
    private String opMode = "";
    private long runStartStep;
    private String warnings = "";
    private String error = "";
    private GamepadState lastPad = GamepadState.NEUTRAL;
    private MatchState matchState = MatchState.NONE;
    private long phaseStartNanos;
    private String matchAuto = "";
    private String matchTeleOp = "";

    /**
     * @param seed     fixes which of the dozen pieces are green and which are purple; equal seeds give equal worlds
     * @param hasField whether there is a field with pieces and a turnstile, or only the robot on a bare floor
     */
    FakeWorld(double startX, double startY, double startHeading, long seed, boolean hasField) {
        this(startX, startY, startHeading, seed, hasField, MatchTimes.DEFAULT);
    }

    /** @param matchTimes how long the periods of a timed match last, in simulated time */
    FakeWorld(double startX, double startY, double startHeading, long seed, boolean hasField, MatchTimes matchTimes) {
        this.x = startX;
        this.y = startY;
        this.yaw = startHeading;
        this.hasField = hasField;
        this.matchTimes = matchTimes;
        if (hasField) {
            int[] kinds = new int[12];
            for (int i = 0; i < kinds.length; i++) {
                kinds[i] = i % 2 == 0 ? FakeField.KIND_GREEN : FakeField.KIND_PURPLE;
            }
            SplittableRandom random = new SplittableRandom(seed);
            for (int i = kinds.length - 1; i > 0; i--) {
                int swap = random.nextInt(i + 1);
                int kept = kinds[i];
                kinds[i] = kinds[swap];
                kinds[swap] = kept;
            }
            for (int i = 0; i < kinds.length; i++) {
                spawn(kinds[i], -0.9 + 0.3 * (i % 6), 0.3 * (i / 6));
            }
        }
    }

    static long timeOfStep(long step) {
        return step * NANOS / STEPS_PER_SECOND;
    }

    long simTimeNanos() {
        return timeOfStep(step);
    }

    long stepIndex() {
        return step;
    }

    /** Whether nothing is moving and nothing is running that could make anything move. */
    boolean atRest() {
        if (phase == RobotPhase.RUNNING || Math.abs(forward) + Math.abs(left) + Math.abs(turn) > REST_SPEED) {
            return false;
        }
        return pieces.stream().allMatch(piece -> Math.abs(piece.vx) + Math.abs(piece.vy) < REST_SPEED);
    }

    RobotPhase phase() {
        return phase;
    }

    MatchState matchState() {
        return matchState;
    }

    // Driver Station ---------------------------------------------------------------------------------------------

    /** Presses Init with the OpMode selected; returns why it was refused, or null. */
    String init(String name) {
        if (matchActive()) {
            return "Init is not available while a match is armed or under way.";
        }
        if (!declares(name)) {
            return "This program has no OpMode called " + name + ".";
        }
        if (phase != RobotPhase.IDLE) {
            return "An OpMode is already initialised or running; stop it first.";
        }
        initialise(name);
        return null;
    }

    /** Presses Start; returns why it was refused, or null. */
    String start() {
        if (matchActive()) {
            return "Start is not available while a match is armed or under way.";
        }
        if (phase != RobotPhase.INIT) {
            return "Initialise an OpMode before starting it.";
        }
        begin();
        return null;
    }

    /** Presses Stop: ends the OpMode, gives up an armed match and ends one that is under way. */
    void stop() {
        switch (matchState) {
            case ARMED -> matchState = MatchState.NONE;
            case AUTONOMOUS, TRANSITION, TELEOP -> matchState = MatchState.ENDED;
            default -> { }
        }
        phase = RobotPhase.IDLE;
    }

    /**
     * Prepares a timed match from idle: initialises the first OpMode and waits for {@link #startMatch}. Returns why it
     * was refused, or null.
     *
     * @param auto   the autonomous OpMode, or empty for a match without an autonomous period
     * @param teleOp the driver-controlled OpMode
     */
    String armMatch(String auto, String teleOp) {
        if (matchState != MatchState.NONE) {
            return "A match was already armed or played. Reset the session to play another.";
        }
        if (phase != RobotPhase.IDLE) {
            return "A match cannot be armed while an OpMode is initialised or running. Stop it first.";
        }
        if (!auto.isEmpty() && !declares(auto)) {
            return "This program has no OpMode called " + auto + ".";
        }
        if (!declares(teleOp)) {
            return "This program has no OpMode called " + teleOp + ".";
        }
        matchAuto = auto;
        matchTeleOp = teleOp;
        initialise(auto.isEmpty() ? teleOp : auto);
        matchState = MatchState.ARMED;
        return null;
    }

    /** Starts the armed match; returns why it was refused, or null. */
    String startMatch() {
        if (matchState != MatchState.ARMED) {
            return "There is no armed match to start.";
        }
        phaseStartNanos = simTimeNanos();
        matchState = matchAuto.isEmpty() ? MatchState.TELEOP : MatchState.AUTONOMOUS;
        begin();
        return null;
    }

    private boolean declares(String name) {
        return opModes.stream().anyMatch(descriptor -> descriptor.name().equals(name));
    }

    private boolean matchActive() {
        return matchState == MatchState.ARMED || matchState == MatchState.AUTONOMOUS
                || matchState == MatchState.TRANSITION || matchState == MatchState.TELEOP;
    }

    private void initialise(String name) {
        opMode = name;
        phase = RobotPhase.INIT;
        warnings = "";
        error = "";
    }

    private void begin() {
        phase = RobotPhase.RUNNING;
        runStartStep = step;
    }

    /** A person at the field does something; returns why that was refused, or null. */
    String humanAction(String id) {
        if (!hasField) {
            return "A bare floor has no human actions.";
        }
        if (FakeField.ACTION_FEED.equals(id)) {
            if (pieces.size() < MAX_PIECES) {
                spawn(nextPieceId % 2 == 0 ? FakeField.KIND_GREEN : FakeField.KIND_PURPLE, -1.6, -1.6);
                Piece fed = pieces.get(pieces.size() - 1);
                fed.vx = 1.3;
                fed.vy = 0.9;
            }
            return null;
        }
        if (FakeField.ACTION_SCATTER.equals(id)) {
            for (int i = 0; i < pieces.size(); i++) {
                double angle = (i + step) * 2.399963;
                pieces.get(i).vx += 1.5 * Math.cos(angle);
                pieces.get(i).vy += 1.5 * Math.sin(angle);
            }
            return null;
        }
        return "This field has no human action called " + id + ".";
    }

    /** Puts a new piece of the given kind at rest on the floor. */
    void spawn(int kind, double px, double py) {
        pieces.add(new Piece(nextPieceId++, kind, px, py));
    }

    // Time -------------------------------------------------------------------------------------------------------

    /** Advances by one physics step with the given state of gamepad 1. */
    void step(GamepadState pad) {
        lastPad = pad;
        double[] command = command(pad);
        forward += (command[0] - forward) * responseGain;
        left += (command[1] - left) * responseGain;
        turn += (command[2] - turn) * responseGain;
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        x += (forward * cos - left * sin) * DT;
        y += (forward * sin + left * cos) * DT;
        yaw = wrap(yaw + turn * DT);
        keepRobotInside();
        movePieces();
        if (phase == RobotPhase.RUNNING) {
            turnstile = wrap(turnstile + TURNSTILE_RADIANS_PER_SECOND * DT);
        }
        step++;
        scriptedEvents();
        matchEvents();
    }

    private void matchEvents() {
        long now = simTimeNanos();
        switch (matchState) {
            case AUTONOMOUS -> {
                if (now - phaseStartNanos >= matchTimes.autonomousNanos()) {
                    phaseStartNanos += matchTimes.autonomousNanos();
                    initialise(matchTeleOp);
                    matchState = MatchState.TRANSITION;
                }
            }
            case TRANSITION -> {
                if (now - phaseStartNanos >= matchTimes.transitionNanos()) {
                    phaseStartNanos += matchTimes.transitionNanos();
                    begin();
                    matchState = MatchState.TELEOP;
                }
            }
            case TELEOP -> {
                if (now - phaseStartNanos >= matchTimes.teleOpNanos()) {
                    phase = RobotPhase.IDLE;
                    matchState = MatchState.ENDED;
                }
            }
            default -> { }
        }
    }

    /** Velocity the OpMode asks for: forward, left, counter-clockwise turn rate. */
    private double[] command(GamepadState pad) {
        if (phase != RobotPhase.RUNNING) {
            return new double[] {0, 0, 0};
        }
        if (FakeField.SQUARE.equals(opMode)) {
            double seconds = (double) (step - runStartStep) / STEPS_PER_SECOND;
            if (seconds >= 4 * SQUARE_SIDE_SECONDS) {
                return new double[] {0, 0, 0};
            }
            boolean driving = seconds % SQUARE_SIDE_SECONDS < SQUARE_DRIVE_SECONDS;
            return driving ? new double[] {0.8, 0, 0} : new double[] {0, 0, (Math.PI / 2) / (SQUARE_SIDE_SECONDS - SQUARE_DRIVE_SECONDS)};
        }
        return new double[] {-pad.leftStickY() * MAX_SPEED, -pad.leftStickX() * MAX_SPEED, -pad.rightStickX() * MAX_TURN};
    }

    private void scriptedEvents() {
        if (phase != RobotPhase.RUNNING) {
            return;
        }
        double seconds = (double) (step - runStartStep) / STEPS_PER_SECOND;
        if (FakeField.TELEMETRY.equals(opMode)) {
            if (seconds >= 1.0 && warnings.isEmpty()) {
                warnings = "Battery voltage is low (11.2 V)";
            }
            if (seconds >= 2.0) {
                addProblem("hub 173 (Control Hub) motor port 3 was driven with no motor connected");
            }
        } else if (FakeField.CRASH.equals(opMode) && seconds >= 3.0) {
            error = "java.lang.IllegalStateException: no arm was configured\n"
                    + "\tat dev.ftcsim.fake.CrashAfterThreeSeconds.runOpMode(CrashAfterThreeSeconds.java:21)";
            phase = RobotPhase.IDLE;
        }
    }

    private void addProblem(String problem) {
        if (!problems.contains(problem)) {
            problems.add(problem);
        }
    }

    private void keepRobotInside() {
        double limit = FakeField.HALF - ROBOT_RADIUS;
        x = Math.max(-limit, Math.min(limit, x));
        y = Math.max(-limit, Math.min(limit, y));
        double[] pushed = pushOutOfBarrier(x, y, ROBOT_RADIUS);
        x = pushed[0];
        y = pushed[1];
    }

    private static double[] pushOutOfBarrier(double px, double py, double radius) {
        double nearestX = Math.max(FakeField.BARRIER_X - FakeField.BARRIER_HALF_X,
                Math.min(FakeField.BARRIER_X + FakeField.BARRIER_HALF_X, px));
        double nearestY = Math.max(FakeField.BARRIER_Y - FakeField.BARRIER_HALF_Y,
                Math.min(FakeField.BARRIER_Y + FakeField.BARRIER_HALF_Y, py));
        double dx = px - nearestX;
        double dy = py - nearestY;
        double distance = Math.hypot(dx, dy);
        if (distance >= radius) {
            return new double[] {px, py, 0, 0};
        }
        if (distance < 1e-9) {
            // The centre is inside the barrier: leave through the nearest side.
            double toLeft = px - (FakeField.BARRIER_X - FakeField.BARRIER_HALF_X);
            double toRight = FakeField.BARRIER_X + FakeField.BARRIER_HALF_X - px;
            double toBottom = py - (FakeField.BARRIER_Y - FakeField.BARRIER_HALF_Y);
            double toTop = FakeField.BARRIER_Y + FakeField.BARRIER_HALF_Y - py;
            double least = Math.min(Math.min(toLeft, toRight), Math.min(toBottom, toTop));
            if (least == toLeft) {
                return new double[] {FakeField.BARRIER_X - FakeField.BARRIER_HALF_X - radius, py, -1, 0};
            } else if (least == toRight) {
                return new double[] {FakeField.BARRIER_X + FakeField.BARRIER_HALF_X + radius, py, 1, 0};
            } else if (least == toBottom) {
                return new double[] {px, FakeField.BARRIER_Y - FakeField.BARRIER_HALF_Y - radius, 0, -1};
            }
            return new double[] {px, FakeField.BARRIER_Y + FakeField.BARRIER_HALF_Y + radius, 0, 1};
        }
        double nx = dx / distance;
        double ny = dy / distance;
        return new double[] {nearestX + nx * radius, nearestY + ny * radius, nx, ny};
    }

    private void movePieces() {
        double drag = Math.exp(-PIECE_DRAG_PER_SECOND * DT);
        double limit = FakeField.HALF - FakeField.PIECE_RADIUS;
        for (Piece piece : pieces) {
            piece.x += piece.vx * DT;
            piece.y += piece.vy * DT;
            piece.vx *= drag;
            piece.vy *= drag;
            if (Math.abs(piece.x) > limit) {
                piece.x = Math.signum(piece.x) * limit;
                piece.vx = -piece.vx * RESTITUTION;
            }
            if (Math.abs(piece.y) > limit) {
                piece.y = Math.signum(piece.y) * limit;
                piece.vy = -piece.vy * RESTITUTION;
            }
            double[] pushed = pushOutOfBarrier(piece.x, piece.y, FakeField.PIECE_RADIUS);
            if (pushed[2] != 0 || pushed[3] != 0) {
                piece.x = pushed[0];
                piece.y = pushed[1];
                double along = piece.vx * pushed[2] + piece.vy * pushed[3];
                if (along < 0) {
                    piece.vx -= (1 + RESTITUTION) * along * pushed[2];
                    piece.vy -= (1 + RESTITUTION) * along * pushed[3];
                }
            }
            pushFromRobot(piece);
        }
        for (int i = 0; i < pieces.size(); i++) {
            for (int j = i + 1; j < pieces.size(); j++) {
                collide(pieces.get(i), pieces.get(j));
            }
        }
    }

    private void pushFromRobot(Piece piece) {
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        double dx = piece.x - x;
        double dy = piece.y - y;
        double localX = cos * dx + sin * dy;
        double localY = -sin * dx + cos * dy;
        double nearestX = Math.max(-ROBOT_HALF_LENGTH, Math.min(ROBOT_HALF_LENGTH, localX));
        double nearestY = Math.max(-ROBOT_HALF_WIDTH, Math.min(ROBOT_HALF_WIDTH, localY));
        double offsetX = localX - nearestX;
        double offsetY = localY - nearestY;
        double distance = Math.hypot(offsetX, offsetY);
        if (distance >= FakeField.PIECE_RADIUS) {
            return;
        }
        double normalLocalX = distance < 1e-9 ? Math.signum(localX == 0 ? 1 : localX) : offsetX / distance;
        double normalLocalY = distance < 1e-9 ? 0 : offsetY / distance;
        double nx = cos * normalLocalX - sin * normalLocalY;
        double ny = sin * normalLocalX + cos * normalLocalY;
        double push = FakeField.PIECE_RADIUS - distance;
        piece.x += nx * push;
        piece.y += ny * push;
        double robotVx = forward * cos - left * sin - turn * (piece.y - y);
        double robotVy = forward * sin + left * cos + turn * (piece.x - x);
        double approach = (piece.vx - robotVx) * nx + (piece.vy - robotVy) * ny;
        if (approach < 0) {
            piece.vx -= (1 + RESTITUTION) * approach * nx;
            piece.vy -= (1 + RESTITUTION) * approach * ny;
        }
    }

    private static void collide(Piece a, Piece b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double distance = Math.hypot(dx, dy);
        double touching = 2 * FakeField.PIECE_RADIUS;
        if (distance >= touching || distance < 1e-9) {
            return;
        }
        double nx = dx / distance;
        double ny = dy / distance;
        double half = (touching - distance) / 2;
        a.x -= nx * half;
        a.y -= ny * half;
        b.x += nx * half;
        b.y += ny * half;
        double closing = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
        if (closing < 0) {
            double impulse = -(1 + RESTITUTION) * closing / 2;
            a.vx -= impulse * nx;
            a.vy -= impulse * ny;
            b.vx += impulse * nx;
            b.vy += impulse * ny;
        }
    }

    private static double wrap(double radians) {
        double wrapped = radians % (2 * Math.PI);
        if (wrapped > Math.PI) {
            return wrapped - 2 * Math.PI;
        }
        return wrapped <= -Math.PI ? wrapped + 2 * Math.PI : wrapped;
    }

    // What a viewer sees -----------------------------------------------------------------------------------------

    /** The bodies ordered by entity id: the chassis, the turnstile, then the pieces in the order they appeared. */
    WorldSnapshot snapshot() {
        List<BodyState> bodies = new ArrayList<>(pieces.size() + 2);
        bodies.add(body(FakeField.CHASSIS_ID, BodyKinds.CHASSIS, x, y, ROBOT_HEIGHT / 2, yaw));
        if (hasField) {
            bodies.add(body(FakeField.TURNSTILE_ID, FakeField.KIND_TURNSTILE, FakeField.TURNSTILE_X, FakeField.TURNSTILE_Y, 0, turnstile));
        }
        for (Piece piece : pieces) {
            bodies.add(body(piece.id, piece.kind, piece.x, piece.y, FakeField.PIECE_RADIUS, 0));
        }
        return new WorldSnapshot(step, simTimeNanos(), bodies);
    }

    private static BodyState body(int id, int kind, double px, double py, double pz, double heading) {
        return new BodyState(
                id, kind, (float) px, (float) py, (float) pz,
                0f, 0f, (float) Math.sin(heading / 2), (float) Math.cos(heading / 2));
    }

    Score score() {
        int green = 0;
        int purple = 0;
        for (Piece piece : pieces) {
            if (inGoal(piece, FakeField.GOAL_X) || inGoal(piece, -FakeField.GOAL_X)) {
                if (piece.kind == FakeField.KIND_GREEN) {
                    green++;
                } else {
                    purple++;
                }
            }
        }
        return new Score(List.of(
                new Score.Line("Green in a goal", green, green * 3),
                new Score.Line("Purple in a goal", purple, purple * 5)));
    }

    private static boolean inGoal(Piece piece, double goalX) {
        return Math.hypot(piece.x - goalX, piece.y - FakeField.GOAL_Y) < FakeField.GOAL_RADIUS;
    }

    SessionStatus status(double speed, double achievedSpeed, boolean paused) {
        return new SessionStatus(
                simTimeNanos(), phase, new TelemetrySnapshot(telemetryLines(), warnings, error), score(),
                List.copyOf(problems), hardware(), speed, achievedSpeed, paused, matchStatus());
    }

    private MatchStatus matchStatus() {
        long duration = switch (matchState) {
            case AUTONOMOUS -> matchTimes.autonomousNanos();
            case TRANSITION -> matchTimes.transitionNanos();
            case TELEOP -> matchTimes.teleOpNanos();
            default -> 0L;
        };
        return duration == 0L
                ? new MatchStatus(matchState, 0L, 0L)
                : new MatchStatus(matchState, Math.min(duration, simTimeNanos() - phaseStartNanos), duration);
    }

    private List<String> telemetryLines() {
        List<String> lines = new ArrayList<>();
        lines.add(phase == RobotPhase.IDLE ? "Status: idle" : "Status: " + phase + " (" + opMode + ")");
        lines.add(String.format(Locale.ROOT, "Pose: x=%.2f m  y=%.2f m  heading=%.0f deg", x, y, Math.toDegrees(yaw)));
        lines.add(String.format(Locale.ROOT, "Speed: %.2f m/s", Math.hypot(forward, left)));
        lines.add(String.format(
                Locale.ROOT, "Gamepad 1: LX=%.2f LY=%.2f RX=%.2f", lastPad.leftStickX(), lastPad.leftStickY(), lastPad.rightStickX()));
        if (FakeField.TELEMETRY.equals(opMode) && phase != RobotPhase.IDLE) {
            for (int i = 1; i <= 40; i++) {
                lines.add("Line " + i + ": " + (i % 7 == 0 ? "a long telemetry line that is wider than the panel and has to wrap or be cut" : "value " + i * 37 % 101));
            }
        }
        return lines;
    }

    private List<Readout> hardware() {
        double reach = (ROBOT_HALF_LENGTH + ROBOT_HALF_WIDTH);
        double[] wheels = {
            forward - left - reach * turn, forward - left + reach * turn,
            forward + left + reach * turn, forward + left - reach * turn
        };
        String[] names = {"frontLeft", "backLeft", "frontRight", "backRight"};
        List<Readout> readouts = new ArrayList<>();
        for (int i = 0; i < wheels.length; i++) {
            double radiansPerSecond = wheels[i] / WHEEL_RADIUS;
            double ticks = radiansPerSecond / (2 * Math.PI) * TICKS_PER_REVOLUTION;
            readouts.add(new Readout(
                    names[i], String.format(Locale.ROOT, "%.0f ticks/s  %.1f A", ticks, 0.3 + Math.abs(radiansPerSecond) * 0.02)));
        }
        readouts.add(new Readout("claw", phase == RobotPhase.IDLE ? "off" : (lastPad.a() ? "2000 us" : "1500 us")));
        readouts.add(new Readout("imu", String.format(Locale.ROOT, "%.1f deg", Math.toDegrees(yaw))));
        return readouts;
    }
}
