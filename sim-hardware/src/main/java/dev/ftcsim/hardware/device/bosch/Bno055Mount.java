package dev.ftcsim.hardware.device.bosch;

import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.camera.Mat3;
import dev.ftcsim.hardware.device.DeviceConfig;
import java.util.Arrays;
import java.util.Locale;

/**
 * How a BNO055 sits on the robot: which way its board faces, and so where the chip's own axes point in the chassis
 * frame (X forward, Y left, Z up).
 *
 * <p>A board is described the way the SDK describes REV hubs and REV and AndyMark IMUs, by the direction its logo
 * faces and the direction its connector (the USB ports of a hub, the I2C port of an external IMU) faces. Those two
 * give the board's own axes: Z along the logo, Y along the connector, X completing the right-handed set. The chip
 * is soldered to the board turned a quarter turn about Z, so that the SDK drivers have a fixed rotation to undo:
 * the chip's X axis is opposite to the board's Y, and its Y is the board's X. With the logo up and the connector
 * forward that puts the chip's X axis towards the back of the robot and its Y towards the right.
 *
 * <p>The chip can also remap its axes itself ({@code AXIS_MAP_CONFIG} and {@code AXIS_MAP_SIGN}); {@link #frame}
 * gives the axes after a remap.
 */
final class Bno055Mount {

    /** The directions a board can face, as the SDK names them, in the chassis frame. */
    private enum Direction {
        UP(0, 0, 1),
        DOWN(0, 0, -1),
        FORWARD(1, 0, 0),
        BACKWARD(-1, 0, 0),
        LEFT(0, 1, 0),
        RIGHT(0, -1, 0);

        private final Vec3 vector;

        Direction(double x, double y, double z) {
            this.vector = new Vec3(x, y, z);
        }
    }

    /**
     * The axes of the chip as it senses and reports, in the chassis frame.
     *
     * @param sensor      the three axes in order, as columns; after a remap that mirrors the chip they are left-handed,
     *                    and a vector in the chip's frame is the dot product of a vector with each of them
     * @param orientation the right-handed frame an orientation is reported in: the same axes, with the third made the
     *                    cross product of the first two (a mirrored frame has no rotation to report)
     */
    record Frame(Mat3 sensor, Mat3 orientation) {

        static Frame of(Vec3 x, Vec3 y, Vec3 z) {
            Mat3 sensor = Mat3.columns(x, y, z);
            boolean rightHanded = x.cross(y).dot(z) > 0;
            return new Frame(sensor, rightHanded ? sensor : Mat3.columns(x, y, x.cross(y)));
        }
    }

    private final Vec3[] axes;

    private Bno055Mount(Vec3 logo, Vec3 connector) {
        axes = new Vec3[] {connector.times(-1), connector.cross(logo), logo};
    }

    /**
     * Reads {@code mount.logo} and {@code mount.port} of a profile (the directions UP, DOWN, FORWARD, BACKWARD, LEFT
     * and RIGHT; logo up and port forward when absent).
     *
     * @throws IllegalArgumentException if a direction is not one of those, or the two are not at right angles
     */
    static Bno055Mount read(DeviceConfig config) {
        Direction logo = direction(config, "mount.logo", Direction.UP);
        Direction port = direction(config, "mount.port", Direction.FORWARD);
        if (logo.vector.dot(port.vector) != 0) {
            throw config.rejected(
                    "mount.port",
                    "cannot face " + port + " when the logo faces " + logo + ": a board's connector is at right angles to its logo");
        }
        return new Bno055Mount(logo.vector, port.vector);
    }

    /** The axes as the chip leaves them at power-up, with the identity remap. */
    Frame frame() {
        return Frame.of(axes[0], axes[1], axes[2]);
    }

    /**
     * The axes after the chip's remap registers. Each output axis takes the sensor axis its two bits select (0 X,
     * 1 Y, 2 Z), negated if its sign bit is set; settings that do not use each sensor axis exactly once are not a
     * remap the chip documents, and it keeps its axes.
     */
    Frame frame(int axisMapConfig, int axisMapSign) {
        int[] source = {axisMapConfig & 3, (axisMapConfig >> 2) & 3, (axisMapConfig >> 4) & 3};
        int[] sorted = source.clone();
        Arrays.sort(sorted);
        if (!Arrays.equals(sorted, new int[] {0, 1, 2})) {
            return frame();
        }
        Vec3 x = axes[source[0]].times((axisMapSign & 4) != 0 ? -1 : 1);
        Vec3 y = axes[source[1]].times((axisMapSign & 2) != 0 ? -1 : 1);
        Vec3 z = axes[source[2]].times((axisMapSign & 1) != 0 ? -1 : 1);
        return Frame.of(x, y, z);
    }

    private static Direction direction(DeviceConfig config, String key, Direction fallback) {
        String text = config.text(key, fallback.name());
        try {
            return Direction.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw config.rejected(key, "must be one of " + Arrays.toString(Direction.values()) + ", not \"" + text + "\"");
        }
    }
}
