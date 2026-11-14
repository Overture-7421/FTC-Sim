package dev.ftcsim.hardware.device.digitalchickenlabs;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.LittleEndian;
import java.util.HashMap;
import java.util.Map;

/**
 * An OctoQuad read and written the way the SDK's driver does it, from the register map of the data sheet: a command
 * and its operands in one write, results read back from the command data registers.
 */
final class OctoQuadRig {

    static final int CHIP_ID = 0x00;
    static final int FIRMWARE_MAJOR = 0x01;
    static final int COMMAND = 0x04;
    static final int COMMAND_DATA_0 = 0x05;
    static final int YAW_AXIS = 0x0C;
    static final int LOCALIZER_STATUS = 0x0D;
    static final int LOCALIZER_VX = 0x0E;
    static final int LOCALIZER_VY = 0x10;
    static final int LOCALIZER_VH = 0x12;
    static final int LOCALIZER_X = 0x14;
    static final int LOCALIZER_Y = 0x16;
    static final int LOCALIZER_H = 0x18;
    static final int LOCALIZER_CRC = 0x1A;
    static final int ENCODER_POSITION_0 = 0x1C;
    static final int ENCODER_VELOCITY_0 = 0x3C;
    static final int ENCODER_CRC = 0x4C;

    static final int SET_PARAMETER = 1;
    static final int READ_PARAMETER = 2;
    static final int RESET_EVERYTHING = 20;
    static final int RESET_ENCODERS = 21;
    static final int RESET_LOCALIZER = 40;

    static final int DIRECTIONS = 0;
    static final int BANK_CONFIG = 2;
    static final int VELOCITY_INTERVAL = 3;
    static final int PULSE_WIDTH_RANGE = 4;
    static final int PULSE_WIDTH_TRACKS_WRAP = 5;
    static final int X_TICKS_PER_MM = 50;
    static final int Y_TICKS_PER_MM = 51;
    static final int TCP_OFFSET_X = 52;
    static final int TCP_OFFSET_Y = 53;
    static final int IMU_SCALAR = 54;
    static final int PORT_X = 55;
    static final int PORT_Y = 56;
    static final int LOCALIZER_VELOCITY_INTERVAL = 57;
    static final int UPTIME_MS = 200;
    static final int IMU_DIAGNOSTIC = 201;

    static final double TICKS_PER_MM = 19.89436789;

    /** Counts that follow the world's clock at a constant rate. */
    static final class RampSource implements EncoderSource {
        private final WorldRig world;
        private final double countsPerSecond;

        RampSource(WorldRig world, double countsPerSecond) {
            this.world = world;
            this.countsPerSecond = countsPerSecond;
        }

        @Override
        public long encoderCounts() {
            return Math.round(countsPerSecond * world.now() / 1e9);
        }

        @Override
        public double encoderCountsPerSecond() {
            return countsPerSecond;
        }
    }

    /** Counts a test sets by hand. */
    static final class ManualSource implements EncoderSource {
        long counts;

        @Override
        public long encoderCounts() {
            return counts;
        }

        @Override
        public double encoderCountsPerSecond() {
            return 0;
        }
    }

    final WorldRig world;
    final I2cPeripheral chip;

    OctoQuadRig(WorldRig world, Map<String, Object> settings) {
        this.world = world;
        this.chip = DeviceModels.standard().create("octoquad-ftc-mk2", "octoquad", settings, world.host);
    }

    /** Two pods wired to channels 0 (forward) and 1 (sideways), mounted as goBILDA 4-bar pods are. */
    static Map<String, Object> pods() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("channel0.pod.x", 0.02);
        settings.put("channel0.pod.y", -0.1135);
        settings.put("channel0.pod.ticksPerMetre", TICKS_PER_MM * 1000);
        settings.put("channel1.pod.x", -0.004);
        settings.put("channel1.pod.y", 0.03);
        settings.put("channel1.pod.axisDegrees", 90);
        settings.put("channel1.pod.ticksPerMetre", TICKS_PER_MM * 1000);
        return settings;
    }

    byte[] read(int register, int count) {
        return chip.writeRead(new byte[] {(byte) register}, count, world.now());
    }

    int readInt(int register) {
        return LittleEndian.intAt(read(register, 4), 0);
    }

    /** A signed 16-bit register. */
    int readShort(int register) {
        return (short) LittleEndian.unsignedShortAt(read(register, 2), 0);
    }

    void write(int register, int... bytes) {
        byte[] data = new byte[bytes.length + 1];
        data[0] = (byte) register;
        for (int i = 0; i < bytes.length; i++) {
            data[i + 1] = (byte) bytes[i];
        }
        chip.write(data, world.now());
    }

    void writeBytes(int register, byte[] bytes) {
        byte[] data = new byte[bytes.length + 1];
        data[0] = (byte) register;
        System.arraycopy(bytes, 0, data, 1, bytes.length);
        chip.write(data, world.now());
    }

    /** A command with its operands, in one write to the command register. */
    void command(int command, int... operands) {
        int[] bytes = new int[operands.length + 1];
        bytes[0] = command;
        System.arraycopy(operands, 0, bytes, 1, operands.length);
        write(COMMAND, bytes);
    }

    void setParameter(int parameter, int... values) {
        int[] operands = new int[values.length + 1];
        operands[0] = parameter;
        System.arraycopy(values, 0, operands, 1, values.length);
        command(SET_PARAMETER, operands);
    }

    void setFloatParameter(int parameter, float value) {
        byte[] bytes = LittleEndian.bytes(value);
        setParameter(parameter, bytes[0], bytes[1], bytes[2], bytes[3]);
    }

    /** Asks for a parameter and reads {@code length} bytes of the answer. */
    byte[] readParameter(int parameter, int argument, int length) {
        command(READ_PARAMETER, parameter, argument);
        return read(COMMAND_DATA_0, length);
    }

    /** What {@code setSingleEncoderDirection} does: read the bit field, change one bit, write it back. */
    void setDirection(int channel, boolean reversed) {
        int bits = readParameter(DIRECTIONS, 0, 1)[0] & 0xFF;
        bits = reversed ? bits | 1 << channel : bits & ~(1 << channel);
        setParameter(DIRECTIONS, bits);
    }

    /** What the driver's {@code setAllLocalizerParameters} and {@code resetLocalizerAndCalibrateIMU} do. */
    void startLocalizer(int portX, int portY, double ticksPerMmX, double ticksPerMmY, double tcpX, double tcpY) {
        setParameter(PORT_X, portX);
        setParameter(PORT_Y, portY);
        setFloatParameter(X_TICKS_PER_MM, (float) ticksPerMmX);
        setFloatParameter(Y_TICKS_PER_MM, (float) ticksPerMmY);
        setFloatParameter(TCP_OFFSET_X, (float) tcpX);
        setFloatParameter(TCP_OFFSET_Y, (float) tcpY);
        setFloatParameter(IMU_SCALAR, 1.0f);
        setParameter(LOCALIZER_VELOCITY_INTERVAL, 25);
        command(RESET_LOCALIZER);
    }
}
