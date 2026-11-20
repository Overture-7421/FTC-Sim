package dev.ftcsim.scenarios.opmodes;

import java.util.Locale;

/**
 * The BIOBUZZ robot's shooter, for a driver who holds a button to shoot. The turret, the hood and the flywheels follow the alliance's
 * HIVE all the time, button or no button: the turret and the hood point at the CELL, and the flywheels stay at the speed that reaches
 * it, so that a shot is ready the moment it is asked for, with no spin-up. While the button is held (and only then) the stopper opens
 * and, once the turret, the flywheels and the hood are where they should be and the robot is still, the indexer feeds the balls; it
 * stops feeding while any of them is not where it should be and resumes when it is again. Letting go shuts the stopper and stops the
 * indexer; the flywheels stay at their speed.
 *
 * <p>It refuses to feed, and says why, while the pose is only the approximate start the program was given (the camera has not seen
 * the HIVE yet: the turret then sweeps about where the HIVE should be until it does), and while no throw from where the robot
 * stands gets into the CELL that faces up.
 *
 * <p>The class holds the decisions and no hardware: each loop it is told what the robot measures and answers what every actuator
 * should do, so it is tested without a robot. Written for Java 8, like the other files a robot project takes.
 */
public final class BiobuzzHoldShooter {
    /** Where the shooter is in its sequence. */
    public enum State {
        IDLE, REFUSED, SPINNING_UP, FEEDING
    }

    /** What every actuator should do this loop. */
    public static final class Command {
        /** Turret servo power, positive to the left. */
        public double turretPower;
        /** Power of both flywheel motors. */
        public double flywheelPower;
        /** Hood servo position. */
        public double hoodPosition;
        public boolean stopperOpen;
        /** The indexer's power to feed the shooter, 0 when it is not feeding. */
        public double indexerPower;
    }

    /** Flywheel control: feed-forward from the target speed and a correction from the error, in power per tick per second. */
    static final double FLYWHEEL_KF = 1.0 / BiobuzzShotSolver.MOTOR_MAX_TICKS_PER_SECOND;
    static final double FLYWHEEL_KP = 0.01;
    /** Where the flywheels idle when there is nothing at all to work a speed out from (an estimate that cannot reach the CELL). */
    static final double IDLE_TICKS_PER_SECOND = 1500.0;
    static final double AIM_GAIN = 0.015;
    /** The turret's servo (an Axon Mini+ behind 1.375:1) turns about 370 degrees a second at full power: nothing here holds it back. */
    static final double AIM_LIMIT = 1.0;
    /**
     * While the pose is not confirmed the turret sweeps this far either side of where the HIVE should be, once in this many seconds:
     * the robot may be anywhere along its wall, so the HIVE may be far from where the start says. The fastest the turret turns in the
     * sweep is under the 40 degrees a second above which camera frames are left out of the pose.
     */
    static final double SEARCH_AMPLITUDE_DEGREES = 100.0;
    static final double SEARCH_PERIOD_SECONDS = 20.0;
    /**
     * The turret's working range in degrees left of the robot's front: the span its two absolute encoders (5.5 and 44/7 turns to its
     * one) tell apart without doubt, which the stub's test checks from -165 to +250. A real turret has cables that keep it in range too.
     */
    public static final double TURRET_MIN_DEGREES = -170.0;
    public static final double TURRET_MAX_DEGREES = 250.0;

    /** What "everything is where it should be" means. */
    static final double TURRET_TOLERANCE_DEGREES = 2.0;
    static final double FLYWHEEL_TOLERANCE = 0.03;
    static final double STILL_SPEED_MPS = 0.1;
    static final double STILL_TURN_DEGREES_PER_SECOND = 15.0;
    /** The hood's servo (five turns, about 250 degrees a second) takes this long for a whole range, and a little more to settle. */
    static final double HOOD_SECONDS_PER_POSITION = 1800.0 / 250.0;
    static final double HOOD_SETTLE_MARGIN_SECONDS = 0.15;
    /** A hood that is only a little off stays where it is: the estimate wanders a little, and every move costs the servo its time. */
    static final double HOOD_DEADBAND = 0.02;
    /** The stopper's servo takes this long to open. */
    public static final double STOPPER_OPEN_SECONDS = 0.15;
    /** Ready has to hold this long before the stopper opens. */
    static final double READY_HOLD_SECONDS = 0.05;
    /**
     * The indexer's power to feed a shot. It starts at {@link #FEED_POWER} for {@link #FEED_KICK_SECONDS}, which takes the first ball up
     * to the flywheels at once, and then goes on at {@link #FEED_SUSTAINED_POWER}: at full power the four balls leave 20 ms apart, so close
     * that they fly in a line, touch in the air and in the CELL, and the last falls short, and the flywheels have no time to recover
     * between them; slowly, they leave about a tenth of a second apart.
     */
    public static final double FEED_POWER = 1.0;
    static final double FEED_KICK_SECONDS = 0.06;
    public static final double FEED_SUSTAINED_POWER = 0.1;
    /** The shot is worked out again when the robot has moved this far, or this often. */
    static final double RESOLVE_DISTANCE_M = 0.01;
    static final double RESOLVE_SECONDS = 0.2;

    private final BiobuzzHive.Alliance alliance;
    private State state = State.IDLE;
    private String status = "idle: hold the shoot button";
    private BiobuzzShotSolver.Solution solution;
    private BiobuzzHive.Tilt solvedTilt;
    private double solvedX = Double.NaN;
    private double solvedY = Double.NaN;
    private double solvedAt = -1e9;
    private double hood;
    private boolean hoodKnown;
    private double hoodSettledAt = -1e9;
    private double readySince = Double.NaN;
    private double feedingStartedAt;
    private double stopperOpenedAt;
    private double fedSeconds;
    private double lastUpdate = Double.NaN;
    private double aimTarget = Double.NaN;
    private boolean confirmed;
    private double flywheelTarget;

    public BiobuzzHoldShooter(BiobuzzHive.Alliance alliance) {
        this.alliance = alliance;
    }

    public State state() {
        return state;
    }

    public String status() {
        return status;
    }

    /** The shot as last worked out, or null before the first pose. */
    public BiobuzzShotSolver.Solution solution() {
        return solution;
    }

    /** Seconds of feeding since the button went down. */
    public double fedSeconds() {
        return fedSeconds;
    }

    /**
     * One loop.
     *
     * @param now            seconds on any clock that only goes forward
     * @param held           whether the shoot button is down
     * @param pose           the estimated pose
     * @param confirmed      whether the camera has placed the robot, or the pose is only the start it was given
     * @param tilt           which CELL of the HIVE faces up now
     * @param turretDegrees  the turret's angle, degrees left of the robot's front
     * @param flywheelTicks  the flywheels' speed, ticks a second, as the motors report it
     * @param speed          how fast the robot is driving, metres a second
     * @param turnRate       how fast the robot is turning, degrees a second
     */
    public Command update(double now, boolean held, BiobuzzPoseEstimator.Pose pose, boolean confirmed, BiobuzzHive.Tilt tilt,
            double turretDegrees, double flywheelTicks, double speed, double turnRate) {
        Command command = new Command();
        double dt = Double.isNaN(lastUpdate) ? 0.0 : now - lastUpdate;
        lastUpdate = now;
        this.confirmed = confirmed;
        command.hoodPosition = hood;
        double bearing = BiobuzzShotSolver.bearingDegrees(pose, alliance, tilt);
        resolve(now, pose, confirmed, tilt);

        double target = bearing + (confirmed ? 0.0 : SEARCH_AMPLITUDE_DEGREES * Math.sin(2.0 * Math.PI * now / SEARCH_PERIOD_SECONDS));
        // The turret has a working range, which is what its two absolute encoders can tell apart (and where its cables allow it to go): the
        // angle it is sent to is the one in that range that is the same direction, never the shorter way round past the end of it.
        aimTarget = inRange(target, Double.isNaN(aimTarget) ? turretDegrees : aimTarget);
        double error = aimTarget - turretDegrees;
        command.turretPower = Math.max(-AIM_LIMIT, Math.min(AIM_LIMIT, AIM_GAIN * error));
        double aimError = inRange(bearing, aimTarget) - turretDegrees;

        if (confirmed && solution.feasible) {
            if (!hoodKnown || Math.abs(solution.hoodPosition - hood) > HOOD_DEADBAND) {
                double from = hoodKnown ? hood : 0.0;
                hoodSettledAt = Math.max(now, hoodSettledAt) + Math.abs(solution.hoodPosition - from) * HOOD_SECONDS_PER_POSITION
                        + HOOD_SETTLE_MARGIN_SECONDS;
                hood = solution.hoodPosition;
                hoodKnown = true;
            }
            command.hoodPosition = hood;
            flywheelTarget = solution.flywheelTicksPerSecond;
        } else {
            // No shot from here (or no pose to trust yet): idle at about the speed of one from near here, never at a stop.
            double nominal = BiobuzzShotSolver.nominalTicks(pose, alliance, tilt);
            if (!Double.isNaN(nominal)) {
                flywheelTarget = nominal;
            } else if (flywheelTarget <= 0.0) {
                flywheelTarget = IDLE_TICKS_PER_SECOND;
            }
        }
        // The flywheels stay at the speed of the shot, button or no button, so that there is no spin-up when it is asked for.
        command.flywheelPower = Math.max(0.0, Math.min(1.0,
                FLYWHEEL_KF * flywheelTarget + FLYWHEEL_KP * (flywheelTarget - flywheelTicks)));

        if (!held) {
            state = State.IDLE;
            status = "idle: hold the shoot button";
            readySince = Double.NaN;
            fedSeconds = 0.0;
            return command;
        }
        if (!confirmed) {
            return refuse(command, "the camera has not seen the HIVE's tags yet, so the pose is only the start: drive towards the HIVE");
        }
        if (!solution.feasible) {
            return refuse(command, solution.reason.text());
        }

        double ticks = solution.flywheelTicksPerSecond;
        double speedError = Math.abs(flywheelTicks - ticks) / ticks;
        boolean aimed = Math.abs(aimError) < TURRET_TOLERANCE_DEGREES;
        boolean still = speed < STILL_SPEED_MPS && turnRate < STILL_TURN_DEGREES_PER_SECOND;
        boolean hoodSet = now >= hoodSettledAt;
        boolean ready = aimed && speedError < FLYWHEEL_TOLERANCE && hoodSet && still;
        String detail = String.format(Locale.ROOT, "turret %.1f deg off, flywheels %.0f of %.0f, hood %s%s", aimError, flywheelTicks, ticks,
                hoodSet ? "set" : "moving", still ? "" : ", robot moving");

        if (state != State.SPINNING_UP && state != State.FEEDING) {
            state = State.SPINNING_UP;
            readySince = Double.NaN;
            fedSeconds = 0.0;
            stopperOpenedAt = now;
        }
        // The stopper (a servo that takes a little over half a second) opens as soon as the button goes down, while the flywheels spin
        // up, so that it is open by the time they are ready; the balls only move when the indexer runs.
        command.stopperOpen = true;
        if (state == State.SPINNING_UP) {
            readySince = ready ? (Double.isNaN(readySince) ? now : readySince) : Double.NaN;
            status = "spinning up: " + detail;
            if (!Double.isNaN(readySince) && now - readySince >= READY_HOLD_SECONDS) {
                state = State.FEEDING;
                feedingStartedAt = now;
            }
            return command;
        }
        boolean open = now - stopperOpenedAt >= STOPPER_OPEN_SECONDS;
        if (open && ready) {
            command.indexerPower = fedSeconds < FEED_KICK_SECONDS ? FEED_POWER : FEED_SUSTAINED_POWER;
            fedSeconds += dt;
        }
        status = String.format(Locale.ROOT, "feeding %.1f s%s: %s", fedSeconds, ready ? "" : " (paused until ready)", detail);
        return command;
    }

    /**
     * The angle in the turret's working range ({@link #TURRET_MIN_DEGREES} to {@link #TURRET_MAX_DEGREES}) that points the way
     * {@code wanted} says, and is nearest to {@code reference}: the turret does not take the shorter way round when that is past the
     * end of the range, where its encoders can no longer tell where it is.
     */
    static double inRange(double wanted, double reference) {
        double base = Math.IEEEremainder(wanted, 360.0);
        double best = Double.NaN;
        for (int turns = -1; turns <= 1; turns++) {
            double candidate = base + 360.0 * turns;
            if (candidate >= TURRET_MIN_DEGREES && candidate <= TURRET_MAX_DEGREES
                    && (Double.isNaN(best) || Math.abs(candidate - reference) < Math.abs(best - reference))) {
                best = candidate;
            }
        }
        return best;
    }

    private Command refuse(Command command, String reason) {
        state = State.REFUSED;
        status = "not shooting: " + reason;
        readySince = Double.NaN;
        fedSeconds = 0.0;
        return command;
    }

    private void resolve(double now, BiobuzzPoseEstimator.Pose pose, boolean confirmed, BiobuzzHive.Tilt tilt) {
        boolean moved = Double.isNaN(solvedX) || Math.hypot(pose.x() - solvedX, pose.y() - solvedY) > RESOLVE_DISTANCE_M;
        if (solution != null && !moved && tilt == solvedTilt && now - solvedAt < RESOLVE_SECONDS) {
            return;
        }
        solution = BiobuzzShotSolver.solve(pose, alliance, tilt, confirmed && hoodKnown ? hood : Double.NaN);
        solvedTilt = tilt;
        solvedX = pose.x();
        solvedY = pose.y();
        solvedAt = now;
    }

    /** What the aim is now, for telemetry. */
    public String aim() {
        if (solution == null) {
            return "no pose yet";
        }
        if (!confirmed) {
            return String.format(Locale.ROOT, "the pose is only the start: looking for the HIVE's tags (bearing %.1f)", solution.bearingDegrees);
        }
        return solution.describe();
    }
}
