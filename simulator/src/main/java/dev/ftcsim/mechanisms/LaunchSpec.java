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
 */
public record LaunchSpec(
        String name,
        String storage,
        List<Condition> active,
        List<Wheel> wheels,
        double efficiency,
        Vec3 direction,
        double dribbleSpeed,
        Turret turret) {

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
        this(name, storage, active, wheels, efficiency, direction, dribbleSpeed, null);
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
