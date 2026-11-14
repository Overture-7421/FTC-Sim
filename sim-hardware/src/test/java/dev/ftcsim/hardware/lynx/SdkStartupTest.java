package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The calls the SDK makes when it brings a hub up, replayed from its source, must all be accepted. */
class SdkStartupTest {

    private final Rig rig = new Rig();
    private final RhspClient client = rig.timedClient();

    @Test
    void theHandshakeOfLynxModuleAndTheControllersIsAcceptedWithoutAnUnexpectedNack() {
        // LynxModule.pingAndQueryKnownInterfacesAndEtc
        assertTrue(client.standard(StandardCommand.KEEP_ALIVE, Bytes.none()).isAck());
        client.standard(StandardCommand.QUERY_INTERFACE, "DEKA\0".getBytes(StandardCharsets.UTF_8));
        client.ack(DekaCommand.FTDI_RESET_CONTROL, Bytes.of().u8(1).build());
        assertTrue(client.standard(StandardCommand.DEBUG_LOG_LEVEL, Bytes.of().u8(6).u8(3).build()).isAck());
        assertTrue(client.standard(StandardCommand.KEEP_ALIVE, Bytes.none()).isAck());
        byte[] idlePattern = {10, 0, (byte) 255, 0, 0, 0, 0, 0};
        assertTrue(client.standard(StandardCommand.SET_MODULE_LED_PATTERN, idlePattern).isAck());

        // the status fetch an acknowledgement with the attention flag makes the SDK schedule
        assertEquals(0xFF03, client.standard(StandardCommand.GET_MODULE_STATUS, Bytes.of().u8(1).build()).packetId());
        client.response(DekaCommand.READ_VERSION_STRING, Bytes.none());

        // LynxModule.getRevProductNumber: a Servo Hub would answer this, an Expansion or Control Hub does not
        assertEquals(0, client.standard(StandardCommand.QUERY_INTERFACE, "SERVO_HUB\0".getBytes(StandardCharsets.UTF_8)).nackReason());

        // LynxModule.getImuType: probing a BNO055 that is not there ends in a status, not in an error
        I2cCalls.Result probe = new I2cCalls(client).readRegisters(0, 0x28, 0x00, 1);
        assertEquals(1, probe.status());
        assertEquals(0, probe.data().length);

        initializeMotors();
        initializeServos();
        for (int pin = 0; pin < 8; pin++) {
            client.ack(DekaCommand.SET_DIO_DIRECTION, Bytes.of().u8(pin).u8(0).build());
        }
        for (int channel = 0; channel < 4; channel++) {
            client.ack(DekaCommand.SET_PWM_ENABLE, Bytes.of().u8(channel).u8(0).build());
            client.ack(DekaCommand.SET_PWM_CONFIGURATION, Bytes.of().u8(channel).u16(20_000).build());
        }
        client.ack(DekaCommand.I2C_CONFIGURE_CHANNEL, Bytes.of().u8(1).u8(0).build());

        assertTrue(rig.hub.unsupported().isEmpty());
    }

    /** LynxDcMotorController.initializeHardware: runWithoutEncoders, then floatHardware. */
    private void initializeMotors() {
        for (int motor = 0; motor < 4; motor++) {
            client.response(DekaCommand.GET_MOTOR_CHANNEL_MODE, Bytes.of().u8(motor).build());
            client.response(DekaCommand.GET_MOTOR_CONSTANT_POWER, Bytes.of().u8(motor).build());
            client.ack(DekaCommand.SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(motor).u8(0).u8(0).build());
            client.ack(DekaCommand.SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(motor).u16(0).build());
            client.ack(DekaCommand.SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(motor).u8(1).build());
            client.ack(DekaCommand.SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(motor).u8(0).u8(1).build());
            client.ack(DekaCommand.SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(motor).u16(0).build());
            client.response(DekaCommand.GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(motor).u8(2).build());
            client.response(DekaCommand.GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(motor).u8(1).build());
        }
    }

    /** LynxServoController.initializeHardware: a range for every servo, then pwmDisable. */
    private void initializeServos() {
        for (int servo = 0; servo < 6; servo++) {
            client.ack(DekaCommand.SET_SERVO_CONFIGURATION, Bytes.of().u8(servo).u16(20_000).build());
        }
        for (int servo = 0; servo < 6; servo++) {
            client.ack(DekaCommand.SET_SERVO_ENABLE, Bytes.of().u8(servo).u8(0).build());
        }
    }
}
