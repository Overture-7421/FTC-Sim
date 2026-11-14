package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.MUTATING;
import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

import java.util.function.LongSupplier;

/** The digital I/O commands of the DEKA interface, mapped onto {@link DigitalBank}. */
final class DigitalCommands {

    private final DigitalBank digital;
    private final LongSupplier clock;

    DigitalCommands(DigitalBank digital, LongSupplier clock) {
        this.digital = digital;
        this.clock = clock;
    }

    void register(CommandTable table) {
        table.register(DekaCommand.SET_SINGLE_DIO_OUTPUT, MUTATING, params -> {
            int pin = pin(params);
            boolean high = params.flag();
            params.end();
            digital.setOutput(pin, high);
            return Reply.ACK;
        });
        table.register(DekaCommand.SET_ALL_DIO_OUTPUTS, MUTATING, params -> {
            int bits = params.u8();
            params.end();
            digital.setOutputs(bits);
            return Reply.ACK;
        });
        table.register(DekaCommand.SET_DIO_DIRECTION, MUTATING, params -> {
            int pin = pin(params);
            boolean toOutput = params.flag();
            params.end();
            digital.setDirection(pin, toOutput);
            return Reply.ACK;
        });
        table.register(DekaCommand.GET_DIO_DIRECTION, QUERY, params -> {
            int pin = pin(params);
            params.end();
            return Reply.response(new PayloadWriter().flag(digital.isOutput(pin)));
        });
        table.register(DekaCommand.GET_SINGLE_DIO_INPUT, QUERY, params -> {
            int pin = pin(params);
            params.end();
            return Reply.response(new PayloadWriter().flag(digital.readInput(pin, clock.getAsLong())));
        });
        table.register(DekaCommand.GET_ALL_DIO_INPUTS, QUERY, params -> {
            params.end();
            return Reply.response(new PayloadWriter().u8(digital.readInputs(clock.getAsLong())));
        });
    }

    private static int pin(Params params) {
        return params.u8(0, DigitalBank.COUNT - 1);
    }
}
