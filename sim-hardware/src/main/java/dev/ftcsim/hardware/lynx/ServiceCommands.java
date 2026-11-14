package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.MUTATING;
import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

import java.nio.charset.StandardCharsets;

/**
 * The DEKA commands that report on or configure the hub itself rather than its ports: the firmware version, phone
 * charging, the FTDI reset watchdog and the data log hint. None of them acts on anything in the simulation, so
 * each only remembers what it was told.
 */
final class ServiceCommands {

    private static final int FIRMWARE_MAJOR = 1;
    private static final int FIRMWARE_MINOR = 8;
    private static final int FIRMWARE_ENGINEERING = 2;
    private static final int MAX_HINT_BYTES = 100;

    private final byte[] versionString;
    private boolean phoneCharging;
    private boolean ftdiResetWatchdog;

    ServiceCommands(HubConfig config) {
        // The layout the SDK parses in LynxModuleWarningManager and CachedLynxModulesInfo.
        versionString = String.format(
                        "HW: %d, Maj: %d, Min: %d, Eng: %d",
                        config.hardwareRevision(), FIRMWARE_MAJOR, FIRMWARE_MINOR, FIRMWARE_ENGINEERING)
                .getBytes(StandardCharsets.US_ASCII);
    }

    void register(CommandTable table) {
        table.register(DekaCommand.PHONE_CHARGE_CONTROL, MUTATING, params -> {
            boolean enabled = params.flag();
            params.end();
            phoneCharging = enabled;
            return Reply.ACK;
        });
        table.register(DekaCommand.PHONE_CHARGE_QUERY, QUERY, params -> {
            params.end();
            return Reply.response(new PayloadWriter().flag(phoneCharging));
        });
        table.register(DekaCommand.FTDI_RESET_CONTROL, MUTATING, params -> {
            boolean enabled = params.flag();
            params.end();
            ftdiResetWatchdog = enabled;
            return Reply.ACK;
        });
        table.register(DekaCommand.FTDI_RESET_QUERY, QUERY, params -> {
            params.end();
            return Reply.response(new PayloadWriter().flag(ftdiResetWatchdog));
        });
        table.register(DekaCommand.INJECT_DATA_LOG_HINT, MUTATING, params -> {
            params.bytes(params.u8(0, MAX_HINT_BYTES));
            params.end();
            return Reply.ACK;
        });
        table.register(DekaCommand.READ_VERSION_STRING, QUERY, params -> {
            params.end();
            return Reply.response(new PayloadWriter().u8(versionString.length).bytes(versionString));
        });
    }
}
