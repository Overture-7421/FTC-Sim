package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.MUTATING;
import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

import java.util.function.LongSupplier;

/** The I2C commands of the DEKA interface, mapped onto {@link I2cBus}. */
final class I2cCommands {

    private static final int MAX_BYTES = 100;

    private final I2cBus[] buses;
    private final Diagnostics diagnostics;
    private final LongSupplier clock;

    I2cCommands(I2cBus[] buses, Diagnostics diagnostics, LongSupplier clock) {
        this.buses = buses;
        this.diagnostics = diagnostics;
        this.clock = clock;
    }

    void register(CommandTable table) {
        table.register(DekaCommand.I2C_WRITE_SINGLE_BYTE, MUTATING, params -> {
            I2cBus bus = bus(params);
            int address = address(params);
            byte value = (byte) params.u8();
            params.end();
            bus.start(I2cOperation.write(address, new byte[] {value}), clock.getAsLong());
            return Reply.ACK;
        });
        table.register(DekaCommand.I2C_WRITE_MULTIPLE_BYTES, MUTATING, params -> {
            I2cBus bus = bus(params);
            int address = address(params);
            byte[] bytes = params.bytes(params.u8(1, MAX_BYTES));
            params.end();
            bus.start(I2cOperation.write(address, bytes), clock.getAsLong());
            return Reply.ACK;
        });
        table.register(DekaCommand.I2C_READ_SINGLE_BYTE, MUTATING, params -> {
            I2cBus bus = bus(params);
            int address = address(params);
            params.end();
            bus.start(I2cOperation.read(address, 1), clock.getAsLong());
            return Reply.ACK;
        });
        // The firmware does not take a count of one here; the SDK reads a single byte with the command above.
        table.register(DekaCommand.I2C_READ_MULTIPLE_BYTES, MUTATING, params -> {
            I2cBus bus = bus(params);
            int address = address(params);
            int count = params.u8(2, MAX_BYTES);
            params.end();
            bus.start(I2cOperation.read(address, count), clock.getAsLong());
            return Reply.ACK;
        });
        table.register(DekaCommand.I2C_WRITE_READ_MULTIPLE_BYTES, MUTATING, params -> {
            I2cBus bus = bus(params);
            int address = address(params);
            int count = params.u8(1, MAX_BYTES);
            int register = params.u8();
            params.end();
            bus.start(I2cOperation.writeRead(address, register, count), clock.getAsLong());
            return Reply.ACK;
        });
        // A status query hands the finished transaction's result over once, so a second asker would take it from the first.
        table.register(DekaCommand.I2C_READ_STATUS_QUERY, MUTATING, params -> {
            I2cBus bus = bus(params);
            params.end();
            return Reply.response(bus.readResult(clock.getAsLong()));
        });
        table.register(DekaCommand.I2C_WRITE_STATUS_QUERY, MUTATING, params -> {
            I2cBus bus = bus(params);
            params.end();
            return Reply.response(bus.writeResult(clock.getAsLong()));
        });
        table.register(DekaCommand.I2C_CONFIGURE_CHANNEL, MUTATING, params -> {
            I2cBus bus = bus(params);
            int speedCode = params.u8();
            params.end();
            if (!I2cBus.supportsSpeedCode(speedCode)) {
                diagnostics.unsupported(DekaCommand.I2C_CONFIGURE_CHANNEL.number(), "I2C bus speeds above 400 kHz");
                throw new CommandRejected(NackReason.param(1));
            }
            bus.setSpeedCode(speedCode);
            return Reply.ACK;
        });
        table.register(DekaCommand.I2C_CONFIGURE_QUERY, QUERY, params -> {
            I2cBus bus = bus(params);
            params.end();
            return Reply.response(new PayloadWriter().u8(bus.speedCode()));
        });
    }

    private I2cBus bus(Params params) {
        return buses[params.u8(0, buses.length - 1)];
    }

    private static int address(Params params) {
        return params.u8(0, 127);
    }
}
