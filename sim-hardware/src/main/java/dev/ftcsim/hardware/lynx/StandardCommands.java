package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.MUTATING;
import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

import java.nio.charset.StandardCharsets;

/** The standard commands every module answers, whatever interfaces it has. */
final class StandardCommands {

    private static final int LAST_DEBUG_GROUP = 12;
    private static final int LAST_DEBUG_VERBOSITY = 3;

    private final LynxHub hub;
    private final ModuleStatus status;
    private final Led led;
    private final Diagnostics diagnostics;

    StandardCommands(LynxHub hub, ModuleStatus status, Led led, Diagnostics diagnostics) {
        this.hub = hub;
        this.status = status;
        this.led = led;
        this.diagnostics = diagnostics;
    }

    void register(CommandTable table) {
        table.register(StandardCommand.KEEP_ALIVE, MUTATING, params -> {
            params.end();
            return Reply.ACK;
        });
        table.register(StandardCommand.FAIL_SAFE, MUTATING, params -> {
            params.end();
            hub.failSafe();
            return Reply.ACK;
        });
        // Reading the status only reads; asking for it to be cleared afterwards takes the events away from the host.
        table.register(StandardCommand.GET_MODULE_STATUS, StandardCommands::statusEffect, params -> {
            boolean clear = params.flag();
            params.end();
            int bits = status.bits();
            if (clear) {
                status.clearEvents();
            }
            // The second byte reports encoder and bridge alerts of the motors, which nothing here can raise.
            return Reply.response(new PayloadWriter().u8(bits).u8(0));
        });
        table.register(StandardCommand.SET_NEW_MODULE_ADDRESS, MUTATING, params -> {
            int address = params.u8(1, 254);
            params.end();
            hub.changeAddress(address);
            return Reply.ACK;
        });
        table.register(StandardCommand.QUERY_INTERFACE, QUERY, params -> {
            String name = new String(params.rest(), StandardCharsets.UTF_8);
            if (name.endsWith("\0")) {
                name = name.substring(0, name.length() - 1);
            }
            if (!DekaCommand.INTERFACE_NAME.equals(name)) {
                throw new CommandRejected(NackReason.param(0));
            }
            return Reply.response(new PayloadWriter().u16(DekaCommand.BASE).u16(DekaCommand.values().length));
        });
        table.register(StandardCommand.START_DOWNLOAD, MUTATING, unsupported(StandardCommand.START_DOWNLOAD));
        table.register(StandardCommand.DOWNLOAD_CHUNK, MUTATING, unsupported(StandardCommand.DOWNLOAD_CHUNK));
        table.register(StandardCommand.SET_MODULE_LED_COLOR, MUTATING, params -> {
            byte[] rgb = new byte[] {(byte) params.u8(), (byte) params.u8(), (byte) params.u8()};
            params.end();
            led.setColor(rgb);
            return Reply.ACK;
        });
        table.register(StandardCommand.GET_MODULE_LED_COLOR, QUERY, params -> {
            params.end();
            return Reply.response(new PayloadWriter().bytes(led.color()));
        });
        table.register(StandardCommand.SET_MODULE_LED_PATTERN, MUTATING, params -> {
            byte[] steps = params.rest();
            if (steps.length == 0 || steps.length % Led.STEP_BYTES != 0 || steps.length > Led.MAX_PATTERN_BYTES) {
                throw new CommandRejected(NackReason.param(0));
            }
            led.setPattern(steps);
            return Reply.ACK;
        });
        table.register(StandardCommand.GET_MODULE_LED_PATTERN, QUERY, params -> {
            params.end();
            return Reply.response(new PayloadWriter().bytes(led.pattern()));
        });
        table.register(StandardCommand.DEBUG_LOG_LEVEL, MUTATING, params -> {
            params.u8(0, LAST_DEBUG_GROUP);
            params.u8(0, LAST_DEBUG_VERBOSITY);
            params.end();
            return Reply.ACK;
        });
        table.register(StandardCommand.DISCOVERY, QUERY, params -> {
            params.end();
            return Reply.response(new PayloadWriter().flag(hub.isParent()));
        });
    }

    private static CommandTable.Effect statusEffect(byte[] payload) {
        return payload.length > 0 && payload[0] != 0 ? MUTATING : QUERY;
    }

    /** Firmware download is not modelled: the command is known, so the NACK says its implementation is pending. */
    private CommandTable.Handler unsupported(StandardCommand command) {
        return params -> {
            diagnostics.unsupported(command.number(), "firmware download");
            throw new CommandRejected(NackReason.COMMAND_IMPL_PENDING);
        };
    }
}
