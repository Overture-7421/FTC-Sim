package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.GET_PWM_CONFIGURATION;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_PWM_ENABLE;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_PWM_PULSE_WIDTH;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_SERVO_CONFIGURATION;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_SERVO_ENABLE;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_SERVO_PULSE_WIDTH;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_PWM_CONFIGURATION;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_PWM_ENABLE;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_PWM_PULSE_WIDTH;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_SERVO_CONFIGURATION;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_SERVO_ENABLE;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_SERVO_PULSE_WIDTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PulseOutputsTest {

    private static final int PARAM0 = 0;
    private static final int PARAM1 = 1;

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;

    @Test
    void aServoPowersUpDisabledWithNoFramePeriodAndNoPulse() {
        for (int servo = 0; servo < 6; servo++) {
            assertFalse(rig.hub.servoEnabled(servo));
            assertEquals(0, rig.hub.servoPulseWidthMicros(servo));
            assertEquals(0, rig.hub.servoFramePeriodMicros(servo));
        }
    }

    @Test
    void servoSettingsAreStoredPerChannelAndVisibleToTheHost() {
        client.ack(SET_SERVO_CONFIGURATION, Bytes.of().u8(2).u16(20_000).build());
        client.ack(SET_SERVO_PULSE_WIDTH, Bytes.of().u8(2).u16(1500).build());
        client.ack(SET_SERVO_ENABLE, Bytes.of().u8(2).u8(1).build());

        assertEquals(20_000, client.response(GET_SERVO_CONFIGURATION, Bytes.of().u8(2).build()).getShort());
        assertEquals(1500, client.response(GET_SERVO_PULSE_WIDTH, Bytes.of().u8(2).build()).getShort());
        assertEquals(1, client.response(GET_SERVO_ENABLE, Bytes.of().u8(2).build()).get());
        assertEquals(1500, rig.hub.servoPulseWidthMicros(2));
        assertEquals(20_000, rig.hub.servoFramePeriodMicros(2));
        assertTrue(rig.hub.servoEnabled(2));
        assertFalse(rig.hub.servoEnabled(3));
        assertEquals(0, rig.hub.servoPulseWidthMicros(3));
    }

    @Test
    void pulseWidthsAndFramePeriodsAreUnsignedSixteenBit() {
        client.ack(SET_SERVO_CONFIGURATION, Bytes.of().u8(0).u16(65_535).build());
        client.ack(SET_SERVO_PULSE_WIDTH, Bytes.of().u8(0).u16(65_535).build());

        assertEquals(65_535, rig.hub.servoFramePeriodMicros(0));
        assertEquals(65_535, rig.hub.servoPulseWidthMicros(0));
    }

    @Test
    void aServoCannotBeSwitchedOnBeforeItHasAFramePeriod() {
        assertEquals(NackReason.SERVO_NOT_CONFIG_BEFORE_ENABLED, client.nack(SET_SERVO_ENABLE, Bytes.of().u8(1).u8(1).build()));
        assertFalse(rig.hub.servoEnabled(1));

        client.ack(SET_SERVO_ENABLE, Bytes.of().u8(1).u8(0).build());
        client.ack(SET_SERVO_CONFIGURATION, Bytes.of().u8(1).u16(20_000).build());
        client.ack(SET_SERVO_ENABLE, Bytes.of().u8(1).u8(1).build());
        assertTrue(rig.hub.servoEnabled(1));
    }

    @Test
    void servoLimitsAreEnforcedOnTheParameterThatBreaksThem() {
        assertEquals(PARAM1, client.nack(SET_SERVO_CONFIGURATION, Bytes.of().u8(0).u16(1).build()));
        client.ack(SET_SERVO_CONFIGURATION, Bytes.of().u8(0).u16(2).build());
        assertEquals(PARAM1, client.nack(SET_SERVO_PULSE_WIDTH, Bytes.of().u8(0).u16(0).build()));
        client.ack(SET_SERVO_PULSE_WIDTH, Bytes.of().u8(0).u16(1).build());
        assertEquals(PARAM0, client.nack(SET_SERVO_PULSE_WIDTH, Bytes.of().u8(6).u16(1500).build()));
        assertEquals(PARAM0, client.nack(GET_SERVO_ENABLE, Bytes.of().u8(6).build()));
        assertEquals(PARAM1, client.nack(GET_SERVO_ENABLE, Bytes.of().u8(0).u8(0).build()));
    }

    @Test
    void thePwmOutputsAreFourPlainChannelsWithoutTheServoRules() {
        client.ack(SET_PWM_ENABLE, Bytes.of().u8(3).u8(1).build());
        client.ack(SET_PWM_PULSE_WIDTH, Bytes.of().u8(3).u16(0).build());
        client.ack(SET_PWM_CONFIGURATION, Bytes.of().u8(3).u16(0).build());
        client.ack(SET_PWM_PULSE_WIDTH, Bytes.of().u8(0).u16(1234).build());
        client.ack(SET_PWM_CONFIGURATION, Bytes.of().u8(0).u16(5000).build());

        assertEquals(1, client.response(GET_PWM_ENABLE, Bytes.of().u8(3).build()).get());
        assertEquals(1234, client.response(GET_PWM_PULSE_WIDTH, Bytes.of().u8(0).build()).getShort());
        assertEquals(5000, client.response(GET_PWM_CONFIGURATION, Bytes.of().u8(0).build()).getShort());
        assertTrue(rig.hub.pwmEnabled(3));
        assertEquals(1234, rig.hub.pwmPulseWidthMicros(0));
        assertEquals(PARAM0, client.nack(SET_PWM_ENABLE, Bytes.of().u8(4).u8(1).build()));
    }

    @Test
    void failSafeSwitchesEveryServoAndPwmOutputOffAndKeepsTheirSettings() {
        for (int channel = 0; channel < 4; channel++) {
            client.ack(SET_SERVO_CONFIGURATION, Bytes.of().u8(channel).u16(20_000).build());
            client.ack(SET_SERVO_PULSE_WIDTH, Bytes.of().u8(channel).u16(1500).build());
            client.ack(SET_SERVO_ENABLE, Bytes.of().u8(channel).u8(1).build());
            client.ack(SET_PWM_ENABLE, Bytes.of().u8(channel).u8(1).build());
        }

        client.standard(StandardCommand.FAIL_SAFE, Bytes.none());

        for (int channel = 0; channel < 4; channel++) {
            assertFalse(rig.hub.servoEnabled(channel));
            assertFalse(rig.hub.pwmEnabled(channel));
            assertEquals(1500, rig.hub.servoPulseWidthMicros(channel));
        }
        client.ack(SET_SERVO_ENABLE, Bytes.of().u8(0).u8(1).build());
        assertTrue(rig.hub.servoEnabled(0));
    }

    @Test
    void theHostAccessorsRejectChannelsThatDoNotExist() {
        assertThrows(IllegalArgumentException.class, () -> rig.hub.servoEnabled(6));
        assertThrows(IllegalArgumentException.class, () -> rig.hub.servoPulseWidthMicros(-1));
        assertThrows(IllegalArgumentException.class, () -> rig.hub.pwmEnabled(4));
    }
}
