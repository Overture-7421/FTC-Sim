package dev.ftcsim.hardware.lynx;

import java.nio.ByteBuffer;

/** The calls the SDK's controllers make, spelled out over an {@link RhspClient}. */
final class HubCalls {

    /** The decoded response of {@code GetBulkInputData}. */
    record Bulk(int digitalInputs, int[] encoders, int motorStatus, int[] velocities, int[] analogMillivolts) {

        boolean overCurrent(int motor) {
            return (motorStatus & (1 << motor)) != 0;
        }

        boolean atTarget(int motor) {
            return (motorStatus & (1 << (motor + 4))) != 0;
        }
    }

    static final int OPEN_LOOP = 0;
    static final int VELOCITY = 1;
    static final int POSITION = 2;
    static final int LEGACY_PID = 0;
    static final int PIDF = 1;

    private final RhspClient client;

    HubCalls(RhspClient client) {
        this.client = client;
    }

    void setMode(int motor, int mode, boolean floatAtZero) {
        client.ack(DekaCommand.SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(motor).u8(mode).u8(floatAtZero ? 1 : 0).build());
    }

    void enable(int motor, boolean on) {
        client.ack(DekaCommand.SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(motor).u8(on ? 1 : 0).build());
    }

    void setPower(int motor, int power) {
        client.ack(DekaCommand.SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(motor).u16(power).build());
    }

    void setVelocity(int motor, int countsPerSecond) {
        client.ack(DekaCommand.SET_MOTOR_TARGET_VELOCITY, Bytes.of().u8(motor).u16(countsPerSecond).build());
    }

    void setTarget(int motor, int target, int tolerance) {
        client.ack(DekaCommand.SET_MOTOR_TARGET_POSITION, Bytes.of().u8(motor).i32(target).u16(tolerance).build());
    }

    void resetEncoder(int motor) {
        client.ack(DekaCommand.RESET_MOTOR_ENCODER, Bytes.of().u8(motor).build());
    }

    int position(int motor) {
        return client.response(DekaCommand.GET_MOTOR_ENCODER_POSITION, Bytes.of().u8(motor).build()).getInt();
    }

    /** Sets PIDF gains the way the SDK does: as the floating-point gain times 65536. */
    void setPidf(int motor, int mode, double p, double i, double d, double f, int algorithm) {
        client.ack(
                DekaCommand.SET_MOTOR_PIDF_COEFFICIENTS,
                Bytes.of()
                        .u8(motor)
                        .u8(mode)
                        .i32(fixed(p))
                        .i32(fixed(i))
                        .i32(fixed(d))
                        .i32(fixed(f))
                        .u8(algorithm)
                        .build());
    }

    Bulk bulk() {
        ByteBuffer data = client.response(DekaCommand.GET_BULK_INPUT_DATA, Bytes.none());
        int digital = data.get() & 0xFF;
        int[] encoders = new int[4];
        for (int i = 0; i < 4; i++) {
            encoders[i] = data.getInt();
        }
        int status = data.get() & 0xFF;
        int[] velocities = new int[4];
        for (int i = 0; i < 4; i++) {
            velocities[i] = data.getShort();
        }
        int[] analog = new int[4];
        for (int i = 0; i < 4; i++) {
            analog[i] = data.getShort();
        }
        return new Bulk(digital, encoders, status, velocities, analog);
    }

    /** One ADC channel in engineering units. */
    int adc(int channel) {
        return client.response(DekaCommand.GET_ADC, Bytes.of().u8(channel).u8(0).build()).getShort();
    }

    static int fixed(double gain) {
        return (int) Math.round(gain * 65536.0);
    }
}
