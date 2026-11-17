package dev.ftcsim.scenarios.opmodes;

import java.util.Locale;

/**
 * The BIOBUZZ robot's automatic shot: one press of the button turns the turret to the blue HIVE's nearer CELL, spins the
 * flywheels to the speed that reaches it, sets the hood, waits until every one of them is where it should be, and only then
 * opens the stopper and runs the indexer. The balls wait against the shut stopper until that moment, which is how they are
 * preloaded: the intake (by hand) brings them up to it. The class holds the decisions and no hardware: each loop it is told what the
 * robot measures and answers what every actuator should do, so it is tested without a robot.
 *
 * <p>The pose is {@link BiobuzzPoseEstimator}'s and the target {@link BiobuzzHive#nearestBlueCell}. The launch numbers
 * below are tuning for the robot's shooter (the efficiency of the wheels, the hood's angles), measured once; a team replaces
 * them and the formula with a table of its own.
 *
 * <p>Written for Java 8, like the other files a robot project takes.
 */
public final class BiobuzzAutoShoot {
    /** Where the automatic shot is in its sequence. */
    public enum State {
        IDLE, AIMING, FEEDING, DONE, FAILED
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
        public double indexerPower;
    }

    /** Where to aim and how hard to shoot. */
    public static final class Solution {
        public final double bearingDegrees;
        public final double distanceMeters;
        public final double hoodPosition;
        public final double flywheelTicksPerSecond;
        public final boolean reachable;
        public final double targetX;
        public final double targetY;

        Solution(double bearing, double distance, double hood, double ticks, boolean reachable, double targetX, double targetY) {
            this.bearingDegrees = bearing;
            this.distanceMeters = distance;
            this.hoodPosition = hood;
            this.flywheelTicksPerSecond = ticks;
            this.reachable = reachable;
            this.targetX = targetX;
            this.targetY = targetY;
        }
    }

    /** The turret's axis ahead of the robot's centre, the opening ahead of the axis and how high it is above the floor, metres. */
    static final double TURRET_AXIS_M = BiobuzzPoseEstimator.TURRET_AXIS_X;
    static final double MUZZLE_AHEAD_M = 0.111;
    static final double MUZZLE_HEIGHT_M = 0.4074;
    /** The shooter: the share of the wheels' rim speed a ball leaves with, a 3 in wheel behind a 43:30 belt, a 28 count encoder. */
    static final double EFFICIENCY = 0.49;
    static final double WHEEL_RADIUS_M = 0.0381;
    static final double BELT_RATIO = 43.0 / 30.0;
    static final double TICKS_PER_TURN = 28.0;
    /** The flywheels' speed at full power, measured on the robot (the viscous load of the wheels keeps it well under the motor's 2800). */
    static final double MOTOR_MAX_TICKS_PER_SECOND = 2130.0;
    /**
     * The launch angle is {@code BASE} degrees with the hood servo at 0 (the hood at its 1 degree) and rises with the hood: the
     * servo has five turns (1800 degrees) behind 730:24 gears, so a position of 1 would be 59 degrees of hood, and the hood's stop
     * holds it at 24 degrees, which is position 0.41. The code keeps its commands under that.
     */
    static final double ELEVATION_BASE_DEGREES = 45.0;
    static final double HOOD_DEGREES_PER_POSITION = 1800.0 / (730.0 / 24.0);
    public static final double HOOD_MAX_POSITION = 24.0 / HOOD_DEGREES_PER_POSITION;
    /** The hood's servo turns at about 250 degrees a second, so a whole range of 1800 degrees takes this long. */
    static final double HOOD_SECONDS_PER_POSITION = 1800.0 / 250.0;
    static final double HOOD_SETTLE_MARGIN_SECONDS = 0.3;
    static final double HOOD_DEADBAND = 0.02;
    private static final double GRAVITY = 9.81;

    /** Flywheel control: feed-forward from the target speed and a correction from the error, in power per tick per second. */
    static final double FLYWHEEL_KF = 1.0 / MOTOR_MAX_TICKS_PER_SECOND;
    static final double FLYWHEEL_KP = 0.0006;
    static final double AIM_GAIN = 0.02;
    static final double AIM_LIMIT = 0.4;

    /** What "everything is where it should be" means. */
    static final double TURRET_TOLERANCE_DEGREES = 2.0;
    static final double FLYWHEEL_TOLERANCE = 0.03;
    static final double TURN_RATE_LIMIT_DEGREES_PER_SECOND = 15.0;
    /** The stopper's servo takes this long to open. */
    static final double STOPPER_OPEN_SECONDS = 0.6;
    /** Ready has to hold this long before the stopper opens. */
    static final double READY_HOLD_SECONDS = 0.3;
    static final double FEED_POWER = 0.7;
    /** Of feeding, in seconds, which sends the three balls. The robot has no sensor to say when they are gone. */
    public static final double FEED_SECONDS = 2.0;
    static final double AIM_TIMEOUT_SECONDS = 8.0;

    private State state = State.IDLE;
    private String status = "idle: press A";
    private double startedAt;
    private double readySince = Double.NaN;
    private double feedingStartedAt;
    private double fedSeconds;
    private double lastUpdate = Double.NaN;
    /** Where the hood is told to be, and when its servo gets there. */
    private double hood;
    private double hoodSettledAt = -1e9;

    public State state() {
        return state;
    }

    /** Whether the automatic shot is running, so that it, not the driver, has the actuators it uses. */
    public boolean active() {
        return state == State.AIMING || state == State.FEEDING;
    }

    public String status() {
        return status;
    }

    /**
     * The button went down: start a shot, or cancel the one that is running.
     *
     * @param hoodPosition where the hood servo is now, so that the time it needs to move can be told
     */
    public void press(double now, double hoodPosition) {
        if (active()) {
            state = State.IDLE;
            status = "cancelled";
            return;
        }
        state = State.AIMING;
        status = "aiming";
        startedAt = now;
        hood = hoodPosition;
        hoodSettledAt = now;
        readySince = Double.NaN;
        fedSeconds = 0.0;
        lastUpdate = Double.NaN;
    }

    /**
     * Where to aim and how hard to shoot from {@code pose}: the bearing is degrees left of the robot's front. A shot that the
     * flywheels cannot reach is flagged.
     */
    public static Solution solve(BiobuzzPoseEstimator.Pose pose) {
        BiobuzzHive.Tag cell = BiobuzzHive.nearestBlueCell(pose.x());
        double axisX = pose.x() + Math.cos(pose.heading()) * TURRET_AXIS_M;
        double axisY = pose.y() + Math.sin(pose.heading()) * TURRET_AXIS_M;
        double dx = cell.x() - axisX;
        double dy = cell.y() - axisY;
        double bearing = Math.toDegrees(normalize(Math.atan2(dy, dx) - pose.heading()));
        double distance = Math.hypot(dx, dy);
        double d = distance - MUZZLE_AHEAD_M;
        double rise = cell.z() - MUZZLE_HEIGHT_M;
        if (d > 0.0) {
            // The launch angle that needs the least speed to reach the CELL is a half of the way from 45 degrees to the line to it;
            // the hood goes as near to it as its stop allows.
            double best = Math.PI / 4.0 + Math.atan2(rise, d) / 2.0;
            double position = Math.max(0.0, Math.min(HOOD_MAX_POSITION,
                    (Math.toDegrees(best) - ELEVATION_BASE_DEGREES) / HOOD_DEGREES_PER_POSITION));
            double theta = Math.toRadians(ELEVATION_BASE_DEGREES + HOOD_DEGREES_PER_POSITION * position);
            double room = d * Math.tan(theta) - rise;
            if (room > 0.05) {
                double speed = Math.sqrt(GRAVITY * d * d / (2.0 * Math.cos(theta) * Math.cos(theta) * room));
                double rim = speed / EFFICIENCY;
                double motorRadPerSecond = rim * BELT_RATIO / WHEEL_RADIUS_M;
                double ticks = motorRadPerSecond * TICKS_PER_TURN / (2.0 * Math.PI);
                if (ticks <= 0.95 * MOTOR_MAX_TICKS_PER_SECOND) {
                    return new Solution(bearing, distance, position, ticks, true, cell.x(), cell.y());
                }
            }
        }
        return new Solution(bearing, distance, 0.0, 0.0, false, cell.x(), cell.y());
    }

    /**
     * One loop of the sequence.
     *
     * @param now            seconds on any clock that only goes forward
     * @param pose           the estimated pose, or null before there is one
     * @param turretDegrees  the turret's angle, degrees left of the robot's front
     * @param flywheelTicks  the flywheels' speed, ticks a second, as the motors report it
     * @param turnRate       how fast the robot is turning, degrees a second
     */
    public Command update(double now, BiobuzzPoseEstimator.Pose pose, double turretDegrees, double flywheelTicks, double turnRate) {
        Command command = new Command();
        command.hoodPosition = hood;
        if (!active()) {
            return command;
        }
        if (state == State.AIMING && now - startedAt > AIM_TIMEOUT_SECONDS) {
            return finish(State.FAILED, "gave up: it did not get ready in time", command);
        }
        if (state == State.FEEDING && now - feedingStartedAt > FEED_SECONDS * 4.0) {
            return finish(State.FAILED, "gave up: the feed kept pausing", command);
        }
        if (pose == null) {
            status = "waiting for a pose: the camera has not seen two tags together";
            return command;
        }
        Solution solution = solve(pose);
        if (!solution.reachable) {
            return finish(State.FAILED, String.format(Locale.ROOT, "out of range: %.2f m from the CELL", solution.distanceMeters), command);
        }
        // A hood that is only a little off stays where it is: the estimate wanders a little, and every move costs the servo its time.
        if (Math.abs(solution.hoodPosition - hood) > HOOD_DEADBAND) {
            // The servo turns at a set speed from where it is to where it is sent: it is there after this long.
            hoodSettledAt = Math.max(now, hoodSettledAt)
                    + Math.abs(solution.hoodPosition - hood) * HOOD_SECONDS_PER_POSITION + HOOD_SETTLE_MARGIN_SECONDS;
            hood = solution.hoodPosition;
        }
        command.hoodPosition = hood;

        double error = Math.IEEEremainder(solution.bearingDegrees - turretDegrees, 360.0);
        command.turretPower = Math.max(-AIM_LIMIT, Math.min(AIM_LIMIT, AIM_GAIN * error));
        double target = solution.flywheelTicksPerSecond;
        command.flywheelPower = Math.max(0.0, Math.min(1.0, FLYWHEEL_KF * target + FLYWHEEL_KP * (target - flywheelTicks)));
        double speedError = Math.abs(flywheelTicks - target) / target;
        boolean aimed = Math.abs(error) < TURRET_TOLERANCE_DEGREES && turnRate < TURN_RATE_LIMIT_DEGREES_PER_SECOND;
        boolean hoodSet = now >= hoodSettledAt;
        boolean ready = aimed && speedError < FLYWHEEL_TOLERANCE && hoodSet;
        double dt = Double.isNaN(lastUpdate) ? 0.0 : now - lastUpdate;
        lastUpdate = now;

        if (state == State.AIMING) {
            readySince = ready ? (Double.isNaN(readySince) ? now : readySince) : Double.NaN;
            status = String.format(Locale.ROOT, "aiming: turret %.1f deg off, flywheels %.0f of %.0f, hood %s", error, flywheelTicks, target,
                    hoodSet ? "set" : "moving");
            if (!Double.isNaN(readySince) && now - readySince >= READY_HOLD_SECONDS) {
                state = State.FEEDING;
                feedingStartedAt = now;
            }
            return command;
        }
        command.stopperOpen = true;
        boolean open = now - feedingStartedAt >= STOPPER_OPEN_SECONDS;
        if (open && ready) {
            command.indexerPower = FEED_POWER;
            fedSeconds += dt;
        }
        status = String.format(Locale.ROOT, "feeding: %.1f of %.1f s%s", fedSeconds, FEED_SECONDS, ready ? "" : " (paused until ready)");
        if (fedSeconds >= FEED_SECONDS) {
            return finish(State.DONE, "done: press A to shoot again", command);
        }
        return command;
    }

    private Command finish(State end, String message, Command command) {
        state = end;
        status = message;
        command.turretPower = 0.0;
        command.flywheelPower = 0.0;
        command.stopperOpen = false;
        command.indexerPower = 0.0;
        return command;
    }

    /** What the aim is now, for telemetry. */
    public String aim(BiobuzzPoseEstimator.Pose pose, double turretDegrees) {
        if (pose == null) {
            return "no pose yet";
        }
        Solution solution = solve(pose);
        return String.format(Locale.ROOT, "blue CELL x=%.2f y=%.2f bearing=%.1f error=%.1f distance=%.2f",
                solution.targetX, solution.targetY, solution.bearingDegrees,
                Math.IEEEremainder(solution.bearingDegrees - turretDegrees, 360.0), solution.distanceMeters);
    }

    private static double normalize(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }
}
