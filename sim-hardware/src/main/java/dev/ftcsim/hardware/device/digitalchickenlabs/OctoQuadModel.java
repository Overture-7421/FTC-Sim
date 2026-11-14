package dev.ftcsim.hardware.device.digitalchickenlabs;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import dev.ftcsim.hardware.device.odometry.OdometryPod;
import dev.ftcsim.hardware.device.odometry.PodMount;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.LittleEndian;
import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;

/**
 * The OctoQuad FTC Edition MK2 (Digital Chicken Labs), as the SDK's {@code OctoQuad} driver talks to it: eight
 * channels that count quadrature encoders or measure the pulse width of absolute encoders, and the absolute
 * localizer, which dead-reckons a pose from two of them and the onboard IMU. The register map, commands and
 * parameters are those of the firmware 3 specification, including the CRC-16 that guards both bulk blocks; with
 * firmware 3.1 or later the uptime and IMU diagnostic parameters of SDK 12 are answered too.
 *
 * <p>Positions and the pulse widths are read from the sources when robot code reads them. Velocities and the
 * localizer are updated once per physics step, so a velocity interval shorter than a step is measured over a step.
 *
 * <p>Profile settings: {@code firmware} (text, default {@code 3.1.0}), {@code gyroBiasRadPerSec} (default 0) and,
 * for each channel {@code channel0} to {@code channel7}, what is wired to it: either an odometry pod, as the
 * settings {@code channelN.pod.*} (see {@link PodMount#read}), or the encoder of a mechanism as
 * {@code channelN.encoder}, the name the robot profile gives it. {@code channelN.countsPerRev} says how many of
 * the source's counts are one turn of an absolute encoder, for a channel that robot code uses in pulse-width mode.
 */
public final class OctoQuadModel extends RegisterFilePeripheral {

    public static final int ADDRESS = 0x30;

    private static final int CHANNEL_COUNT = 8;
    private static final int CHIP_ID_VALUE = 0x51;

    private static final int CHIP_ID = 0x00;
    private static final int FIRMWARE_MAJOR = 0x01;
    private static final int COMMAND = 0x04;
    private static final int COMMAND_DATA_0 = 0x05;
    private static final int COMMAND_DATA_COUNT = 7;
    private static final int LOCALIZER_YAW_AXIS = 0x0C;
    private static final int LOCALIZER_STATUS = 0x0D;
    private static final int LOCALIZER_VELOCITY_X = 0x0E;
    private static final int LOCALIZER_VELOCITY_Y = 0x10;
    private static final int LOCALIZER_VELOCITY_HEADING = 0x12;
    private static final int LOCALIZER_X = 0x14;
    private static final int LOCALIZER_Y = 0x16;
    private static final int LOCALIZER_HEADING = 0x18;
    private static final int LOCALIZER_CRC = 0x1A;
    private static final int ENCODER_POSITION = 0x1C;
    private static final int ENCODER_VELOCITY = 0x3C;
    private static final int ENCODER_CRC = 0x4C;

    private static final int CMD_SET_PARAMETER = 1;
    private static final int CMD_READ_PARAMETER = 2;
    private static final int CMD_SAVE_TO_FLASH = 3;
    private static final int CMD_RESET_EVERYTHING = 20;
    private static final int CMD_RESET_ENCODERS = 21;
    private static final int CMD_RESET_LOCALIZER = 40;

    private static final int PARAM_ENCODER_DIRECTIONS = 0;
    private static final int PARAM_I2C_RECOVERY_MODE = 1;
    private static final int PARAM_CHANNEL_BANK_CONFIG = 2;
    private static final int PARAM_CHANNEL_VELOCITY_INTERVAL = 3;
    private static final int PARAM_CHANNEL_PULSE_WIDTH_RANGE = 4;
    private static final int PARAM_CHANNEL_PULSE_WIDTH_TRACKS_WRAP = 5;
    private static final int PARAM_LOCALIZER_X_TICKS_PER_MM = 50;
    private static final int PARAM_LOCALIZER_Y_TICKS_PER_MM = 51;
    private static final int PARAM_LOCALIZER_TCP_OFFSET_X_MM = 52;
    private static final int PARAM_LOCALIZER_TCP_OFFSET_Y_MM = 53;
    private static final int PARAM_LOCALIZER_IMU_HEADING_SCALAR = 54;
    private static final int PARAM_LOCALIZER_PORT_X = 55;
    private static final int PARAM_LOCALIZER_PORT_Y = 56;
    private static final int PARAM_LOCALIZER_VELOCITY_INTERVAL = 57;
    private static final int PARAM_UPTIME_MS = 200;
    private static final int PARAM_IMU_DIAGNOSTIC = 201;

    private static final int BANK_ALL_PULSE_WIDTH = 1;
    private static final int BANK_SECOND_PULSE_WIDTH = 2;
    private static final int FIRST_CHANNEL_OF_SECOND_BANK = 4;
    /** The first minor version of firmware 3 that reports its uptime and the IMU's diagnostic. */
    private static final int FIRST_DIAGNOSTIC_MINOR = 1;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final OctoQuadChannel[] channels;
    private final OctoQuadLocalizer localizer;
    private final long powerOnNanos;
    private final boolean diagnostics;
    private int bankConfig;
    private int i2cRecoveryMode;

    OctoQuadModel(DeviceContext context, OctoQuadChannel[] channels, int[] firmware, double gyroBias) {
        super(ADDRESS);
        this.channels = channels;
        this.powerOnNanos = context.nowNanos();
        this.localizer = new OctoQuadLocalizer(context.body(), channels, gyroBias, powerOnNanos);
        this.diagnostics = firmware[1] >= FIRST_DIAGNOSTIC_MINOR;
        poke(CHIP_ID, CHIP_ID_VALUE);
        for (int i = 0; i < firmware.length; i++) {
            poke(FIRMWARE_MAJOR + i, firmware[i]);
        }
        restartVelocitySampling(powerOnNanos);
    }

    /** The registry's factory. */
    public static I2cPeripheral create(DeviceConfig config, DeviceContext context) {
        int[] firmware = firmware(config);
        OctoQuadChannel[] channels = new OctoQuadChannel[CHANNEL_COUNT];
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            channels[i] = channel(config, context, "channel" + i);
        }
        OctoQuadModel model = new OctoQuadModel(context, channels, firmware, config.number("gyroBiasRadPerSec", 0));
        context.onStep(model::afterStep);
        return model;
    }

    private static int[] firmware(DeviceConfig config) {
        String text = config.text("firmware", "3.1.0");
        String[] parts = text.split("\\.");
        try {
            if (parts.length == 3) {
                int[] version = {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
                if (version[0] == 3 && version[1] >= 0 && version[1] <= 255 && version[2] >= 0 && version[2] <= 255) {
                    return version;
                }
            }
        } catch (NumberFormatException notNumbers) {
            // Reported below with the rest.
        }
        throw config.rejected("firmware", "must read 3.minor.engineering, like 3.1.0, not \"" + text + "\"");
    }

    private static OctoQuadChannel channel(DeviceConfig config, DeviceContext context, String prefix) {
        EncoderSource source = null;
        if (config.hasGroup(prefix + ".pod")) {
            source = OdometryPod.mount(PodMount.read(config, prefix + ".pod", 0), context);
        }
        if (config.has(prefix + ".encoder")) {
            if (source != null) {
                throw config.rejected(prefix + ".encoder", "cannot be given as well as " + prefix + ".pod");
            }
            String name = config.text(prefix + ".encoder");
            try {
                source = context.encoder(name);
            } catch (IllegalArgumentException unknownEncoder) {
                throw config.rejected(prefix + ".encoder", "is not an encoder of this robot: " + unknownEncoder.getMessage());
            }
        }
        int countsPerRevolution = config.integer(prefix + ".countsPerRev", 0);
        if (countsPerRevolution < 0) {
            throw config.rejected(prefix + ".countsPerRev", "must not be negative, not " + countsPerRevolution);
        }
        return new OctoQuadChannel(source, countsPerRevolution);
    }

    /** One update, after the physics step that ended at {@code stepNanos}. */
    void afterStep(long stepNanos) {
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            channels[i].sample(stepNanos, pulseWidth(i));
        }
        localizer.afterStep(stepNanos);
    }

    @Override
    protected void beforeRead(int firstRegister, int count, long nowNanos) {
        poke(LOCALIZER_YAW_AXIS, localizer.yawAxis(nowNanos));
        poke(LOCALIZER_STATUS, localizer.status(nowNanos));
        pokeShort(LOCALIZER_VELOCITY_X, localizer.velocityX());
        pokeShort(LOCALIZER_VELOCITY_Y, localizer.velocityY());
        pokeShort(LOCALIZER_VELOCITY_HEADING, localizer.velocityHeading());
        pokeShort(LOCALIZER_X, localizer.xMm());
        pokeShort(LOCALIZER_Y, localizer.yMm());
        pokeShort(LOCALIZER_HEADING, localizer.headingRaw());
        pokeShort(LOCALIZER_CRC, crc(LOCALIZER_STATUS, LOCALIZER_CRC - LOCALIZER_STATUS));
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            pokeBytes(ENCODER_POSITION + 4 * i, LittleEndian.bytes((int) channels[i].position(pulseWidth(i))));
            pokeShort(ENCODER_VELOCITY + 2 * i, channels[i].velocity());
        }
        pokeShort(ENCODER_CRC, crc(ENCODER_POSITION, ENCODER_CRC - ENCODER_POSITION));
    }

    @Override
    protected void writeRegister(int register, int value, long nowNanos) {
        boolean command = register >= COMMAND && register < COMMAND_DATA_0 + COMMAND_DATA_COUNT;
        boolean pose = register >= LOCALIZER_X && register < LOCALIZER_HEADING + 2;
        if (command || pose) {
            poke(register, value);
        }
    }

    @Override
    protected void afterWrite(int firstRegister, int count, long nowNanos) {
        int last = firstRegister + count - 1;
        if (firstRegister <= COMMAND && last >= COMMAND) {
            execute(peek(COMMAND), nowNanos);
        }
        if (firstRegister <= LOCALIZER_X && last > LOCALIZER_X) {
            localizer.setXMm(shortAt(LOCALIZER_X));
        }
        if (firstRegister <= LOCALIZER_Y && last > LOCALIZER_Y) {
            localizer.setYMm(shortAt(LOCALIZER_Y));
        }
        if (firstRegister <= LOCALIZER_HEADING && last > LOCALIZER_HEADING) {
            localizer.setHeadingRaw(shortAt(LOCALIZER_HEADING));
        }
    }

    private void execute(int command, long nowNanos) {
        switch (command) {
            case CMD_SET_PARAMETER -> setParameter(operands(), nowNanos);
            case CMD_READ_PARAMETER -> readParameter(operands(), nowNanos);
            case CMD_RESET_EVERYTHING -> resetEverything(nowNanos);
            case CMD_RESET_ENCODERS -> resetEncoders(peek(COMMAND_DATA_0));
            case CMD_RESET_LOCALIZER -> localizer.reset(nowNanos);
            case CMD_SAVE_TO_FLASH -> {
                // Nothing to keep: the simulation is one power cycle long.
            }
            default -> {
                // A command the firmware does not know does nothing.
            }
        }
    }

    private void setParameter(byte[] operands, long nowNanos) {
        int parameter = operands[0] & 0xFF;
        int argument = operands[1] & 0xFF;
        OctoQuadLocalizer.Settings settings = localizer.staged();
        switch (parameter) {
            case PARAM_ENCODER_DIRECTIONS -> setDirections(argument, nowNanos);
            case PARAM_I2C_RECOVERY_MODE -> i2cRecoveryMode = argument;
            case PARAM_CHANNEL_BANK_CONFIG -> {
                bankConfig = argument;
                restartVelocitySampling(nowNanos);
            }
            case PARAM_CHANNEL_VELOCITY_INTERVAL -> {
                int milliseconds = operands[2] & 0xFF;
                if (argument < CHANNEL_COUNT && milliseconds != 0) {
                    channels[argument].setVelocityInterval(milliseconds, nowNanos, pulseWidth(argument));
                }
            }
            case PARAM_CHANNEL_PULSE_WIDTH_RANGE -> {
                if (argument < CHANNEL_COUNT) {
                    channels[argument].setPulseRange(
                            LittleEndian.unsignedShortAt(operands, 2), LittleEndian.unsignedShortAt(operands, 4));
                }
            }
            case PARAM_CHANNEL_PULSE_WIDTH_TRACKS_WRAP -> {
                for (int i = 0; i < CHANNEL_COUNT; i++) {
                    channels[i].setWrapTracking((argument >> i & 1) != 0);
                }
            }
            case PARAM_LOCALIZER_X_TICKS_PER_MM -> settings.ticksPerMmX = LittleEndian.floatAt(operands, 1);
            case PARAM_LOCALIZER_Y_TICKS_PER_MM -> settings.ticksPerMmY = LittleEndian.floatAt(operands, 1);
            case PARAM_LOCALIZER_TCP_OFFSET_X_MM -> settings.tcpOffsetXMm = LittleEndian.floatAt(operands, 1);
            case PARAM_LOCALIZER_TCP_OFFSET_Y_MM -> settings.tcpOffsetYMm = LittleEndian.floatAt(operands, 1);
            case PARAM_LOCALIZER_IMU_HEADING_SCALAR -> settings.imuScalar = LittleEndian.floatAt(operands, 1);
            case PARAM_LOCALIZER_PORT_X -> {
                if (argument < CHANNEL_COUNT) {
                    settings.portX = argument;
                }
            }
            case PARAM_LOCALIZER_PORT_Y -> {
                if (argument < CHANNEL_COUNT) {
                    settings.portY = argument;
                }
            }
            case PARAM_LOCALIZER_VELOCITY_INTERVAL -> {
                if (argument != 0) {
                    settings.velocityIntervalMs = argument;
                }
            }
            default -> {
                // A parameter the firmware does not know is ignored.
            }
        }
    }

    private void readParameter(byte[] operands, long nowNanos) {
        int parameter = operands[0] & 0xFF;
        int argument = operands[1] & 0xFF;
        OctoQuadLocalizer.Settings settings = localizer.staged();
        byte[] value = switch (parameter) {
            case PARAM_ENCODER_DIRECTIONS -> new byte[] {(byte) directions()};
            case PARAM_I2C_RECOVERY_MODE -> new byte[] {(byte) i2cRecoveryMode};
            case PARAM_CHANNEL_BANK_CONFIG -> new byte[] {(byte) bankConfig};
            case PARAM_CHANNEL_VELOCITY_INTERVAL -> argument < CHANNEL_COUNT
                    ? new byte[] {(byte) channels[argument].velocityIntervalMs()}
                    : new byte[0];
            case PARAM_CHANNEL_PULSE_WIDTH_RANGE -> argument < CHANNEL_COUNT
                    ? concatenate(
                            LittleEndian.shortBytes(channels[argument].minPulseUs()),
                            LittleEndian.shortBytes(channels[argument].maxPulseUs()))
                    : new byte[0];
            case PARAM_CHANNEL_PULSE_WIDTH_TRACKS_WRAP -> new byte[] {(byte) wrapTracking()};
            case PARAM_LOCALIZER_X_TICKS_PER_MM -> LittleEndian.bytes((float) settings.ticksPerMmX);
            case PARAM_LOCALIZER_Y_TICKS_PER_MM -> LittleEndian.bytes((float) settings.ticksPerMmY);
            case PARAM_LOCALIZER_TCP_OFFSET_X_MM -> LittleEndian.bytes((float) settings.tcpOffsetXMm);
            case PARAM_LOCALIZER_TCP_OFFSET_Y_MM -> LittleEndian.bytes((float) settings.tcpOffsetYMm);
            case PARAM_LOCALIZER_IMU_HEADING_SCALAR -> LittleEndian.bytes((float) settings.imuScalar);
            case PARAM_LOCALIZER_PORT_X -> new byte[] {(byte) settings.portX};
            case PARAM_LOCALIZER_PORT_Y -> new byte[] {(byte) settings.portY};
            case PARAM_LOCALIZER_VELOCITY_INTERVAL -> new byte[] {(byte) settings.velocityIntervalMs};
            case PARAM_UPTIME_MS -> diagnostics ? LittleEndian.bytes(uptimeMs(nowNanos)) : new byte[0];
            case PARAM_IMU_DIAGNOSTIC -> diagnostics ? LittleEndian.bytes(0) : new byte[0];
            default -> new byte[0];
        };
        pokeBytes(COMMAND_DATA_0, value);
    }

    private void setDirections(int reversedBits, long nowNanos) {
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            boolean reversed = (reversedBits >> i & 1) != 0;
            if (reversed != channels[i].reversed()) {
                channels[i].setReversed(reversed, pulseWidth(i), nowNanos);
            }
        }
    }

    private void resetEncoders(int mask) {
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if ((mask >> i & 1) != 0) {
                channels[i].reset();
            }
        }
    }

    private void resetEverything(long nowNanos) {
        for (OctoQuadChannel channel : channels) {
            channel.restoreDefaults();
        }
        bankConfig = 0;
        i2cRecoveryMode = 0;
        localizer.restoreDefaults();
        restartVelocitySampling(nowNanos);
    }

    private void restartVelocitySampling(long nowNanos) {
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            channels[i].restartSampling(nowNanos, pulseWidth(i));
        }
    }

    private boolean pulseWidth(int channel) {
        return bankConfig == BANK_ALL_PULSE_WIDTH
                || bankConfig == BANK_SECOND_PULSE_WIDTH && channel >= FIRST_CHANNEL_OF_SECOND_BANK;
    }

    private int directions() {
        int bits = 0;
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            bits |= channels[i].reversed() ? 1 << i : 0;
        }
        return bits;
    }

    private int wrapTracking() {
        int bits = 0;
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            bits |= channels[i].wrapTracking() ? 1 << i : 0;
        }
        return bits;
    }

    private int uptimeMs(long nowNanos) {
        return (int) ((nowNanos - powerOnNanos) / NANOS_PER_MILLI);
    }

    private byte[] operands() {
        byte[] operands = new byte[COMMAND_DATA_COUNT];
        for (int i = 0; i < COMMAND_DATA_COUNT; i++) {
            operands[i] = (byte) peek(COMMAND_DATA_0 + i);
        }
        return operands;
    }

    private int crc(int first, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) peek(first + i);
        }
        return Crc16Profibus.of(bytes, 0, length);
    }

    private int shortAt(int register) {
        return (short) (peek(register) | peek(register + 1) << 8);
    }

    private void pokeShort(int register, int value) {
        pokeBytes(register, LittleEndian.shortBytes(value));
    }

    private void pokeBytes(int register, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            poke(register + i, bytes[i]);
        }
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] bytes = new byte[first.length + second.length];
        System.arraycopy(first, 0, bytes, 0, first.length);
        System.arraycopy(second, 0, bytes, first.length, second.length);
        return bytes;
    }
}
