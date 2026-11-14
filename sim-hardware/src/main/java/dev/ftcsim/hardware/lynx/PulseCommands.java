package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.MUTATING;
import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

/** The servo and PWM command families, which differ only in their numbers and limits. */
final class PulseCommands {

    /** The six DEKA commands of one family. */
    record Family(
            DekaCommand setFramePeriod,
            DekaCommand getFramePeriod,
            DekaCommand setPulseWidth,
            DekaCommand getPulseWidth,
            DekaCommand setEnable,
            DekaCommand getEnable) {}

    static final Family SERVO = new Family(
            DekaCommand.SET_SERVO_CONFIGURATION,
            DekaCommand.GET_SERVO_CONFIGURATION,
            DekaCommand.SET_SERVO_PULSE_WIDTH,
            DekaCommand.GET_SERVO_PULSE_WIDTH,
            DekaCommand.SET_SERVO_ENABLE,
            DekaCommand.GET_SERVO_ENABLE);

    static final Family PWM = new Family(
            DekaCommand.SET_PWM_CONFIGURATION,
            DekaCommand.GET_PWM_CONFIGURATION,
            DekaCommand.SET_PWM_PULSE_WIDTH,
            DekaCommand.GET_PWM_PULSE_WIDTH,
            DekaCommand.SET_PWM_ENABLE,
            DekaCommand.GET_PWM_ENABLE);

    private static final int MAX_MICROS = 0xFFFF;

    private PulseCommands() {}

    static void register(CommandTable table, Family family, PulseBank bank) {
        table.register(family.setFramePeriod(), MUTATING, params -> {
            int channel = params.u8(0, bank.count() - 1);
            int micros = params.u16(bank.minFramePeriod(), MAX_MICROS);
            params.end();
            bank.setFramePeriod(channel, micros);
            return Reply.ACK;
        });
        table.register(family.getFramePeriod(), QUERY, params -> {
            int channel = channel(params, bank);
            return Reply.response(new PayloadWriter().u16(bank.framePeriod(channel)));
        });
        table.register(family.setPulseWidth(), MUTATING, params -> {
            int channel = params.u8(0, bank.count() - 1);
            int micros = params.u16(bank.minPulseWidth(), MAX_MICROS);
            params.end();
            bank.setPulseWidth(channel, micros);
            return Reply.ACK;
        });
        table.register(family.getPulseWidth(), QUERY, params -> {
            int channel = channel(params, bank);
            return Reply.response(new PayloadWriter().u16(bank.pulseWidth(channel)));
        });
        table.register(family.setEnable(), MUTATING, params -> {
            int channel = params.u8(0, bank.count() - 1);
            boolean on = params.flag();
            params.end();
            bank.enable(channel, on);
            return Reply.ACK;
        });
        table.register(family.getEnable(), QUERY, params -> {
            int channel = channel(params, bank);
            return Reply.response(new PayloadWriter().flag(bank.enabled(channel)));
        });
    }

    private static int channel(Params params, PulseBank bank) {
        int channel = params.u8(0, bank.count() - 1);
        params.end();
        return channel;
    }
}
