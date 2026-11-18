package dev.ftcsim.hardware.device.digitalchickenlabs;

import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import dev.ftcsim.hardware.device.rev.ThroughBoreEncoder;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;
import java.util.Arrays;

/**
 * The Digital Chicken Labs OctoQuad FTC Edition (MK1 and MK2) as the SDK's {@code OctoQuadImpl} talks to it, with the
 * robot's turret encoders on its ports. The registers, commands and parameters are those the SDK driver uses
 * (firmware 3.x): chip id, firmware version, the command and its data bytes, the localizer block, and the eight positions
 * and velocities with their CRC16 (the PROFIBUS polynomial 0x1DCF, initial value and final xor 0xFFFF).
 *
 * <p><b>What is simulated.</b> The encoders on the turret. A channel that is wired reports, in the bank configuration the
 * code sets, either the incremental counts of the encoder's quadrature output or the width in microseconds of its
 * absolute pulse, with the direction, minimum and maximum pulse width, wrap tracking, velocity interval and resets the
 * code programs. With wrap tracking the position is the pulse plus the wraps times the programmed span, and a reset zeros
 * the wraps, so that the position is the raw pulse again. A channel that is not wired reads 0. The velocity of a channel is
 * the change of its position over its sample interval, as the data sheet describes it.
 *
 * <p><b>What is ideal.</b> The pulse is exact, to the whole microsecond a counter sees; the real encoders vary by about
 * 10 microseconds, so a team's minimum and maximum matter only to wrap tracking and velocity here. The device starts as
 * if its settings had been saved to flash: the bank configuration is absolute (pulse width) when any channel is wired. What
 * the code sets overrides that, {@code saveParametersToFlash} keeps it for {@code resetEverything}, and nothing survives
 * the session.
 *
 * <p><b>What is accepted and ignored.</b> The MK2's IMU and localizer: their registers and parameters take what is
 * written and report a localizer that is not initialised, with a valid CRC.
 *
 * <p><b>Settings</b>: {@code fw.major}, {@code fw.minor}, {@code fw.engineering} (default 3.1.0, the firmware the SDK 12
 * driver is built for; an SDK 11 driver, built for 3.0, warns of a newer minor revision as it does on the real device), and
 * for each wired port N in 0..7 {@code encoderN.turns} (turns of the encoder for one turn of the turret, above 0),
 * {@code encoderN.zeroOffsetDegrees}, {@code encoderN.mirrored} (0 or 1) and {@code encoderN.model} (a
 * {@link ThroughBoreEncoder} id, default the usual one).
 */
public final class OctoQuadModel extends RegisterFilePeripheral {

    /** The 7-bit address of the OctoQuad. */
    public static final int ADDRESS = 0x30;

    static final int CHIP_ID = 0x51;
    static final int CHANNELS = 8;

    private static final int REG_CHIP_ID = 0x00;
    private static final int REG_FIRMWARE_MAJOR = 0x01;
    private static final int REG_COMMAND = 0x04;
    private static final int REG_COMMAND_DATA = 0x05;
    private static final int REG_LOCALIZER_STATUS = 0x0D;
    private static final int REG_LOCALIZER_CRC = 0x1A;
    private static final int REG_POSITIONS = 0x1C;
    private static final int REG_VELOCITIES = 0x3C;
    private static final int REG_ENCODER_CRC = 0x4C;
    private static final int LOCALIZER_BYTES = 13;
    private static final int ENCODER_BYTES = 48;
    /** The localizer's status code for a localizer that has not been initialised. */
    private static final int LOCALIZER_NOT_INITIALIZED = 1;

    private static final int CMD_SET_PARAM = 1;
    private static final int CMD_READ_PARAM = 2;
    private static final int CMD_WRITE_PARAMS_TO_FLASH = 3;
    private static final int CMD_RESET_EVERYTHING = 20;
    private static final int CMD_RESET_ENCODERS = 21;

    private static final int PARAM_ENCODER_DIRECTIONS = 0;
    private static final int PARAM_I2C_RECOVERY_MODE = 1;
    private static final int PARAM_CHANNEL_BANK_CONFIG = 2;
    private static final int PARAM_CHANNEL_VEL_INTVL = 3;
    private static final int PARAM_CHANNEL_PULSE_WIDTH_MIN_MAX = 4;
    private static final int PARAM_CHANNEL_PULSE_WIDTH_TRACKS_WRAP = 5;
    private static final int PARAM_UPTIME_MS = 200;
    private static final int PARAM_IMU_DIAG = 201;

    private static final int ALL_QUADRATURE = 0;
    private static final int ALL_PULSE_WIDTH = 1;
    private static final int BANK1_QUADRATURE_BANK2_PULSE_WIDTH = 2;

    private static final int DEFAULT_VELOCITY_INTERVAL_MS = 50;
    private static final int DEFAULT_MIN_PULSE_US = 1;
    private static final int DEFAULT_MAX_PULSE_US = 1024;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double TWO_PI = 2.0 * Math.PI;

    /** What the code can set, which the flash keeps a copy of. */
    private static final class Params {
        int bankConfig;
        int directions;
        int recoveryMode;
        int wrapMask;
        final int[] velocityIntervalMs = new int[CHANNELS];
        final int[] minPulseUs = new int[CHANNELS];
        final int[] maxPulseUs = new int[CHANNELS];
        final byte[][] localizer = new byte[8][4];

        Params copy() {
            Params copy = new Params();
            copy.bankConfig = bankConfig;
            copy.directions = directions;
            copy.recoveryMode = recoveryMode;
            copy.wrapMask = wrapMask;
            System.arraycopy(velocityIntervalMs, 0, copy.velocityIntervalMs, 0, CHANNELS);
            System.arraycopy(minPulseUs, 0, copy.minPulseUs, 0, CHANNELS);
            System.arraycopy(maxPulseUs, 0, copy.maxPulseUs, 0, CHANNELS);
            for (int i = 0; i < localizer.length; i++) {
                copy.localizer[i] = localizer[i].clone();
            }
            return copy;
        }
    }

    /** An encoder on a port, as the design wired it. */
    private record Wired(ThroughBoreEncoder.Spec spec, double turnsPerTurretTurn, double zeroTurns, int sign) {}

    private final DeviceContext context;
    private final Wired[] wired = new Wired[CHANNELS];

    private Params params = new Params();
    private Params flash;

    private final long[] position = new long[CHANNELS];
    private final int[] velocity = new int[CHANNELS];
    private final int[] lastPulse = new int[CHANNELS];
    private final int[] wraps = new int[CHANNELS];
    private final long[] quadratureOffset = new long[CHANNELS];
    private final long[] lastVelocityAt = new long[CHANNELS];
    private final long[] lastVelocityPosition = new long[CHANNELS];
    private long nowNanos;

    private OctoQuadModel(DeviceContext context, DeviceConfig config) {
        super(ADDRESS);
        this.context = context;
        int firmwareMajor = config.integer("fw.major", 3);
        int firmwareMinor = config.integer("fw.minor", 1);
        int firmwareEngineering = config.integer("fw.engineering", 0);
        boolean any = false;
        for (int channel = 0; channel < CHANNELS; channel++) {
            String prefix = "encoder" + channel + ".";
            if (!config.has(prefix + "turns")) {
                for (String key : new String[] {"model", "zeroOffsetDegrees", "mirrored"}) {
                    config.has(prefix + key);
                }
                continue;
            }
            double turns = config.number(prefix + "turns");
            if (!(turns > 0)) {
                throw config.rejected(prefix + "turns", "must be above 0, not " + turns);
            }
            ThroughBoreEncoder.Spec spec = ThroughBoreEncoder.spec(config.text(prefix + "model", ThroughBoreEncoder.USUAL));
            wired[channel] = new Wired(
                    spec, turns, config.number(prefix + "zeroOffsetDegrees", 0.0) / 360.0,
                    config.integer(prefix + "mirrored", 0) != 0 ? -1 : 1);
            any = true;
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            params.velocityIntervalMs[channel] = DEFAULT_VELOCITY_INTERVAL_MS;
            params.minPulseUs[channel] = DEFAULT_MIN_PULSE_US;
            params.maxPulseUs[channel] = DEFAULT_MAX_PULSE_US;
        }
        params.bankConfig = any ? ALL_PULSE_WIDTH : ALL_QUADRATURE;
        flash = params.copy();
        poke(REG_CHIP_ID, CHIP_ID);
        poke(REG_FIRMWARE_MAJOR, firmwareMajor);
        poke(REG_FIRMWARE_MAJOR + 1, firmwareMinor);
        poke(REG_FIRMWARE_MAJOR + 2, firmwareEngineering);
        poke(REG_LOCALIZER_STATUS, LOCALIZER_NOT_INITIALIZED);
        sample(context.nowNanos());
        for (int channel = 0; channel < CHANNELS; channel++) {
            lastVelocityAt[channel] = context.nowNanos();
            lastVelocityPosition[channel] = position[channel];
        }
    }

    /** The registry's factory. */
    public static I2cPeripheral create(DeviceConfig config, DeviceContext context) {
        OctoQuadModel model = new OctoQuadModel(context, config);
        context.onStep(model::sample);
        return model;
    }

    // ---------------------------------------------------------------------------------------------
    // The encoders
    // ---------------------------------------------------------------------------------------------

    private boolean pulseWidth(int channel) {
        return switch (params.bankConfig) {
            case ALL_PULSE_WIDTH -> true;
            case BANK1_QUADRATURE_BANK2_PULSE_WIDTH -> channel >= CHANNELS / 2;
            default -> false;
        };
    }

    private boolean reversed(int channel) {
        return (params.directions & (1 << channel)) != 0;
    }

    private boolean tracksWrap(int channel) {
        return (params.wrapMask & (1 << channel)) != 0;
    }

    /** One look at the encoders, after the physics step that ended at {@code stepNanos}: what each reports and its velocity. */
    private void sample(long stepNanos) {
        nowNanos = stepNanos;
        double yaw = context.turret().yawRadians();
        for (int channel = 0; channel < CHANNELS; channel++) {
            Wired encoder = wired[channel];
            if (encoder == null) {
                position[channel] = 0;
                velocity[channel] = 0;
                continue;
            }
            double turns = encoder.sign() * encoder.turnsPerTurretTurn() * yaw / TWO_PI;
            if (pulseWidth(channel)) {
                double angle = encoder.zeroTurns() + turns;
                int pulse = encoder.spec().pulseMicros(angle - Math.floor(angle));
                if (reversed(channel)) {
                    pulse = params.minPulseUs[channel] + params.maxPulseUs[channel] - pulse;
                }
                position[channel] = pulse;
                if (tracksWrap(channel)) {
                    int span = params.maxPulseUs[channel] - params.minPulseUs[channel];
                    if (pulse - lastPulse[channel] > span / 2) {
                        wraps[channel]--;
                    } else if (lastPulse[channel] - pulse > span / 2) {
                        wraps[channel]++;
                    }
                    position[channel] = pulse + (long) wraps[channel] * span;
                }
                lastPulse[channel] = pulse;
            } else {
                long counts = Math.round(turns * encoder.spec().quadratureCountsPerTurn());
                position[channel] = (reversed(channel) ? -counts : counts) - quadratureOffset[channel];
            }
            long interval = params.velocityIntervalMs[channel] * NANOS_PER_MILLI;
            if (stepNanos - lastVelocityAt[channel] >= interval) {
                long change = position[channel] - lastVelocityPosition[channel];
                velocity[channel] = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, change));
                lastVelocityAt[channel] = stepNanos;
                lastVelocityPosition[channel] = position[channel];
            }
        }
    }

    private void resetEncoders(int mask) {
        for (int channel = 0; channel < CHANNELS; channel++) {
            if ((mask & (1 << channel)) == 0) {
                continue;
            }
            if (pulseWidth(channel)) {
                // A reset zeros the wraps and leaves the absolute position, which is the point of an absolute encoder.
                wraps[channel] = 0;
            } else if (wired[channel] != null) {
                quadratureOffset[channel] += position[channel];
            }
        }
        sample(nowNanos);
        for (int channel = 0; channel < CHANNELS; channel++) {
            lastVelocityPosition[channel] = position[channel];
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The register file
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void beforeRead(int firstRegister, int count, long now) {
        sample(now);
        for (int channel = 0; channel < CHANNELS; channel++) {
            store(REG_POSITIONS + 4 * channel, 4, (int) position[channel]);
            store(REG_VELOCITIES + 2 * channel, 2, velocity[channel]);
        }
        store(REG_ENCODER_CRC, 2, crc16(REG_POSITIONS, ENCODER_BYTES));
        store(REG_LOCALIZER_CRC, 2, crc16(REG_LOCALIZER_STATUS, LOCALIZER_BYTES));
    }

    private void store(int register, int bytes, int value) {
        for (int i = 0; i < bytes; i++) {
            poke(register + i, value >> (8 * i));
        }
    }

    private int crc16(int first, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= peek(first + i) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1DCF : crc << 1;
            }
            crc &= 0xFFFF;
        }
        return crc ^ 0xFFFF;
    }

    @Override
    protected void afterWrite(int firstRegister, int count, long now) {
        if (firstRegister > REG_COMMAND || firstRegister + count <= REG_COMMAND) {
            return;
        }
        nowNanos = now;
        int command = peek(REG_COMMAND);
        switch (command) {
            case CMD_SET_PARAM -> setParam();
            case CMD_READ_PARAM -> readParam(now);
            case CMD_WRITE_PARAMS_TO_FLASH -> flash = params.copy();
            case CMD_RESET_EVERYTHING -> {
                params = flash.copy();
                Arrays.fill(wraps, 0);
                Arrays.fill(quadratureOffset, 0L);
                sample(now);
                for (int channel = 0; channel < CHANNELS; channel++) {
                    lastVelocityPosition[channel] = position[channel];
                    lastVelocityAt[channel] = now;
                }
            }
            case CMD_RESET_ENCODERS -> resetEncoders(peek(REG_COMMAND_DATA));
            default -> {
                // The localizer's reset and anything this firmware does not know do nothing.
            }
        }
        poke(REG_COMMAND, 0);
    }

    private int data(int index) {
        return peek(REG_COMMAND_DATA + index);
    }

    private void setParam() {
        int param = data(0);
        switch (param) {
            case PARAM_ENCODER_DIRECTIONS -> params.directions = data(1);
            case PARAM_I2C_RECOVERY_MODE -> params.recoveryMode = data(1);
            case PARAM_CHANNEL_BANK_CONFIG -> {
                if (data(1) <= BANK1_QUADRATURE_BANK2_PULSE_WIDTH) {
                    params.bankConfig = data(1);
                }
            }
            case PARAM_CHANNEL_VEL_INTVL -> {
                int channel = data(1);
                if (channel < CHANNELS && data(2) >= 1) {
                    params.velocityIntervalMs[channel] = data(2);
                }
            }
            case PARAM_CHANNEL_PULSE_WIDTH_MIN_MAX -> {
                int channel = data(1);
                if (channel < CHANNELS) {
                    params.minPulseUs[channel] = data(2) | data(3) << 8;
                    params.maxPulseUs[channel] = data(4) | data(5) << 8;
                }
            }
            case PARAM_CHANNEL_PULSE_WIDTH_TRACKS_WRAP -> params.wrapMask = data(1);
            default -> {
                if (param >= 50 && param < 58) {
                    for (int i = 0; i < 4; i++) {
                        params.localizer[param - 50][i] = (byte) data(1 + i);
                    }
                }
            }
        }
    }

    /** The answer to a parameter read goes where the command's data was. */
    private void readParam(long now) {
        int param = data(0);
        int channel = data(1);
        int[] answer = new int[4];
        switch (param) {
            case PARAM_ENCODER_DIRECTIONS -> answer[0] = params.directions;
            case PARAM_I2C_RECOVERY_MODE -> answer[0] = params.recoveryMode;
            case PARAM_CHANNEL_BANK_CONFIG -> answer[0] = params.bankConfig;
            case PARAM_CHANNEL_VEL_INTVL -> answer[0] = channel < CHANNELS ? params.velocityIntervalMs[channel] : 0;
            case PARAM_CHANNEL_PULSE_WIDTH_MIN_MAX -> {
                if (channel < CHANNELS) {
                    answer[0] = params.minPulseUs[channel] & 0xFF;
                    answer[1] = params.minPulseUs[channel] >> 8 & 0xFF;
                    answer[2] = params.maxPulseUs[channel] & 0xFF;
                    answer[3] = params.maxPulseUs[channel] >> 8 & 0xFF;
                }
            }
            case PARAM_CHANNEL_PULSE_WIDTH_TRACKS_WRAP -> answer[0] = params.wrapMask;
            case PARAM_UPTIME_MS -> {
                long millis = now / NANOS_PER_MILLI;
                for (int i = 0; i < 4; i++) {
                    answer[i] = (int) (millis >> (8 * i)) & 0xFF;
                }
            }
            case PARAM_IMU_DIAG -> {
                // The IMU is not simulated and reports no fault.
            }
            default -> {
                if (param >= 50 && param < 58) {
                    for (int i = 0; i < 4; i++) {
                        answer[i] = params.localizer[param - 50][i] & 0xFF;
                    }
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            poke(REG_COMMAND_DATA + i, answer[i]);
        }
    }
}
