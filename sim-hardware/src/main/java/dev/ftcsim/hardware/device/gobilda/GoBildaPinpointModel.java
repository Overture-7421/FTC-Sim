package dev.ftcsim.hardware.device.gobilda;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.LittleEndian;

/**
 * The goBILDA Pinpoint Odometry Computer (3110-0002-0001), as the SDK's {@code GoBildaPinpointDriver} talks to it,
 * and ideal: it reports the chassis's true pose, with no pods to measure it by and no gyro to drift.
 *
 * <p>What is the code's is the frame the pose is reported in. Where the chassis stands when the chip becomes ready is
 * the origin, heading zero; a write to the X, Y or heading position register moves the reported pose to the value
 * written, from the true pose of that moment, and the chip goes on reporting the chassis's true motion turned into
 * that frame; the reset control bit puts the pose back to the origin and the chip calibrates again. Everything the
 * code programs about pods (offsets, resolution, encoder directions) and the yaw scalar is accepted and has no effect,
 * so whatever the code says about its pods is right. The encoder registers count the chassis's true travel forward
 * and to the left at the resolution the code wrote. The velocities are the true ones in the reported frame.
 *
 * <p>Timeline, estimated: after power-up the chip is not ready for {@value #BOOT_MILLIS} ms and then calibrates for
 * {@value #CALIBRATION_MILLIS} ms, as it does again after a reset or recalibration. While it is not ready or calibrating
 * the pose and velocities are held. The chip updates once per physics step.
 *
 * <p>It takes no settings.
 */
public final class GoBildaPinpointModel implements I2cPeripheral {

    public static final int ADDRESS = 0x31;

    static final long BOOT_MILLIS = 100;
    static final long CALIBRATION_MILLIS = 250;

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double MM_PER_METRE = 1000.0;

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

    private static final int CONTROL_RECALIBRATE_IMU = 1;
    private static final int CONTROL_RESET_POSITION_AND_IMU = 1 << 1;

    /** What a bulk read returns until robot code chooses otherwise. */
    private static final int[] DEFAULT_BULK_SCOPE = {
        DEVICE_STATUS, LOOP_TIME, X_ENCODER_VALUE, Y_ENCODER_VALUE, X_POSITION, Y_POSITION, H_ORIENTATION,
        X_VELOCITY, Y_VELOCITY, H_VELOCITY
    };

    private final BodyView body;

    private int selectedRegister;
    private int[] bulkScope = DEFAULT_BULK_SCOPE;
    private float ticksPerMm = DEFAULT_TICKS_PER_MM;
    private float xPodOffsetMm;
    private float yPodOffsetMm;
    private float yawScalar = 1.0f;

    private long calibrationStartNanos;
    private long readyAtNanos;
    private boolean running;
    private BodyPose latestPose;
    private double lastYaw;
    private double unwrappedYaw;
    private double lastX;
    private double lastY;
    /** What the chip reports, in the frame the code last set; the heading counts on past a half turn. */
    private double reportedXMm;
    private double reportedYMm;
    private double reportedHeading;
    /** The reported heading minus the chassis's yaw counted past a half turn: the turn from the field to the reported frame. */
    private double frameTurn;
    private double velocityXMm;
    private double velocityYMm;
    private double velocityHeading;
    private double forwardTravelMm;
    private double leftTravelMm;
    private double imuYawZero;

    private GoBildaPinpointModel(DeviceContext context) {
        this.body = context.body();
        long now = context.nowNanos();
        latestPose = body.sample();
        lastYaw = latestPose.yaw();
        unwrappedYaw = lastYaw;
        imuYawZero = lastYaw;
        lastX = latestPose.x();
        lastY = latestPose.y();
        calibrationStartNanos = now + BOOT_MILLIS * NANOS_PER_MILLI;
        readyAtNanos = calibrationStartNanos + CALIBRATION_MILLIS * NANOS_PER_MILLI;
    }

    /** The registry's factory. */
    public static I2cPeripheral create(DeviceConfig config, DeviceContext context) {
        GoBildaPinpointModel model = new GoBildaPinpointModel(context);
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
        double yawChange = StrictMath.IEEEremainder(latestPose.yaw() - lastYaw, TWO_PI);
        double deltaX = latestPose.x() - lastX;
        double deltaY = latestPose.y() - lastY;
        double meanYaw = lastYaw + yawChange / 2.0;
        double cos = StrictMath.cos(meanYaw);
        double sin = StrictMath.sin(meanYaw);
        forwardTravelMm += (deltaX * cos + deltaY * sin) * MM_PER_METRE;
        leftTravelMm += (-deltaX * sin + deltaY * cos) * MM_PER_METRE;
        unwrappedYaw += yawChange;
        lastYaw = latestPose.yaw();
        lastX = latestPose.x();
        lastY = latestPose.y();
        if (stepNanos < readyAtNanos) {
            running = false;
            velocityXMm = 0;
            velocityYMm = 0;
            velocityHeading = 0;
            return;
        }
        if (!running) {
            // The chassis stands at the origin of the reported frame when calibration ends.
            running = true;
            imuYawZero = latestPose.yaw();
            frameTurn = reportedHeading - unwrappedYaw;
            return;
        }
        double cosFrame = StrictMath.cos(frameTurn);
        double sinFrame = StrictMath.sin(frameTurn);
        reportedXMm += (cosFrame * deltaX - sinFrame * deltaY) * MM_PER_METRE;
        reportedYMm += (sinFrame * deltaX + cosFrame * deltaY) * MM_PER_METRE;
        reportedHeading = unwrappedYaw + frameTurn;
        velocityXMm = (cosFrame * latestPose.vx() - sinFrame * latestPose.vy()) * MM_PER_METRE;
        velocityYMm = (sinFrame * latestPose.vx() + cosFrame * latestPose.vy()) * MM_PER_METRE;
        velocityHeading = latestPose.wz();
    }

    private void store(int register, byte[] bytes, long nowNanos) {
        switch (register) {
            case DEVICE_CONTROL -> control(LittleEndian.intAt(bytes, 1), nowNanos);
            case X_POSITION -> reportedXMm = LittleEndian.floatAt(bytes, 1);
            case Y_POSITION -> reportedYMm = LittleEndian.floatAt(bytes, 1);
            case H_ORIENTATION -> {
                reportedHeading = LittleEndian.floatAt(bytes, 1);
                frameTurn = reportedHeading - unwrappedYaw;
            }
            case MM_PER_TICK -> ticksPerMm = LittleEndian.floatAt(bytes, 1);
            case X_POD_OFFSET -> xPodOffsetMm = LittleEndian.floatAt(bytes, 1);
            case Y_POD_OFFSET -> yPodOffsetMm = LittleEndian.floatAt(bytes, 1);
            case YAW_SCALAR -> yawScalar = LittleEndian.floatAt(bytes, 1);
            default -> {
                // The other registers are read-only; writing them has no effect.
            }
        }
    }

    /** Only the resets matter: the encoder directions the code sets say how its pods are mounted, and the chip's are ideal. */
    private void control(int bits, long nowNanos) {
        if ((bits & CONTROL_RESET_POSITION_AND_IMU) != 0) {
            reportedXMm = 0;
            reportedYMm = 0;
            reportedHeading = 0;
            frameTurn = -unwrappedYaw;
            startCalibration(nowNanos);
        } else if ((bits & CONTROL_RECALIBRATE_IMU) != 0) {
            startCalibration(nowNanos);
        }
    }

    private void startCalibration(long nowNanos) {
        calibrationStartNanos = nowNanos;
        readyAtNanos = nowNanos + CALIBRATION_MILLIS * NANOS_PER_MILLI;
        running = false;
        velocityXMm = 0;
        velocityYMm = 0;
        velocityHeading = 0;
    }

    private void setBulkScope(byte[] bytes) {
        bulkScope = new int[bytes.length - 1];
        for (int i = 0; i < bulkScope.length; i++) {
            bulkScope[i] = bytes[i + 1] & 0xFF;
        }
    }

    private int status(long nowNanos) {
        if (nowNanos < calibrationStartNanos) {
            return 0;
        }
        return nowNanos < readyAtNanos ? STATUS_CALIBRATING : STATUS_READY;
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
            case X_ENCODER_VALUE -> LittleEndian.bytes((int) Math.round(forwardTravelMm * ticksPerMm));
            case Y_ENCODER_VALUE -> LittleEndian.bytes((int) Math.round(leftTravelMm * ticksPerMm));
            case X_POSITION -> LittleEndian.bytes((float) reportedXMm);
            case Y_POSITION -> LittleEndian.bytes((float) reportedYMm);
            case H_ORIENTATION -> LittleEndian.bytes((float) reportedHeading);
            case X_VELOCITY -> LittleEndian.bytes((float) velocityXMm);
            case Y_VELOCITY -> LittleEndian.bytes((float) velocityYMm);
            case H_VELOCITY -> LittleEndian.bytes((float) velocityHeading);
            case MM_PER_TICK -> LittleEndian.bytes(ticksPerMm);
            case X_POD_OFFSET -> LittleEndian.bytes(xPodOffsetMm);
            case Y_POD_OFFSET -> LittleEndian.bytes(yPodOffsetMm);
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
