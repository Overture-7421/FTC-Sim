package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

import java.util.function.LongSupplier;

/**
 * The {@code GetBulkInputData} command: digital inputs, the four encoders, the motor status bits, the four
 * velocities and the four analog inputs, in one response.
 */
final class BulkInputCommand {

    private static final int AT_TARGET_FIRST_BIT = 4;

    private final DigitalBank digital;
    private final MotorBank motors;
    private final AdcBank adc;
    private final LongSupplier clock;

    BulkInputCommand(DigitalBank digital, MotorBank motors, AdcBank adc, LongSupplier clock) {
        this.digital = digital;
        this.motors = motors;
        this.adc = adc;
        this.clock = clock;
    }

    void register(CommandTable table) {
        table.register(DekaCommand.GET_BULK_INPUT_DATA, QUERY, params -> {
            params.end();
            return Reply.response(read(clock.getAsLong()));
        });
    }

    private PayloadWriter read(long now) {
        PayloadWriter payload = new PayloadWriter().u8(digital.levels(now));
        int status = 0;
        for (int motor = 0; motor < MotorBank.COUNT; motor++) {
            payload.i32((int) motors.channel(motor).position());
            status |= (motors.channel(motor).overCurrent() ? 1 : 0) << motor;
            status |= (motors.channel(motor).atTarget() ? 1 : 0) << (AT_TARGET_FIRST_BIT + motor);
        }
        payload.u8(status);
        for (int motor = 0; motor < MotorBank.COUNT; motor++) {
            payload.u16(saturatedVelocity(motors.channel(motor).velocity()));
        }
        for (int input = 0; input < AdcBank.USER_INPUT_COUNT; input++) {
            payload.u16(adc.userMillivolts(input, now));
        }
        return payload;
    }

    private static int saturatedVelocity(double countsPerSecond) {
        return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(countsPerSecond)));
    }
}
