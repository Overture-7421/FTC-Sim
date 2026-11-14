package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

import java.util.function.LongSupplier;

/** The {@code GetADC} command, mapped onto {@link AdcBank}. */
final class AdcCommands {

    private static final int ENGINEERING_MODE = 0;
    private static final int RAW_MODE = 1;

    private final AdcBank adc;
    private final Diagnostics diagnostics;
    private final LongSupplier clock;

    AdcCommands(AdcBank adc, Diagnostics diagnostics, LongSupplier clock) {
        this.adc = adc;
        this.diagnostics = diagnostics;
        this.clock = clock;
    }

    void register(CommandTable table) {
        table.register(DekaCommand.GET_ADC, QUERY, params -> {
            AdcChannel channel = AdcChannel.fromWire(params.u8(0, AdcChannel.values().length - 1));
            int mode = params.u8(ENGINEERING_MODE, RAW_MODE);
            params.end();
            long now = clock.getAsLong();
            int value;
            if (mode == ENGINEERING_MODE) {
                value = adc.engineering(channel, now);
            } else if (channel.isUserInput()) {
                value = adc.userRawCounts(channel, now);
            } else {
                diagnostics.unsupported(DekaCommand.GET_ADC.number(), "raw counts of ADC channel " + channel);
                throw new CommandRejected(NackReason.param(1));
            }
            return Reply.response(new PayloadWriter().u16(value));
        });
    }
}
