package dev.ftcsim.hardware.device.odometry;

import dev.ftcsim.hardware.device.DeviceConfig;

/**
 * How an odometry pod, a wheel on an encoder that only measures, is bolted to the chassis. Coordinates are in the
 * body frame, metres, X forward and Y left; the position is where the wheel touches the floor.
 *
 * @param axisRadians  the direction the wheel rolls in when it counts up, counter-clockwise from body +X
 * @param ticksPerMetre encoder counts per metre of travel along the axis
 * @param direction    +1 if the encoder counts up for travel along the axis, -1 if the pod is mounted so that it
 *                     counts down; the robot code's own encoder direction setting must undo that
 */
public record PodMount(double x, double y, double axisRadians, double ticksPerMetre, int direction) {

    public PodMount {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(axisRadians)) {
            throw new IllegalArgumentException("a pod needs a finite position and axis");
        }
        if (!(ticksPerMetre > 0) || Double.isInfinite(ticksPerMetre)) {
            throw new IllegalArgumentException("ticksPerMetre must be positive, not " + ticksPerMetre);
        }
        if (direction != 1 && direction != -1) {
            throw new IllegalArgumentException("direction must be 1 or -1, not " + direction);
        }
    }

    /**
     * Reads the pod whose settings are named {@code prefix.x}, {@code prefix.y}, {@code prefix.ticksPerMetre},
     * {@code prefix.direction} (default 1) and {@code prefix.axisDegrees} (default {@code defaultAxisDegrees}).
     */
    public static PodMount read(DeviceConfig config, String prefix, double defaultAxisDegrees) {
        double x = config.number(prefix + ".x");
        double y = config.number(prefix + ".y");
        double axisDegrees = config.number(prefix + ".axisDegrees", defaultAxisDegrees);
        double ticksPerMetre = config.number(prefix + ".ticksPerMetre");
        int direction = config.integer(prefix + ".direction", 1);
        try {
            return new PodMount(x, y, StrictMath.toRadians(axisDegrees), ticksPerMetre, direction);
        } catch (IllegalArgumentException invalid) {
            throw config.rejected(prefix, "is not a usable pod: " + invalid.getMessage());
        }
    }
}
