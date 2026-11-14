package dev.ftcsim.hardware.device.bosch;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.camera.Mat3;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * A BNO055 on a chassis whose true state is a function of time that the test writes, read and written the way the
 * SDK's drivers do: a register number written, then bytes read; or a register number and the bytes to store.
 */
final class Bno055Rig {

    // The chip's register numbers, from the datasheet and the SDK driver's Register enum.
    static final int CHIP_ID = 0x00;
    static final int ACC_ID = 0x01;
    static final int MAG_ID = 0x02;
    static final int GYR_ID = 0x03;
    static final int PAGE_ID = 0x07;
    static final int ACC_DATA = 0x08;
    static final int MAG_DATA = 0x0E;
    static final int GYR_DATA = 0x14;
    static final int EUL_DATA = 0x1A;
    static final int QUA_DATA = 0x20;
    static final int LIA_DATA = 0x28;
    static final int GRV_DATA = 0x2E;
    static final int TEMP = 0x34;
    static final int CALIB_STAT = 0x35;
    static final int ST_RESULT = 0x36;
    static final int SYS_STAT = 0x39;
    static final int SYS_ERR = 0x3A;
    static final int UNIT_SEL = 0x3B;
    static final int OPR_MODE = 0x3D;
    static final int PWR_MODE = 0x3E;
    static final int SYS_TRIGGER = 0x3F;
    static final int AXIS_MAP_CONFIG = 0x41;
    static final int AXIS_MAP_SIGN = 0x42;
    static final int ACC_OFFSET_X_LSB = 0x55;
    // On page 1:
    static final int ACC_CONFIG = 0x08;
    static final int GYR_CONFIG_0 = 0x0A;
    static final int UNIQUE_ID_FIRST = 0x50;

    static final int CONFIG = 0x00;
    static final int ACCONLY = 0x01;
    static final int GYRONLY = 0x03;
    static final int IMU = 0x08;
    static final int NDOF = 0x0C;

    /** Degrees, metres per second squared, Celsius and the Android pitch convention: what the SDK's new driver selects. */
    static final int UNITS_DEGREES = 0x80;
    /** Radians for angles and rates, as the legacy driver's default parameters select. */
    static final int UNITS_RADIANS = 0x80 | 0x04 | 0x02;

    static final long MS = WorldRig.MS;
    static final long SECOND = WorldRig.SECOND;
    static final double G = 9.80665;

    /** The chassis, as a function of simulated time. */
    private static final class Motion implements BodyView {
        private final LongFunction<BodyPose> truth;
        private long now;

        Motion(LongFunction<BodyPose> truth) {
            this.truth = truth;
        }

        void advanceTo(long nanos) {
            now = nanos;
        }

        @Override
        public BodyPose sample() {
            return truth.apply(now);
        }
    }

    final WorldRig world;
    final I2cPeripheral chip;

    Bno055Rig(LongFunction<BodyPose> truth, Map<String, Object> settings) {
        Motion motion = new Motion(truth);
        world = new WorldRig(motion, motion::advanceTo, (origin, direction, maxMeters) -> null);
        chip = DeviceModels.standard().create(DeviceModels.BNO055_IMU, "imu", settings, world.host);
    }

    Bno055Rig(LongFunction<BodyPose> truth) {
        this(truth, new HashMap<>());
    }

    /** A chassis standing still with the given yaw, pitch (positive nose down) and roll (positive right side down). */
    static LongFunction<BodyPose> standing(double yaw, double pitch, double roll) {
        return nanos -> pose(nanos, yaw, pitch, roll, Vec3.ZERO, Vec3.ZERO);
    }

    /**
     * The chassis state with the orientation of intrinsic yaw, pitch, roll rotations (about Z, then Y, then X), the
     * velocity of its origin and its angular velocity, both in the field frame.
     */
    static BodyPose pose(long nanos, double yaw, double pitch, double roll, Vec3 velocity, Vec3 angularVelocity) {
        double[] q = Mat3.fromYawPitchRoll(yaw, pitch, roll).toQuaternion();
        return new BodyPose(
                nanos, 0, 0, 0, q[0], q[1], q[2], q[3],
                velocity.x(), velocity.y(), velocity.z(), angularVelocity.x(), angularVelocity.y(), angularVelocity.z());
    }

    byte[] read(int register, int count) {
        return chip.writeRead(new byte[] {(byte) register}, count, world.now());
    }

    int readByte(int register) {
        return read(register, 1)[0] & 0xFF;
    }

    /** The signed 16-bit register pair starting at {@code register}. */
    int readShort(int register) {
        byte[] bytes = read(register, 2);
        return (short) ((bytes[0] & 0xFF) | bytes[1] << 8);
    }

    /** Three consecutive signed 16-bit values, as the vector registers hold them. */
    int[] readVector(int register) {
        byte[] bytes = read(register, 6);
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            values[i] = (short) ((bytes[2 * i] & 0xFF) | bytes[2 * i + 1] << 8);
        }
        return values;
    }

    void write(int register, int... values) {
        byte[] bytes = new byte[values.length + 1];
        bytes[0] = (byte) register;
        for (int i = 0; i < values.length; i++) {
            bytes[i + 1] = (byte) values[i];
        }
        chip.write(bytes, world.now());
    }

    /**
     * What the SDK's drivers do to start the chip: select the units in CONFIG mode, enter a mode, and wait for the
     * first data. Time starts at 0.7 s, so that tests that read the registers see a chip that has been running a while.
     */
    void startIn(int mode, int units) {
        world.runTo(Math.max(world.now(), 700 * MS));
        write(UNIT_SEL, units);
        write(OPR_MODE, mode);
        world.runTo(world.now() + 100 * MS);
    }
}
