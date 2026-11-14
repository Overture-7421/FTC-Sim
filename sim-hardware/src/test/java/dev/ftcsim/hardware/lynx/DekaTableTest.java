package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.FTDI_RESET_CONTROL;
import static dev.ftcsim.hardware.lynx.DekaCommand.FTDI_RESET_QUERY;
import static dev.ftcsim.hardware.lynx.DekaCommand.INJECT_DATA_LOG_HINT;
import static dev.ftcsim.hardware.lynx.DekaCommand.PHONE_CHARGE_CONTROL;
import static dev.ftcsim.hardware.lynx.DekaCommand.PHONE_CHARGE_QUERY;
import static dev.ftcsim.hardware.lynx.DekaCommand.READ_VERSION_STRING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DekaTableTest {

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;

    @Test
    void theTableHasFiftyFourCommandsInTheOrderTheSdkBuildsIt() {
        assertEquals(54, DekaCommand.values().length);
        assertEquals(0x1000, DekaCommand.GET_BULK_INPUT_DATA.number());
        assertEquals(0x1007, DekaCommand.GET_ADC.number());
        assertEquals(0x1008, DekaCommand.SET_MOTOR_CHANNEL_MODE.number());
        assertEquals(0x1017, DekaCommand.SET_MOTOR_PID_COEFFICIENTS.number());
        assertEquals(0x101F, DekaCommand.SET_SERVO_CONFIGURATION.number());
        assertEquals(0x1025, DekaCommand.I2C_WRITE_SINGLE_BYTE.number());
        assertEquals(0x1029, DekaCommand.I2C_READ_STATUS_QUERY.number());
        assertEquals(0x102B, DekaCommand.I2C_CONFIGURE_CHANNEL.number());
        assertEquals(0x1030, DekaCommand.READ_VERSION_STRING.number());
        assertEquals(0x1033, DekaCommand.SET_MOTOR_PIDF_COEFFICIENTS.number());
        assertEquals(0x1034, DekaCommand.I2C_WRITE_READ_MULTIPLE_BYTES.number());
        assertEquals(0x1035, DekaCommand.GET_MOTOR_PIDF_COEFFICIENTS.number());
    }

    @Test
    void everyDekaCommandIsKnownAndNothingBeyondTheTableIs() {
        for (DekaCommand command : DekaCommand.values()) {
            RhspClient.Reply reply = client.deka(command, Bytes.none());
            if (reply.isNack()) {
                assertNotEquals(NackReason.PACKET_TYPE_ID_UNKNOWN, reply.nackReason(), command.toString());
                assertNotEquals(NackReason.COMMAND_IMPL_PENDING, reply.nackReason(), command.toString());
            }
        }
        assertTrue(rig.hub.unsupported().isEmpty());

        assertEquals(NackReason.PACKET_TYPE_ID_UNKNOWN, client.send(0x1036, Bytes.none()).nackReason());
    }

    @Test
    void theVersionStringHasTheLayoutTheSdkParses() {
        ByteBuffer data = client.response(READ_VERSION_STRING, Bytes.none());
        byte[] text = new byte[data.get() & 0xFF];
        data.get(text);
        String version = new String(text, StandardCharsets.US_ASCII);

        // LynxModuleWarningManager.lookForOutdatedModules
        String[] fields = Arrays.copyOfRange(version.split("(, )?\\w*: "), 2, 5);
        assertArrayEquals(new String[] {"1", "8", "2"}, fields);
        // CachedLynxModulesInfo.formatFirmwareVersion
        String shown = version.substring(version.indexOf(',') + 1).replaceAll("[a-zA-Z: ]*", "").replaceAll(",", ".");
        assertEquals("1.8.2", shown);
    }

    @Test
    void phoneChargingAndTheFtdiWatchdogAreRememberedAsSet() {
        assertEquals(0, client.response(PHONE_CHARGE_QUERY, Bytes.none()).get());
        client.ack(PHONE_CHARGE_CONTROL, Bytes.of().u8(1).build());
        assertEquals(1, client.response(PHONE_CHARGE_QUERY, Bytes.none()).get());
        client.ack(PHONE_CHARGE_CONTROL, Bytes.of().u8(0).build());
        assertEquals(0, client.response(PHONE_CHARGE_QUERY, Bytes.none()).get());

        assertEquals(0, client.response(FTDI_RESET_QUERY, Bytes.none()).get());
        client.ack(FTDI_RESET_CONTROL, Bytes.of().u8(1).build());
        assertEquals(1, client.response(FTDI_RESET_QUERY, Bytes.none()).get());
    }

    @Test
    void dataLogHintsOfUpToOneHundredBytesAreAcceptedAndNothingIsKept() {
        client.ack(INJECT_DATA_LOG_HINT, Bytes.of().u8(5).raw("hello".getBytes(StandardCharsets.UTF_8)).build());
        client.ack(INJECT_DATA_LOG_HINT, Bytes.of().u8(0).build());

        assertEquals(0, client.nack(INJECT_DATA_LOG_HINT, Bytes.of().u8(101).raw(new byte[101]).build()));
        assertEquals(1, client.nack(INJECT_DATA_LOG_HINT, Bytes.of().u8(5).raw(new byte[2]).build()));
    }
}
