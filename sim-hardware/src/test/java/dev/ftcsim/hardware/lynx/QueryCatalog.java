package dev.ftcsim.hardware.lynx;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A valid request for every command that is a query, so that tests can make all of them. A query added to the
 * hub has to be added here as well, which {@link CommandClassificationTest} insists on.
 */
final class QueryCatalog {

    /** One request: the command's name, its packet id and a payload the hub accepts. */
    record Request(String name, int packetId, byte[] payload) {}

    private static final int BATTERY_MONITOR = 13;

    private QueryCatalog() {}

    static List<Request> requests() {
        return List.of(
                standard(StandardCommand.GET_MODULE_STATUS, Bytes.of().u8(0).build()),
                standard(StandardCommand.QUERY_INTERFACE, "DEKA\0".getBytes(StandardCharsets.UTF_8)),
                standard(StandardCommand.GET_MODULE_LED_COLOR, Bytes.none()),
                standard(StandardCommand.GET_MODULE_LED_PATTERN, Bytes.none()),
                standard(StandardCommand.DISCOVERY, Bytes.none()),
                deka(DekaCommand.GET_BULK_INPUT_DATA, Bytes.none()),
                deka(DekaCommand.GET_DIO_DIRECTION, Bytes.of().u8(0).build()),
                deka(DekaCommand.GET_SINGLE_DIO_INPUT, Bytes.of().u8(0).build()),
                deka(DekaCommand.GET_ALL_DIO_INPUTS, Bytes.none()),
                deka(DekaCommand.GET_ADC, Bytes.of().u8(BATTERY_MONITOR).u8(0).build()),
                deka(DekaCommand.GET_MOTOR_CHANNEL_MODE, motor(0)),
                deka(DekaCommand.GET_MOTOR_CHANNEL_ENABLE, motor(0)),
                deka(DekaCommand.GET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, motor(0)),
                deka(DekaCommand.GET_MOTOR_CONSTANT_POWER, motor(0)),
                deka(DekaCommand.GET_MOTOR_TARGET_VELOCITY, motor(0)),
                deka(DekaCommand.GET_MOTOR_TARGET_POSITION, motor(0)),
                deka(DekaCommand.IS_MOTOR_AT_TARGET, motor(0)),
                deka(DekaCommand.GET_MOTOR_ENCODER_POSITION, motor(0)),
                deka(DekaCommand.GET_MOTOR_PID_COEFFICIENTS, Bytes.of().u8(0).u8(HubCalls.VELOCITY).build()),
                deka(DekaCommand.GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(0).u8(HubCalls.VELOCITY).build()),
                deka(DekaCommand.GET_PWM_CONFIGURATION, motor(0)),
                deka(DekaCommand.GET_PWM_PULSE_WIDTH, motor(0)),
                deka(DekaCommand.GET_PWM_ENABLE, motor(0)),
                deka(DekaCommand.GET_SERVO_CONFIGURATION, motor(0)),
                deka(DekaCommand.GET_SERVO_PULSE_WIDTH, motor(0)),
                deka(DekaCommand.GET_SERVO_ENABLE, motor(0)),
                deka(DekaCommand.I2C_CONFIGURE_QUERY, Bytes.of().u8(0).build()),
                deka(DekaCommand.PHONE_CHARGE_QUERY, Bytes.none()),
                deka(DekaCommand.FTDI_RESET_QUERY, Bytes.none()),
                deka(DekaCommand.READ_VERSION_STRING, Bytes.none()));
    }

    /** Whether the request goes to every module rather than to one. */
    static boolean isBroadcast(Request request) {
        return request.packetId() == StandardCommand.DISCOVERY.number();
    }

    private static byte[] motor(int channel) {
        return Bytes.of().u8(channel).build();
    }

    private static Request standard(StandardCommand command, byte[] payload) {
        return new Request(command.name(), command.number(), payload);
    }

    private static Request deka(DekaCommand command, byte[] payload) {
        return new Request(command.name(), command.number(), payload);
    }
}
