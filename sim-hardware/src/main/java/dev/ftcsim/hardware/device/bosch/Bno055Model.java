package dev.ftcsim.hardware.device.bosch;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.camera.Mat3;
import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;

/**
 * The Bosch BNO055 inertial sensor, as the SDK's drivers for it talk to it: a REV hub's own IMU, the REV 9-axis
 * IMU, the AndyMark IMU and the Adafruit breakout are this one chip, and {@code IMU}, {@code BNO055IMU} and the
 * concrete BNO055 drivers (new and legacy) run unchanged on top of this model.
 *
 * <p><b>What it reports comes from the world.</b> Its accelerometer, gyroscope and fusion outputs are computed from
 * the true chassis: its orientation and angular velocity, and its acceleration (the change of its velocity). Nothing
 * the robot code tells the SDK reaches the chip; code that tells the SDK a mounting that is not the real one gets
 * the wrong angles, as on a robot. The chip is mounted as the profile says ({@code mount.logo} and
 * {@code mount.port}, see {@link Bno055Mount}), logo up and port forward by default, which is what the SDK assumes
 * when it is not told otherwise.
 *
 * <p><b>Reference.</b> The fusion modes without a magnetometer, the one a robot uses, measure orientation against
 * gravity and heading relative to where the chip pointed when it powered up or last reset: heading zero is the heading
 * of the chip's own axes at that moment, which for a chip that lies flat is the chassis heading (and it also holds when
 * the chip changes mode: the reference is a property of the powered chip, not of the mode). Pitch and roll are
 * absolute. The compass modes run as the IMU mode does, since the simulated world has no magnetic field; the
 * magnetometer reads zero.
 *
 * <p><b>Timeline</b> (datasheet typical values, so estimates): the chip is powered up before the simulation starts,
 * as a robot's chips are long before its program runs, so it answers from the first instant; after a reset
 * ({@code SYS_TRIGGER} bit 5) it does not answer for {@value #BOOT_MILLIS} ms, and while it boots every register reads
 * 0xFF, as the SDK driver's comments describe it. It powers up in CONFIG mode. After a switch to an operating mode its
 * outputs read zero for {@value #DATA_START_MILLIS} ms (the SDK's driver comments say about 50 ms before valid data)
 * and then update at 100 Hz, from the latest physics step. The chip looks at the world once it has been talked to: at
 * every physics step, to hold the latest sample. Robot code can keep the simulation's baton for a long stretch of
 * simulated time, with no physics step in it (the SDK's {@code resetYaw} polls the chip for up to half a second inside
 * a synchronized method), and then an output that is overdue by a whole period is latched from that latest sample when
 * the chip is read, so that the poll finds data as it would on a robot. Calibration levels rise with time in the
 * mode: the gyroscope reaches 3 after {@value #GYRO_CALIBRATION_MILLIS} ms and the accelerometer after
 * {@value #ACCELEROMETER_CALIBRATION_MILLIS} ms; the magnetometer never does, and the system level is the lowest
 * level of the sensors the mode uses. The temperature is a constant {@value #TEMPERATURE_CELSIUS} degrees Celsius.
 *
 * <p><b>Registers.</b> The identity and status registers, the unit selection (which, like the axis remap, the
 * sensor configuration of page 1 and the calibration offsets, only takes writes in CONFIG mode), the accelerometer,
 * gyroscope, Euler, quaternion, linear acceleration, gravity and temperature outputs in the units the driver
 * selected, and the axis remap registers are modelled. The magnetometer, the interrupts, self-test, the power modes
 * and the clock selection are not: their registers read what was written, and do nothing. Gyroscope and
 * accelerometer ranges clip what they report. The only imperfection is the profile's {@code gyroBiasRadPerSec}
 * (default 0), a drift of the heading at a constant rate, counted from power-up or the last reset, which the gyroscope
 * outputs show as well.
 *
 * <p>Profile settings: {@code address} (40, the Control Hub's own IMU, the Adafruit breakout and the AndyMark
 * IMU, or 41, the REV 9-axis IMU; default 40), {@code mount.logo} and {@code mount.port}, {@code gyroBiasRadPerSec}.
 */
public final class Bno055Model extends RegisterFilePeripheral {

    /** The address of the chip with its address pin low, which is the default of every SDK driver but the REV one. */
    public static final int ADDRESS = 0x28;

    /** The address with the pin high: the one the REV 9-axis IMU is built with. */
    public static final int ALTERNATE_ADDRESS = 0x29;

    static final long BOOT_MILLIS = 650;
    static final long DATA_START_MILLIS = 50;
    static final long GYRO_CALIBRATION_MILLIS = 3000;
    static final long ACCELEROMETER_CALIBRATION_MILLIS = 6000;
    static final int TEMPERATURE_CELSIUS = 30;

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double NANOS_PER_SECOND = 1e9;
    /** The fusion output rate of the chip is 100 Hz. */
    private static final long OUTPUT_PERIOD_NANOS = 10 * NANOS_PER_MILLI;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double GRAVITY = 9.80665;

    private static final int CHIP_ID = 0x00;
    private static final int ACC_ID = 0x01;
    private static final int MAG_ID = 0x02;
    private static final int GYR_ID = 0x03;
    private static final int SW_REV_ID_LSB = 0x04;
    private static final int SW_REV_ID_MSB = 0x05;
    private static final int BL_REV_ID = 0x06;
    private static final int PAGE_ID = 0x07;
    private static final int ACC_DATA = 0x08;
    private static final int GYR_DATA = 0x14;
    private static final int EUL_DATA = 0x1A;
    private static final int QUA_DATA = 0x20;
    private static final int LIA_DATA = 0x28;
    private static final int GRV_DATA = 0x2E;
    private static final int TEMP = 0x34;
    private static final int CALIB_STAT = 0x35;
    private static final int ST_RESULT = 0x36;
    private static final int SYS_STAT = 0x39;
    private static final int UNIT_SEL = 0x3B;
    private static final int OPR_MODE = 0x3D;
    private static final int PWR_MODE = 0x3E;
    private static final int SYS_TRIGGER = 0x3F;
    private static final int TEMP_SOURCE = 0x40;
    private static final int AXIS_MAP_CONFIG = 0x41;
    private static final int AXIS_MAP_SIGN = 0x42;
    /** The first register only CONFIG mode writes of the main page, after the unit selection and the axis remap. */
    private static final int SIC_MATRIX = 0x43;
    private static final int RADIUS_LAST = 0x6A;

    private static final int ACC_CONFIG = 0x08;
    private static final int GYR_CONFIG_0 = 0x0A;
    private static final int PAGE_ONE_CONFIG_LAST = 0x1F;
    private static final int UNIQUE_ID = 0x50;
    private static final int UNIQUE_ID_BYTES = 16;

    private static final int CHIP_ID_VALUE = 0xA0;
    private static final int ST_RESULT_ALL_PASSED = 0x0F;
    private static final int SYS_TRIGGER_RESET = 0x20;

    private static final int UNIT_ACCELERATION_MG = 1;
    private static final int UNIT_RATE_RADIANS = 1 << 1;
    private static final int UNIT_ANGLE_RADIANS = 1 << 2;
    private static final int UNIT_TEMPERATURE_FAHRENHEIT = 1 << 4;
    private static final int UNIT_ORIENTATION_ANDROID = 1 << 7;
    private static final int UNIT_SEL_DEFAULT = UNIT_ORIENTATION_ANDROID;
    private static final int UNIT_SEL_USED = 0x97;

    private static final int STATUS_IDLE = 0;
    private static final int STATUS_RUNNING_FUSION = 5;
    private static final int STATUS_RUNNING_NO_FUSION = 6;

    private static final int AXIS_MAP_CONFIG_DEFAULT = 0x24;
    private static final int BOOTING = 0xFF;

    /** Gyroscope ranges by the low bits of GYR_CONFIG_0, in degrees per second. */
    private static final double[] GYRO_RANGE_DPS = {2000, 1000, 500, 250, 125};
    /** Accelerometer ranges by the low bits of ACC_CONFIG, in multiples of gravity. */
    private static final double[] ACCELEROMETER_RANGE_G = {2, 4, 8, 16};

    private final BodyView body;
    private final Bno055Mount mount;
    private final double gyroBias;
    private final byte[] configuration = new byte[256];

    private int page;
    private Bno055Mode mode = Bno055Mode.CONFIG;
    private Bno055Mount.Frame frame;
    private boolean awake;
    private long bootDoneNanos;
    private long referenceNanos;
    private double referenceYaw;
    private long modeEnteredNanos;
    private long nextOutputNanos;
    /** The chassis as the world last showed it to the chip: at its construction, and at every physics step once used. */
    private BodyPose seen;
    /** Whether anything has talked to the chip; until then it only wakes up, and looks at the world no more. */
    private boolean used;
    private Vec3 previousVelocity;
    private long previousPoseNanos;
    private Vec3 lastAcceleration = Vec3.ZERO;

    private Bno055Model(int address, DeviceContext context, Bno055Mount mount, double gyroBias) {
        super(address);
        this.body = context.body();
        this.mount = mount;
        this.gyroBias = gyroBias;
        this.seen = body.sample();
        // The chip has been powered for as long as the robot has, which is far longer than the program: it has booted.
        powerUp(context.nowNanos() - BOOT_MILLIS * NANOS_PER_MILLI);
    }

    /** The registry's factory. */
    public static I2cPeripheral create(DeviceConfig config, DeviceContext context) {
        int address = config.integer("address", ADDRESS);
        if (address != ADDRESS && address != ALTERNATE_ADDRESS) {
            throw config.rejected("address", "must be " + ADDRESS + " (0x28) or " + ALTERNATE_ADDRESS
                    + " (0x29), the two addresses the chip can have, not " + address);
        }
        Bno055Mount mount = Bno055Mount.read(config);
        double bias = config.number("gyroBiasRadPerSec", 0);
        Bno055Model model = new Bno055Model(address, context, mount, bias);
        context.onStep(model::afterStep);
        return model;
    }

    /** One update of the chip, after the physics step that ended at {@code stepNanos}. */
    void afterStep(long stepNanos) {
        if (used || !awake) {
            seen = body.sample();
        }
        if (!awake) {
            if (stepNanos < bootDoneNanos) {
                return;
            }
            wake();
        }
        if (mode == Bno055Mode.CONFIG || stepNanos < nextOutputNanos) {
            return;
        }
        latch(stepNanos);
    }

    /**
     * The chip computes its outputs on its own clock, whether the world steps or not. Robot code can hold the
     * simulation's baton for a long stretch of simulated time (the SDK's {@code resetYaw} polls the chip inside a
     * synchronized method for up to half a second), and the world cannot step meanwhile. A chip whose next output
     * is more than a whole output period overdue, which a world that steps never allows, therefore latches it when it
     * is read, from the latest sample it has of the world.
     */
    private void catchUp(long nowNanos) {
        if (mode != Bno055Mode.CONFIG && nowNanos >= nextOutputNanos + OUTPUT_PERIOD_NANOS) {
            latch(nowNanos);
        }
    }

    private void latch(long atNanos) {
        output(atNanos);
        nextOutputNanos = (atNanos / OUTPUT_PERIOD_NANOS + 1) * OUTPUT_PERIOD_NANOS;
    }

    @Override
    protected int readRegister(int register, long nowNanos) {
        used = true;
        if (nowNanos < bootDoneNanos) {
            return BOOTING;
        }
        wakeIfDue();
        catchUp(nowNanos);
        if (page == 1) {
            return register == PAGE_ID ? 1 : configuration[register] & 0xFF;
        }
        return switch (register) {
            case TEMP -> temperature();
            case CALIB_STAT -> calibration(nowNanos);
            default -> peek(register);
        };
    }

    @Override
    protected void writeRegister(int register, int value, long nowNanos) {
        used = true;
        if (nowNanos < bootDoneNanos) {
            return;
        }
        wakeIfDue();
        if (register == PAGE_ID) {
            if (value == 0 || value == 1) {
                page = value;
            }
        } else if (page == 1) {
            if (register >= ACC_CONFIG && register <= PAGE_ONE_CONFIG_LAST && mode == Bno055Mode.CONFIG) {
                configuration[register] = (byte) value;
            }
        } else {
            writeMainPage(register, value, nowNanos);
        }
    }

    private void writeMainPage(int register, int value, long nowNanos) {
        switch (register) {
            case OPR_MODE -> enter(Bno055Mode.fromBits(value & 0x0F), nowNanos);
            case PWR_MODE, TEMP_SOURCE -> poke(register, value & 0x03);
            case SYS_TRIGGER -> {
                if ((value & SYS_TRIGGER_RESET) != 0) {
                    powerUp(nowNanos);
                }
            }
            case UNIT_SEL -> configure(register, value & UNIT_SEL_USED);
            case AXIS_MAP_CONFIG -> {
                configure(register, value & 0x3F);
                frame = mount.frame(peek(AXIS_MAP_CONFIG), peek(AXIS_MAP_SIGN));
            }
            case AXIS_MAP_SIGN -> {
                configure(register, value & 0x07);
                frame = mount.frame(peek(AXIS_MAP_CONFIG), peek(AXIS_MAP_SIGN));
            }
            default -> {
                if (register >= SIC_MATRIX && register <= RADIUS_LAST) {
                    configure(register, value);
                }
                // The other registers are read-only; writing them has no effect.
            }
        }
    }

    /** Stores a setting that the chip only accepts in CONFIG mode. */
    private void configure(int register, int value) {
        if (mode == Bno055Mode.CONFIG) {
            poke(register, value);
        }
    }

    /** Power-up and reset: the chip is mute while it boots, then it is in CONFIG mode with its registers at defaults. */
    private void powerUp(long startedNanos) {
        awake = false;
        bootDoneNanos = startedNanos + BOOT_MILLIS * NANOS_PER_MILLI;
        mode = Bno055Mode.CONFIG;
        page = 0;
        for (int register = 0; register < 256; register++) {
            poke(register, 0);
            configuration[register] = 0;
        }
        poke(CHIP_ID, CHIP_ID_VALUE);
        poke(ACC_ID, 0xFB);
        poke(MAG_ID, 0x32);
        poke(GYR_ID, 0x0F);
        poke(SW_REV_ID_LSB, 0x11);
        poke(SW_REV_ID_MSB, 0x03);
        poke(BL_REV_ID, 0x15);
        poke(ST_RESULT, ST_RESULT_ALL_PASSED);
        poke(SYS_STAT, STATUS_IDLE);
        poke(UNIT_SEL, UNIT_SEL_DEFAULT);
        poke(TEMP_SOURCE, 0x01);
        poke(AXIS_MAP_CONFIG, AXIS_MAP_CONFIG_DEFAULT);
        configuration[ACC_CONFIG] = 0x0D;
        configuration[0x09] = 0x6D;
        configuration[GYR_CONFIG_0] = 0x38;
        for (int i = 0; i < UNIQUE_ID_BYTES; i++) {
            configuration[UNIQUE_ID + i] = (byte) (0xA5 ^ (31 * i));
        }
        frame = mount.frame();
    }

    private void wakeIfDue() {
        if (!awake) {
            wake();
        }
    }

    /** The chip has booted: heading zero is the way the chassis faces now. */
    private void wake() {
        awake = true;
        referenceNanos = bootDoneNanos;
        referenceYaw = chipToField(seen).yaw();
    }

    private void enter(Bno055Mode next, long nowNanos) {
        if (next == null || next == mode) {
            return;
        }
        mode = next;
        poke(OPR_MODE, next.bits);
        poke(SYS_STAT, next == Bno055Mode.CONFIG ? STATUS_IDLE : next.fusion ? STATUS_RUNNING_FUSION : STATUS_RUNNING_NO_FUSION);
        for (int register = ACC_DATA; register < TEMP; register++) {
            poke(register, 0);
        }
        modeEnteredNanos = nowNanos;
        nextOutputNanos = nowNanos + DATA_START_MILLIS * NANOS_PER_MILLI;
        previousVelocity = null;
        lastAcceleration = Vec3.ZERO;
    }

    private Mat3 chipToField(BodyPose pose) {
        return Mat3.fromQuaternion(pose.qw(), pose.qx(), pose.qy(), pose.qz()).times(frame.orientation());
    }

    /** Writes the outputs of the sensors the mode runs, from the latest sample of the world, at the chip's time {@code atNanos}. */
    private void output(long atNanos) {
        BodyPose pose = seen;
        Mat3 bodyToField = Mat3.fromQuaternion(pose.qw(), pose.qx(), pose.qy(), pose.qz());
        Mat3 fieldToChip = frame.sensor().transpose().times(bodyToField.transpose());
        int units = peek(UNIT_SEL);
        double accelerationScale = (units & UNIT_ACCELERATION_MG) == 0 ? 100.0 : 1000.0 / GRAVITY;
        double degreesScale = 16.0 * (180.0 / Math.PI);
        double rateScale = (units & UNIT_RATE_RADIANS) == 0 ? degreesScale : 900.0;
        double angleScale = (units & UNIT_ANGLE_RADIANS) == 0 ? degreesScale : 900.0;

        Vec3 gravity = fieldToChip.times(new Vec3(0, 0, GRAVITY));
        Vec3 linear = fieldToChip.times(acceleration(pose));
        if (mode.accelerometer) {
            double limit = ACCELEROMETER_RANGE_G[configuration[ACC_CONFIG] & 3] * GRAVITY;
            putVector(ACC_DATA, clip(gravity.plus(linear), limit), accelerationScale);
        }
        if (mode.gyroscope) {
            int range = configuration[GYR_CONFIG_0] & 7;
            double limit = Math.toRadians(GYRO_RANGE_DPS[range < GYRO_RANGE_DPS.length ? range : 0]);
            Vec3 rate = fieldToChip.times(new Vec3(pose.wx(), pose.wy(), pose.wz() + gyroBias));
            putVector(GYR_DATA, clip(rate, limit), rateScale);
        }
        if (mode.fusion) {
            double drift = gyroBias * (atNanos - referenceNanos) / NANOS_PER_SECOND;
            Mat3 orientation = Mat3.rotZ(drift - referenceYaw).times(bodyToField.times(frame.orientation()));
            putOrientation(orientation, units, angleScale);
            putVector(LIA_DATA, linear, accelerationScale);
            putVector(GRV_DATA, gravity, accelerationScale);
        }
    }

    /**
     * The chassis acceleration over the interval between the world samples of this output and the previous one, in
     * the field frame; the previous value while the world has not moved on.
     */
    private Vec3 acceleration(BodyPose pose) {
        if (previousVelocity == null || pose.timeNanos() > previousPoseNanos) {
            if (previousVelocity != null) {
                lastAcceleration = pose.velocity().minus(previousVelocity).times(NANOS_PER_SECOND / (pose.timeNanos() - previousPoseNanos));
            }
            previousVelocity = pose.velocity();
            previousPoseNanos = pose.timeNanos();
        }
        return lastAcceleration;
    }

    private void putOrientation(Mat3 orientation, int units, double angleScale) {
        // Heading is a compass angle, which grows clockwise; roll and pitch are the right-handed rotations about the
        // chip's Y and X axes with the sign reversed, which is how the chip counts them (the SDK driver undoes it).
        double heading = -orientation.yaw();
        heading -= TWO_PI * StrictMath.floor(heading / TWO_PI);
        double roll = -orientation.pitch();
        double pitch = (units & UNIT_ORIENTATION_ANDROID) != 0 ? -orientation.roll() : orientation.roll();
        // A heading that rounds up to a full turn is the heading zero.
        long turn = Math.round(TWO_PI * angleScale);
        long headingCounts = Math.round(heading * angleScale);
        putShort(EUL_DATA, headingCounts >= turn ? headingCounts - turn : headingCounts);
        putShort(EUL_DATA + 2, roll * angleScale);
        putShort(EUL_DATA + 4, pitch * angleScale);
        double[] q = orientation.toQuaternion();
        for (int i = 0; i < 4; i++) {
            putShort(QUA_DATA + 2 * i, q[i] * 16384.0);
        }
    }

    private int temperature() {
        if (mode == Bno055Mode.CONFIG) {
            return 0;
        }
        int units = peek(UNIT_SEL);
        return (units & UNIT_TEMPERATURE_FAHRENHEIT) == 0 ? TEMPERATURE_CELSIUS : (int) Math.round((TEMPERATURE_CELSIUS * 1.8 + 32.0) / 2.0);
    }

    private int calibration(long nowNanos) {
        if (mode == Bno055Mode.CONFIG) {
            return 0;
        }
        long elapsed = nowNanos - modeEnteredNanos;
        int gyroscope = mode.gyroscope ? level(elapsed, GYRO_CALIBRATION_MILLIS) : 0;
        int accelerometer = mode.accelerometer ? level(elapsed, ACCELEROMETER_CALIBRATION_MILLIS) : 0;
        int magnetometer = 0;
        int system = 0;
        if (mode.fusion) {
            system = 3;
            system = mode.gyroscope ? Math.min(system, gyroscope) : system;
            system = mode.accelerometer ? Math.min(system, accelerometer) : system;
            system = mode.magnetometer ? Math.min(system, magnetometer) : system;
        }
        return system << 6 | gyroscope << 4 | accelerometer << 2 | magnetometer;
    }

    private static int level(long elapsedNanos, long fullMillis) {
        return (int) Math.min(3, 3 * elapsedNanos / (fullMillis * NANOS_PER_MILLI));
    }

    private void putVector(int register, Vec3 vector, double scale) {
        putShort(register, vector.x() * scale);
        putShort(register + 2, vector.y() * scale);
        putShort(register + 4, vector.z() * scale);
    }

    private void putShort(int register, double value) {
        int clamped = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
        poke(register, clamped);
        poke(register + 1, clamped >> 8);
    }

    private static Vec3 clip(Vec3 vector, double limit) {
        return new Vec3(
                Math.max(-limit, Math.min(limit, vector.x())),
                Math.max(-limit, Math.min(limit, vector.y())),
                Math.max(-limit, Math.min(limit, vector.z())));
    }
}
