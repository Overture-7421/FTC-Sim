package dev.ftcsim.hardware.device.gobilda;

import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.LittleEndian;
import java.util.Map;

/**
 * A Pinpoint on a scripted chassis, read and written the way the SDK's driver does it: a register number written,
 * then bytes read; or a register number and four bytes written.
 */
final class PinpointRig {

    // The chip's register numbers, from the goBILDA user guide and the SDK driver.
    static final int DEVICE_ID = 1;
    static final int DEVICE_VERSION = 2;
    static final int DEVICE_STATUS = 3;
    static final int DEVICE_CONTROL = 4;
    static final int LOOP_TIME = 5;
    static final int X_ENCODER_VALUE = 6;
    static final int Y_ENCODER_VALUE = 7;
    static final int X_POSITION = 8;
    static final int Y_POSITION = 9;
    static final int H_ORIENTATION = 10;
    static final int X_VELOCITY = 11;
    static final int Y_VELOCITY = 12;
    static final int H_VELOCITY = 13;
    static final int MM_PER_TICK = 14;
    static final int X_POD_OFFSET = 15;
    static final int Y_POD_OFFSET = 16;
    static final int YAW_SCALAR = 17;
    static final int BULK_READ = 18;
    static final int QUATERNION_W = 19;
    static final int QUATERNION_X = 20;
    static final int QUATERNION_Y = 21;
    static final int QUATERNION_Z = 22;
    static final int PITCH = 23;
    static final int ROLL = 24;
    static final int SET_BULK_READ = 25;

    static final int RECALIBRATE_IMU = 1;
    static final int RESET_POSITION_AND_IMU = 1 << 1;
    static final int Y_REVERSED = 1 << 2;
    static final int X_REVERSED = 1 << 4;

    /** The goBILDA 4-bar pod: a 2000 count encoder on a 32 mm wheel. */
    static final double FOUR_BAR_TICKS_PER_MM = 19.89436789;

    final WorldRig world;
    final I2cPeripheral chip;

    PinpointRig(ScriptedBody body) {
        world = new WorldRig(body, (origin, direction, maxMeters) -> null);
        chip = DeviceModels.standard().create(DeviceModels.PINPOINT, "odo", Map.of(), world.host);
    }

    /** What robot code does to configure the chip: its pod offsets, resolution and a reset of the pose and IMU. */
    void configure(double xOffsetMm, double yOffsetMm, double ticksPerMm) {
        writeFloat(X_POD_OFFSET, (float) xOffsetMm);
        writeFloat(Y_POD_OFFSET, (float) yOffsetMm);
        writeFloat(MM_PER_TICK, (float) ticksPerMm);
        writeInt(DEVICE_CONTROL, RESET_POSITION_AND_IMU);
    }

    byte[] read(int register, int count) {
        return chip.writeRead(new byte[] {(byte) register}, count, world.now());
    }

    int readInt(int register) {
        return LittleEndian.intAt(read(register, 5), 0);
    }

    float readFloat(int register) {
        return LittleEndian.floatAt(read(register, 5), 0);
    }

    void writeInt(int register, int value) {
        write(register, LittleEndian.bytes(value));
    }

    void writeFloat(int register, float value) {
        write(register, LittleEndian.bytes(value));
    }

    void write(int register, byte[] value) {
        byte[] bytes = new byte[value.length + 1];
        bytes[0] = (byte) register;
        System.arraycopy(value, 0, bytes, 1, value.length);
        chip.write(bytes, world.now());
    }

    double x() {
        return readFloat(X_POSITION);
    }

    double y() {
        return readFloat(Y_POSITION);
    }

    double heading() {
        return readFloat(H_ORIENTATION);
    }
}
