package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every DEKA command once on its success path and once with its first parameter wrong or missing. */
class DekaCommandMatrixTest {

    private static final int PARAM0 = 0;

    /**
     * @param command  the command under test
     * @param response whether the success path answers with a response packet instead of an acknowledgement
     * @param valid    a payload the hub accepts once {@code setup} has run
     * @param invalid  a payload whose first parameter is out of range or absent
     * @param setup    what the hub must be told first for the command to make sense
     */
    private record Row(DekaCommand command, boolean response, byte[] valid, byte[] invalid, Consumer<Rig> setup) {}

    private static Row ack(DekaCommand command, byte[] valid, byte[] invalid) {
        return new Row(command, false, valid, invalid, rig -> {});
    }

    private static Row response(DekaCommand command, byte[] valid, byte[] invalid) {
        return new Row(command, true, valid, invalid, rig -> {});
    }

    private static Row afterSetup(Row row, Consumer<Rig> setup) {
        return new Row(row.command, row.response, row.valid, row.invalid, setup);
    }

    private static byte[] channel(int channel) {
        return Bytes.of().u8(channel).build();
    }

    private static byte[] withChannel(int channel, int value) {
        return Bytes.of().u8(channel).u16(value).build();
    }

    private static byte[] pid(int motor, int mode) {
        return Bytes.of().u8(motor).u8(mode).i32(65536).i32(0).i32(0).build();
    }

    private static byte[] pidf(int motor, int mode) {
        return Bytes.of().u8(motor).u8(mode).i32(65536).i32(0).i32(0).i32(65536).u8(1).build();
    }

    static Stream<Row> rows() {
        byte[] none = Bytes.none();
        byte[] extra = {0};
        Consumer<Rig> firstPinIsAnOutput = rig -> rig.client.ack(SET_DIO_DIRECTION, Bytes.of().u8(0).u8(1).build());
        Consumer<Rig> positionMode = rig -> new HubCalls(rig.client).setMode(1, HubCalls.POSITION, false);
        Consumer<Rig> servoConfigured = rig -> rig.client.ack(SET_SERVO_CONFIGURATION, withChannel(0, 20_000));
        Consumer<Rig> readWaiting = rig -> {
            rig.client.ack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).build());
            rig.advanceTo(rig.clock.get() + 5 * Rig.MS);
        };
        Consumer<Rig> writeWaiting = rig -> {
            rig.client.ack(I2C_WRITE_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).u8(1).build());
            rig.advanceTo(rig.clock.get() + 5 * Rig.MS);
        };
        return Stream.of(
                response(GET_BULK_INPUT_DATA, none, extra),
                afterSetup(ack(SET_SINGLE_DIO_OUTPUT, Bytes.of().u8(0).u8(1).build(), Bytes.of().u8(8).u8(1).build()), firstPinIsAnOutput),
                afterSetup(ack(SET_ALL_DIO_OUTPUTS, Bytes.of().u8(0xFF).build(), none), firstPinIsAnOutput),
                ack(SET_DIO_DIRECTION, Bytes.of().u8(2).u8(1).build(), Bytes.of().u8(8).u8(1).build()),
                response(GET_DIO_DIRECTION, channel(2), channel(8)),
                response(GET_SINGLE_DIO_INPUT, channel(3), channel(8)),
                response(GET_ALL_DIO_INPUTS, none, extra),
                response(GET_ADC, Bytes.of().u8(13).u8(0).build(), Bytes.of().u8(15).u8(0).build()),
                ack(SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(0).u8(1).u8(0).build(), Bytes.of().u8(4).u8(1).u8(0).build()),
                response(GET_MOTOR_CHANNEL_MODE, channel(0), channel(4)),
                ack(SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).u8(1).build(), Bytes.of().u8(4).u8(1).build()),
                response(GET_MOTOR_CHANNEL_ENABLE, channel(0), channel(4)),
                ack(SET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, withChannel(0, 5000), withChannel(4, 5000)),
                response(GET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, channel(0), channel(4)),
                ack(RESET_MOTOR_ENCODER, channel(0), channel(4)),
                ack(SET_MOTOR_CONSTANT_POWER, withChannel(0, 1000), withChannel(4, 1000)),
                response(GET_MOTOR_CONSTANT_POWER, channel(0), channel(4)),
                ack(SET_MOTOR_TARGET_VELOCITY, withChannel(0, 1000), withChannel(4, 1000)),
                response(GET_MOTOR_TARGET_VELOCITY, channel(0), channel(4)),
                ack(SET_MOTOR_TARGET_POSITION, Bytes.of().u8(0).i32(100).u16(5).build(), Bytes.of().u8(4).i32(100).u16(5).build()),
                response(GET_MOTOR_TARGET_POSITION, channel(0), channel(4)),
                afterSetup(response(IS_MOTOR_AT_TARGET, channel(1), channel(4)), positionMode),
                response(GET_MOTOR_ENCODER_POSITION, channel(0), channel(4)),
                ack(SET_MOTOR_PID_COEFFICIENTS, pid(0, 1), pid(4, 1)),
                response(GET_MOTOR_PID_COEFFICIENTS, Bytes.of().u8(0).u8(1).build(), Bytes.of().u8(4).u8(1).build()),
                ack(SET_PWM_CONFIGURATION, withChannel(0, 20_000), withChannel(4, 20_000)),
                response(GET_PWM_CONFIGURATION, channel(0), channel(4)),
                ack(SET_PWM_PULSE_WIDTH, withChannel(0, 1500), withChannel(4, 1500)),
                response(GET_PWM_PULSE_WIDTH, channel(0), channel(4)),
                ack(SET_PWM_ENABLE, Bytes.of().u8(0).u8(1).build(), Bytes.of().u8(4).u8(1).build()),
                response(GET_PWM_ENABLE, channel(0), channel(4)),
                ack(SET_SERVO_CONFIGURATION, withChannel(0, 20_000), withChannel(6, 20_000)),
                response(GET_SERVO_CONFIGURATION, channel(0), channel(6)),
                ack(SET_SERVO_PULSE_WIDTH, withChannel(0, 1500), withChannel(6, 1500)),
                response(GET_SERVO_PULSE_WIDTH, channel(0), channel(6)),
                afterSetup(ack(SET_SERVO_ENABLE, Bytes.of().u8(0).u8(1).build(), Bytes.of().u8(6).u8(1).build()), servoConfigured),
                response(GET_SERVO_ENABLE, channel(0), channel(6)),
                ack(I2C_WRITE_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).u8(0x55).build(), Bytes.of().u8(4).u8(0x28).u8(0x55).build()),
                ack(I2C_WRITE_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(2).u8(1).u8(2).build(), Bytes.of().u8(4).u8(0x28).u8(2).u8(1).u8(2).build()),
                ack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).build(), Bytes.of().u8(4).u8(0x28).build()),
                ack(I2C_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(4).build(), Bytes.of().u8(4).u8(0x28).u8(4).build()),
                afterSetup(response(I2C_READ_STATUS_QUERY, channel(0), channel(4)), readWaiting),
                afterSetup(response(I2C_WRITE_STATUS_QUERY, channel(0), channel(4)), writeWaiting),
                ack(I2C_CONFIGURE_CHANNEL, Bytes.of().u8(0).u8(1).build(), Bytes.of().u8(4).u8(1).build()),
                ack(PHONE_CHARGE_CONTROL, Bytes.of().u8(1).build(), none),
                response(PHONE_CHARGE_QUERY, none, extra),
                ack(INJECT_DATA_LOG_HINT, Bytes.of().u8(2).u8('h').u8('i').build(), Bytes.of().u8(101).raw(new byte[101]).build()),
                response(I2C_CONFIGURE_QUERY, channel(0), channel(4)),
                response(READ_VERSION_STRING, none, extra),
                ack(FTDI_RESET_CONTROL, Bytes.of().u8(1).build(), none),
                response(FTDI_RESET_QUERY, none, extra),
                ack(SET_MOTOR_PIDF_COEFFICIENTS, pidf(0, 1), pidf(4, 1)),
                ack(I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(3).u8(0x10).build(), Bytes.of().u8(4).u8(0x28).u8(3).u8(0x10).build()),
                response(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(0).u8(1).build(), Bytes.of().u8(4).u8(1).build()));
    }

    @Test
    void theMatrixHasExactlyOneRowForEveryDekaCommand() {
        List<DekaCommand> covered = rows().map(Row::command).collect(Collectors.toList());

        assertEquals(DekaCommand.values().length, covered.size());
        Set<DekaCommand> distinct = EnumSet.copyOf(covered);
        assertEquals(EnumSet.allOf(DekaCommand.class), distinct);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rowArguments")
    void aValidCommandSucceedsAndABadFirstParameterNamesParameterZero(DekaCommand command, Row row) {
        Rig rig = new Rig();
        rig.hub.attach(0, new MemoryDevice(0x28));
        row.setup().accept(rig);

        RhspClient.Reply good = rig.client.deka(command, row.valid());
        if (row.response()) {
            assertEquals(0x9000 + command.ordinal(), good.packetId(), "success path of " + command);
        } else {
            assertTrue(good.isAck(), command + " should be acknowledged, got " + good.packetId());
        }

        RhspClient.Reply bad = rig.client.deka(command, row.invalid());
        assertTrue(bad.isNack(), command + " accepted " + Arrays.toString(row.invalid()));
        assertEquals(PARAM0, bad.nackReason(), command.toString());
    }

    static Stream<Arguments> rowArguments() {
        return rows().map(row -> Arguments.of(row.command(), row));
    }
}
