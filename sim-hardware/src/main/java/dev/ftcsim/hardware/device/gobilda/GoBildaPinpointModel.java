package dev.ftcsim.hardware.device.gobilda;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import dev.ftcsim.hardware.device.odometry.DeadReckoner;
import dev.ftcsim.hardware.device.odometry.GyroHeading;
import dev.ftcsim.hardware.device.odometry.OdometryPod;
import dev.ftcsim.hardware.device.odometry.PodMount;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.LittleEndian;

/**
 * The goBILDA Pinpoint Odometry Computer (3110-0002-0001), as the SDK's {@code GoBildaPinpointDriver} talks to it.
 *
 * <p>The chip counts the encoders of two odometry pods, takes its heading from an inertial sensor, and dead-reckons
 * the pose of a tracking point with the offsets, resolution and encoder directions that robot code has written to
 * it. Those settings live in the chip and the pods' real mounting is separate, so a pose that drifts because robot
 * code told the chip the wrong offsets drifts here too. The chip is modelled as the registers of the V3 firmware;
 * it updates once per physics step, from the pods' counts and the true yaw of the chassis.
 *
 * <p>Timeline, estimated: after power-up the chip is not ready for {@value #BOOT_MILLIS} ms and then calibrates its
 * gyro for {@value #CALIBRATION_MILLIS} ms, as it does again after a reset or recalibration. While it is not ready
 * or calibrating the pose and velocities are held.
 *
 * <p>Profile settings: {@code xPod.*} for the forward-measuring pod and {@code yPod.*} for the sideways one (see
 * {@link PodMount#read}; the axis defaults are forward and left, and a pod left out is reported as not detected),
 * {@code gyroBiasRadPerSec} (default 0) for a gyro that drifts, and {@code yawScalar} (default 1) for the chip's
 * factory yaw calibration.
 */
public final class GoBildaPinpointModel implements I2cPeripheral {

    public static final int ADDRESS = 0x31;

    static final long BOOT_MILLIS = 100;
    static final long CALIBRATION_MILLIS = 250;

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double NANOS_PER_SECOND = 1e9;

    private static final int DEVICE_ID = 1;
    private static final int DEVICE_VERSION = 2;
    private static final int DEVICE_STATUS = 3;
    private static final int DEVICE_CONTROL = 4;
    private static final int LOOP_TIME = 5;
    private static final int X_ENCODER_VALUE = 6;
    private static final int Y_ENCODER_VALUE = 7;
    private static final int X_POSITION = 8;
    private static final int Y_POSITION = 9;
    private static final int H_ORIENTATION = 10;
    private static final int X_VELOCITY = 11;
    private static final int Y_VELOCITY = 12;
    private static final int H_VELOCITY = 13;
    private static final int MM_PER_TICK = 14;
    private static final int X_POD_OFFSET = 15;
    private static final int Y_POD_OFFSET = 16;
    private static final int YAW_SCALAR = 17;
    private static final int BULK_READ = 18;
    private static final int QUATERNION_W = 19;
    private static final int QUATERNION_X = 20;
    private static final int QUATERNION_Y = 21;
    private static final int QUATERNION_Z = 22;
    private static final int PITCH = 23;
    private static final int ROLL = 24;
    private static final int SET_BULK_READ = 25;

    private static final int DEVICE_ID_VALUE = 2;
    private static final int FIRMWARE_VERSION = 3;
    /** The chip's loop runs at 1500 Hz. */
    private static final int LOOP_TIME_MICROS = 667;
    /** The goBILDA swingarm pod's resolution, which is what a chip that has not been told otherwise assumes. */
    private static final float DEFAULT_TICKS_PER_MM = 13.26291192f;

    private static final int STATUS_READY = 1;
    private static final int STATUS_CALIBRATING = 1 << 1;
    private static final int STATUS_X_POD_MISSING = 1 << 2;
    private static final int STATUS_Y_POD_MISSING = 1 << 3;

    private static final int CONTROL_RECALIBRATE_IMU = 1;
    private static final int CONTROL_RESET_POSITION_AND_IMU = 1 << 1;
    private static final int CONTROL_Y_REVERSED = 1 << 2;
    private static final int CONTROL_Y_FORWARD = 1 << 3;
    private static final int CONTROL_X_REVERSED = 1 << 4;
    private static final int CONTROL_X_FORWARD = 1 << 5;

    /** What a bulk read returns until robot code chooses otherwise. */
    private static final int[] DEFAULT_BULK_SCOPE = {
        DEVICE_STATUS, LOOP_TIME, X_ENCODER_VALUE, Y_ENCODER_VALUE, X_POSITION, Y_POSITION, H_ORIENTATION,
        X_VELOCITY, Y_VELOCITY, H_VELOCITY
    };

    private final BodyView body;
    private final EncoderSource xPod;
    private final EncoderSource yPod;
    private final GyroHeading gyro;
    private final DeadReckoner reckoner = new DeadReckoner();

    private int selectedRegister;
    private int[] bulkScope = DEFAULT_BULK_SCOPE;
    private boolean xReversed;
    private boolean yReversed;
    private double ticksPerMm = DEFAULT_TICKS_PER_MM;
    private double xPodOffsetMm;
    private double yPodOffsetMm;
    private float yawScalar;

    private long calibrationStartNanos;
    private long readyAtNanos;
    private boolean running;
    private long lastStepNanos;
    private long lastXCounts;
    private long lastYCounts;
    private double velocityX;
    private double velocityY;
    private double velocityHeading;
    private BodyPose latestPose;
    private double imuYawZero;

    GoBildaPinpointModel(DeviceContext context, EncoderSource xPod, EncoderSource yPod, double gyroBias, float yawScalar) {
        this.body = context.body();
        this.xPod = xPod;
        this.yPod = yPod;
        this.yawScalar = yawScalar;
        long now = context.nowNanos();
        latestPose = body.sample();
        imuYawZero = latestPose.yaw();
        gyro = new GyroHeading(gyroBias, yawScalar, latestPose.yaw(), now);
        calibrationStartNanos = now + BOOT_MILLIS * NANOS_PER_MILLI;
        readyAtNanos = calibrationStartNanos + CALIBRATION_MILLIS * NANOS_PER_MILLI;
        lastStepNanos = now;
        lastXCounts = signedCount(xPod, xReversed);
        lastYCounts = signedCount(yPod, yReversed);
    }

    /** The registry's factory. */
    public static I2cPeripheral create(DeviceConfig config, DeviceContext context) {
        EncoderSource xPod = config.hasGroup("xPod") ? OdometryPod.mount(PodMount.read(config, "xPod", 0), context) : null;
        EncoderSource yPod = config.hasGroup("yPod") ? OdometryPod.mount(PodMount.read(config, "yPod", 90), context) : null;
        double bias = config.number("gyroBiasRadPerSec", 0);
        float scalar = (float) config.number("yawScalar", 1.0);
        GoBildaPinpointModel model = new GoBildaPinpointModel(context, xPod, yPod, bias, scalar);
        context.onStep(model::afterStep);
        return model;
    }

    @Override
    public int address() {
        return ADDRESS;
    }

    @Override
    public void write(byte[] bytes, long nowNanos) {
        if (bytes.length == 0) {
            return;
        }
        selectedRegister = bytes[0] & 0xFF;
        if (selectedRegister == SET_BULK_READ && bytes.length > 1) {
            setBulkScope(bytes);
        } else if (bytes.length >= 5) {
            store(selectedRegister, bytes, nowNanos);
        }
    }

    @Override
    public byte[] read(int count, long nowNanos) {
        byte[] data = selectedRegister == BULK_READ ? bulk(nowNanos) : word(selectedRegister, nowNanos);
        byte[] bytes = new byte[count];
        System.arraycopy(data, 0, bytes, 0, Math.min(count, data.length));
        if (count > data.length) {
            bytes[data.length] = crc8(data);
        }
        return bytes;
    }

    /** One update of the chip, after the physics step that ended at {@code stepNanos}. */
    void afterStep(long stepNanos) {
        latestPose = body.sample();
        gyro.sample(latestPose.yaw(), stepNanos);
        long xCounts = signedCount(xPod, xReversed);
        long yCounts = signedCount(yPod, yReversed);
        if (stepNanos < readyAtNanos) {
            running = false;
            velocityX = 0;
            velocityY = 0;
            velocityHeading = 0;
        } else if (!running) {
            // The gyro's zero is where the chassis stands when calibration ends.
            running = true;
            gyro.setHeading(reckoner.heading());
            imuYawZero = latestPose.yaw();
        } else {
            integrate(stepNanos, xCounts - lastXCounts, yCounts - lastYCounts);
        }
        lastXCounts = xCounts;
        lastYCounts = yCounts;
        lastStepNanos = stepNanos;
    }

    private void integrate(long stepNanos, long xTicks, long yTicks) {
        double seconds = (stepNanos - lastStepNanos) / NANOS_PER_SECOND;
        double x = reckoner.x();
        double y = reckoner.y();
        double heading = reckoner.heading();
        reckoner.advance(
                xTicks / ticksPerMm, yTicks / ticksPerMm, gyro.heading() - heading, xPodOffsetMm, yPodOffsetMm);
        velocityX = (reckoner.x() - x) / seconds;
        velocityY = (reckoner.y() - y) / seconds;
        velocityHeading = (reckoner.heading() - heading) / seconds;
    }

    private void store(int register, byte[] bytes, long nowNanos) {
        switch (register) {
            case DEVICE_CONTROL -> control(LittleEndian.intAt(bytes, 1), nowNanos);
            case X_POSITION -> reckoner.setX(LittleEndian.floatAt(bytes, 1));
            case Y_POSITION -> reckoner.setY(LittleEndian.floatAt(bytes, 1));
            case H_ORIENTATION -> {
                reckoner.setHeading(LittleEndian.floatAt(bytes, 1));
                gyro.setHeading(reckoner.heading());
            }
            case MM_PER_TICK -> ticksPerMm = LittleEndian.floatAt(bytes, 1);
            case X_POD_OFFSET -> xPodOffsetMm = LittleEndian.floatAt(bytes, 1);
            case Y_POD_OFFSET -> yPodOffsetMm = LittleEndian.floatAt(bytes, 1);
            case YAW_SCALAR -> {
                yawScalar = LittleEndian.floatAt(bytes, 1);
                gyro.setScalar(yawScalar);
            }
            default -> {
                // The other registers are read-only; writing them has no effect.
            }
        }
    }

    private void control(int bits, long nowNanos) {
        if ((bits & CONTROL_X_REVERSED) != 0) {
            xReversed = true;
        }
        if ((bits & CONTROL_X_FORWARD) != 0) {
            xReversed = false;
        }
        if ((bits & CONTROL_Y_REVERSED) != 0) {
            yReversed = true;
        }
        if ((bits & CONTROL_Y_FORWARD) != 0) {
            yReversed = false;
        }
        // A change of direction flips the sign of the count; it must not look like travel.
        lastXCounts = signedCount(xPod, xReversed);
        lastYCounts = signedCount(yPod, yReversed);
        if ((bits & CONTROL_RESET_POSITION_AND_IMU) != 0) {
            reckoner.setPose(0, 0, 0);
            gyro.setHeading(0);
            startCalibration(nowNanos);
        } else if ((bits & CONTROL_RECALIBRATE_IMU) != 0) {
            startCalibration(nowNanos);
        }
    }

    private void startCalibration(long nowNanos) {
        calibrationStartNanos = nowNanos;
        readyAtNanos = nowNanos + CALIBRATION_MILLIS * NANOS_PER_MILLI;
        running = false;
        velocityX = 0;
        velocityY = 0;
        velocityHeading = 0;
    }

    private void setBulkScope(byte[] bytes) {
        bulkScope = new int[bytes.length - 1];
        for (int i = 0; i < bulkScope.length; i++) {
            bulkScope[i] = bytes[i + 1] & 0xFF;
        }
    }

    private int status(long nowNanos) {
        int faults = (xPod == null ? STATUS_X_POD_MISSING : 0) | (yPod == null ? STATUS_Y_POD_MISSING : 0);
        if (nowNanos < calibrationStartNanos) {
            return faults;
        }
        if (nowNanos < readyAtNanos) {
            return STATUS_CALIBRATING | faults;
        }
        return faults == 0 ? STATUS_READY : faults;
    }

    private byte[] bulk(long nowNanos) {
        byte[] bytes = new byte[bulkScope.length * 4];
        for (int i = 0; i < bulkScope.length; i++) {
            System.arraycopy(word(bulkScope[i], nowNanos), 0, bytes, i * 4, 4);
        }
        return bytes;
    }

    private byte[] word(int register, long nowNanos) {
        return switch (register) {
            case DEVICE_ID -> LittleEndian.bytes(DEVICE_ID_VALUE);
            case DEVICE_VERSION -> LittleEndian.bytes(FIRMWARE_VERSION);
            case DEVICE_STATUS -> LittleEndian.bytes(status(nowNanos));
            case LOOP_TIME -> LittleEndian.bytes(LOOP_TIME_MICROS);
            case X_ENCODER_VALUE -> LittleEndian.bytes((int) lastXCounts);
            case Y_ENCODER_VALUE -> LittleEndian.bytes((int) lastYCounts);
            case X_POSITION -> LittleEndian.bytes((float) reckoner.x());
            case Y_POSITION -> LittleEndian.bytes((float) reckoner.y());
            case H_ORIENTATION -> LittleEndian.bytes((float) reckoner.heading());
            case X_VELOCITY -> LittleEndian.bytes((float) velocityX);
            case Y_VELOCITY -> LittleEndian.bytes((float) velocityY);
            case H_VELOCITY -> LittleEndian.bytes((float) velocityHeading);
            case MM_PER_TICK -> LittleEndian.bytes((float) ticksPerMm);
            case X_POD_OFFSET -> LittleEndian.bytes((float) xPodOffsetMm);
            case Y_POD_OFFSET -> LittleEndian.bytes((float) yPodOffsetMm);
            case YAW_SCALAR -> LittleEndian.bytes(yawScalar);
            case QUATERNION_W -> LittleEndian.bytes((float) orientation()[0]);
            case QUATERNION_X -> LittleEndian.bytes((float) orientation()[1]);
            case QUATERNION_Y -> LittleEndian.bytes((float) orientation()[2]);
            case QUATERNION_Z -> LittleEndian.bytes((float) orientation()[3]);
            case PITCH -> LittleEndian.bytes((float) latestPose.pitch());
            case ROLL -> LittleEndian.bytes((float) latestPose.roll());
            default -> new byte[4];
        };
    }

    /** The chassis orientation as the inertial sensor knows it: the true tilt, with yaw counted from its zero. */
    private double[] orientation() {
        double cos = StrictMath.cos(-imuYawZero / 2);
        double sin = StrictMath.sin(-imuYawZero / 2);
        BodyPose pose = latestPose;
        return new double[] {
            cos * pose.qw() - sin * pose.qz(),
            cos * pose.qx() - sin * pose.qy(),
            cos * pose.qy() + sin * pose.qx(),
            cos * pose.qz() + sin * pose.qw()
        };
    }

    private static long signedCount(EncoderSource pod, boolean reversed) {
        long counts = pod == null ? 0 : pod.encoderCounts();
        return reversed ? -counts : counts;
    }

    /** The chip's CRC-8: polynomial 0x31, initial value 0x90, no reflection, no final XOR. */
    static byte crc8(byte[] data) {
        int crc = 0x90;
        for (byte value : data) {
            crc ^= value & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x31 : crc << 1;
                crc &= 0xFF;
            }
        }
        return (byte) crc;
    }
}
