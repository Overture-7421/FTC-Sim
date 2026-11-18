package dev.ftcsim.mechanisms;

import dev.ftcsim.contracts.world.Vec3;
import java.util.List;

/**
 * A launcher at the end of a storage's path: while every condition holds, the piece waiting at the end leaves
 * the robot with a speed that follows the surface speed of its wheels.
 *
 * @param storage       the storage whose front piece is launched
 * @param active        what the motors must be doing, typically the feed pushing forward
 * @param wheels        the flywheels the piece passes between; they give the piece its speed and lose the energy
 * @param efficiency    the share of the wheels' surface speed the piece leaves with, between 0 and 1
 * @param direction     the way the piece leaves when the turret, if there is one, faces forward, in the robot frame
 * @param dribbleSpeed  the least speed a released piece has, for a launcher whose wheels are stopped
 * @param turret        what turns the launcher about the robot's vertical axis, or null for a launcher that is fixed
 * @param hood          what sets how high a piece leaves, or null for a launcher whose {@code direction} holds
 * @param muzzle        where a piece leaves the robot, in the robot frame with the turret facing forward (it turns with the
 *                      turret, about the axis of the storage's turned stretch), or null to leave from the end of the path
 */
public record LaunchSpec(
        String name,
        String storage,
        List<Condition> active,
        List<Wheel> wheels,
        double efficiency,
        Vec3 direction,
        double dribbleSpeed,
        Turret turret,
        Hood hood,
        Vec3 muzzle) {

    /**
     * A servo that tilts the launch. The piece leaves {@code baseElevationRadians} (with the servo's shaft at 0) plus the hood's
     * angle, {@code servoAngle / ratio} up to {@code maxHoodRadians}, above the horizontal, along the direction's heading.
     *
     * @param ratio turns of the servo for one turn of the hood; above 0
     * @param maxHoodRadians the hood's stop: how far it turns from its rest, whatever the servo is told
     * @param exit where the piece leaves as the hood sets the angle, or null to leave from the launcher's {@code muzzle}
     */
    public record Hood(String servo, double ratio, double baseElevationRadians, double maxHoodRadians, Exit exit) {
        public Hood {
            if (!(ratio > 0) || !(maxHoodRadians > 0)) {
                throw new IllegalArgumentException("a hood needs a gear ratio and a range above 0: " + servo);
            }
        }

        /** A hood whose piece leaves from the launcher's muzzle. */
        public Hood(String servo, double ratio, double baseElevationRadians, double maxHoodRadians) {
            this(servo, ratio, baseElevationRadians, maxHoodRadians, null);
        }

        /** A hood with no stop short of the servo's travel. */
        public Hood(String servo, double ratio, double baseElevationRadians) {
            this(servo, ratio, baseElevationRadians, Double.POSITIVE_INFINITY, null);
        }

        /** The launch elevation when the servo's shaft has turned {@code servoAngleRad}. */
        public double elevationRadians(double servoAngleRad) {
            return baseElevationRadians + Math.min(maxHoodRadians, servoAngleRad / ratio);
        }
    }

    /**
     * Where a piece leaves a launcher whose hood sets the launch angle: the piece rides round a wheel (or any round part) and is let
     * go where its path points along the launch, so it leaves on the circle of {@code radiusM} about {@code pivot}, on the side that
     * the launch points away from. With the launch {@code elevation} above the horizontal and heading {@code (hx, hy)} (a unit vector)
     * that is {@code pivot + radius * (-sin(elevation) * h, cos(elevation))}: low and forward for a flat launch, back over the wheel's
     * axle for a steep one.
     *
     * @param pivot   the centre of the round part, in the robot frame with the turret facing forward
     * @param radiusM the distance from it to the piece's centre as it leaves: the part's radius and the piece's
     */
    public record Exit(Vec3 pivot, double radiusM) {
        public Exit {
            if (!(radiusM > 0)) {
                throw new IllegalArgumentException("an exit needs a radius above 0");
            }
        }

        /** The piece's centre as it leaves, for a launch at {@code elevationRadians} along the unit heading {@code (hx, hy)}. */
        public Vec3 at(double elevationRadians, double hx, double hy) {
            double sin = StrictMath.sin(elevationRadians);
            return new Vec3(
                    pivot.x() - radiusM * sin * hx, pivot.y() - radiusM * sin * hy, pivot.z() + radiusM * StrictMath.cos(elevationRadians));
        }
    }

    /**
     * The motor that turns the launcher. Its shaft angle is zero when the session starts, which is the launcher facing
     * along {@link LaunchSpec#direction}, and has no limit.
     *
     * @param ratio motor revolutions for one turn of the turret; above 0
     * @param sign  +1 when the motor turning forward turns the turret to the left (counter-clockwise seen from above)
     */
    public record Turret(String motor, double ratio, int sign) {
        public Turret {
            if (!(ratio > 0) || Math.abs(sign) != 1) {
                throw new IllegalArgumentException("a turret needs a gear ratio above 0 and a sign of 1 or -1: " + motor);
            }
        }

        /** The turret's yaw, counter-clockwise from the launcher facing along its direction, when its shaft has turned {@code shaftAngleRad}. */
        public double yawRadians(double shaftAngleRad) {
            return sign * shaftAngleRad / ratio;
        }
    }

    /**
     * One flywheel.
     *
     * @param sign +1 when the motor turning forward drives the wheel's surface the way the piece leaves
     */
    public record Wheel(String motor, double radiusM, int sign) {
        public Wheel {
            if (!(radiusM > 0) || Math.abs(sign) != 1) {
                throw new IllegalArgumentException("a wheel needs a positive radius and a sign of 1 or -1: " + motor);
            }
        }
    }

    /** A launcher that is fixed to the robot. */
    public LaunchSpec(
            String name,
            String storage,
            List<Condition> active,
            List<Wheel> wheels,
            double efficiency,
            Vec3 direction,
            double dribbleSpeed) {
        this(name, storage, active, wheels, efficiency, direction, dribbleSpeed, null, null);
    }

    /** A launcher on a turret, with no hood. */
    public LaunchSpec(
            String name,
            String storage,
            List<Condition> active,
            List<Wheel> wheels,
            double efficiency,
            Vec3 direction,
            double dribbleSpeed,
            Turret turret) {
        this(name, storage, active, wheels, efficiency, direction, dribbleSpeed, turret, null);
    }

    /** A launcher on a turret with a hood, leaving from the end of the path. */
    public LaunchSpec(
            String name,
            String storage,
            List<Condition> active,
            List<Wheel> wheels,
            double efficiency,
            Vec3 direction,
            double dribbleSpeed,
            Turret turret,
            Hood hood) {
        this(name, storage, active, wheels, efficiency, direction, dribbleSpeed, turret, hood, null);
    }

    public LaunchSpec {
        active = List.copyOf(active);
        wheels = List.copyOf(wheels);
        if (!(efficiency > 0 && efficiency <= 1) || !(dribbleSpeed >= 0) || !(direction.length() > 0)) {
            throw new IllegalArgumentException(
                    "a launcher needs an efficiency in (0, 1], a speed that is not negative and a direction: " + name);
        }
        direction = direction.times(1.0 / direction.length());
    }
}
