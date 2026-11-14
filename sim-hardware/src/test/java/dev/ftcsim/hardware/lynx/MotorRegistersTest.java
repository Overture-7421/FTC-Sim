package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_CHANNEL_ENABLE;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_CHANNEL_MODE;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_CONSTANT_POWER;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_PIDF_COEFFICIENTS;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_PID_COEFFICIENTS;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_TARGET_POSITION;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_MOTOR_TARGET_VELOCITY;
import static dev.ftcsim.hardware.lynx.DekaCommand.IS_MOTOR_AT_TARGET;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_CHANNEL_ENABLE;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_CHANNEL_MODE;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_CONSTANT_POWER;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_PIDF_COEFFICIENTS;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_PID_COEFFICIENTS;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_TARGET_POSITION;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_MOTOR_TARGET_VELOCITY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** What the motor commands store and refuse, without anything connected to the ports. */
class MotorRegistersTest {

    private static final int PARAM1 = 1;
    private static final int PARAM2 = 2;

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;
    private final HubCalls hub = new HubCalls(client);

    @Test
    void aMotorPowersUpInOpenLoopWithBrakingAndNothingEnabled() {
        ByteBuffer mode = client.response(GET_MOTOR_CHANNEL_MODE, Bytes.of().u8(3).build());

        assertEquals(HubCalls.OPEN_LOOP, mode.get());
        assertEquals(0, mode.get());
        assertEquals(0, client.response(GET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(3).build()).get());
        assertEquals(0, client.response(GET_MOTOR_CONSTANT_POWER, Bytes.of().u8(3).build()).getShort());
        assertEquals(10_000, client.response(GET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, Bytes.of().u8(3).build()).getShort());
    }

    @Test
    void modeAndZeroPowerBehaviourAreStoredPerChannel() {
        hub.setMode(1, HubCalls.VELOCITY, true);
        hub.setMode(2, HubCalls.POSITION, false);

        ByteBuffer first = client.response(GET_MOTOR_CHANNEL_MODE, Bytes.of().u8(1).build());
        assertEquals(HubCalls.VELOCITY, first.get());
        assertEquals(1, first.get());
        ByteBuffer second = client.response(GET_MOTOR_CHANNEL_MODE, Bytes.of().u8(2).build());
        assertEquals(HubCalls.POSITION, second.get());
        assertEquals(0, second.get());
        assertEquals(HubCalls.OPEN_LOOP, client.response(GET_MOTOR_CHANNEL_MODE, Bytes.of().u8(0).build()).get());
    }

    @Test
    void channelsAndModesOutsideTheirRangesNameTheOffendingParameter() {
        assertEquals(0, client.nack(SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(4).u8(0).u8(0).build()));
        assertEquals(PARAM1, client.nack(SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(0).u8(3).u8(0).build()));
        assertEquals(2, client.nack(SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(0).u8(0).build()));
        assertEquals(0, client.nack(GET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(4).build()));
        assertEquals(1, client.nack(GET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).u8(0).build()));
    }

    @Test
    void powerAndVelocityAreSignedSixteenBitAndStopShortOfTheMostNegativeValue() {
        for (int value : new int[] {32767, -32767, 0, 1, -1}) {
            client.ack(SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(0).u16(value).build());
            assertEquals(value, client.response(GET_MOTOR_CONSTANT_POWER, Bytes.of().u8(0).build()).getShort());
            client.ack(SET_MOTOR_TARGET_VELOCITY, Bytes.of().u8(0).u16(value).build());
            assertEquals(value, client.response(GET_MOTOR_TARGET_VELOCITY, Bytes.of().u8(0).build()).getShort());
        }

        assertEquals(PARAM1, client.nack(SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(0).u16(0x8000).build()));
        assertEquals(PARAM1, client.nack(SET_MOTOR_TARGET_VELOCITY, Bytes.of().u8(0).u16(0x8000).build()));
    }

    @Test
    void theTargetPositionAndItsUnsignedToleranceAreStored() {
        client.ack(SET_MOTOR_TARGET_POSITION, Bytes.of().u8(2).i32(-123_456).u16(60_000).build());

        ByteBuffer stored = client.response(GET_MOTOR_TARGET_POSITION, Bytes.of().u8(2).build());
        assertEquals(-123_456, stored.getInt());
        assertEquals(60_000, stored.getShort() & 0xFFFF);
    }

    @Test
    void positionModeCannotBeEnergisedBeforeATargetIsSet() {
        hub.setMode(0, HubCalls.POSITION, false);

        assertEquals(NackReason.MOTOR_NOT_CONFIG_BEFORE_ENABLED, client.nack(SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).u8(1).build()));
        assertEquals(0, client.response(GET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).build()).get());

        hub.setTarget(0, 100, 5);
        hub.enable(0, true);
        assertEquals(1, client.response(GET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).build()).get());
        hub.enable(0, false);
    }

    @Test
    void onlyPositionModeHasATargetToBeAt() {
        hub.setMode(0, HubCalls.VELOCITY, false);
        assertEquals(NackReason.COMMAND_INVALID_FOR_MOTOR_MODE, client.nack(IS_MOTOR_AT_TARGET, Bytes.of().u8(0).build()));

        hub.setMode(0, HubCalls.POSITION, false);
        hub.setTarget(0, 100, 5);
        assertEquals(0, client.response(IS_MOTOR_AT_TARGET, Bytes.of().u8(0).build()).get());
        hub.setTarget(0, 3, 5);
        assertEquals(1, client.response(IS_MOTOR_AT_TARGET, Bytes.of().u8(0).build()).get());
    }

    @Test
    void theCurrentAlertLevelIsStoredAsUnsignedMilliamps() {
        client.ack(SET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, Bytes.of().u8(1).u16(40_000).build());

        assertEquals(40_000, client.response(GET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, Bytes.of().u8(1).build()).getShort() & 0xFFFF);
    }

    @Test
    void theHubPowersUpWithTheGainsTheSdkSeesOnARealHub() {
        ByteBuffer velocity = client.response(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(0).u8(HubCalls.VELOCITY).build());
        assertEquals(10.0, velocity.getInt() / 65536.0);
        assertEquals(3.0, velocity.getInt() / 65536.0);
        assertEquals(0.0, velocity.getInt() / 65536.0);
        assertEquals(0.0, velocity.getInt() / 65536.0);
        assertEquals(HubCalls.LEGACY_PID, velocity.get());

        ByteBuffer position = client.response(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(0).u8(HubCalls.POSITION).build());
        assertEquals(5.0, position.getInt() / 65536.0);
        assertEquals(0.0, position.getInt() / 65536.0);
        assertEquals(0.0, position.getInt() / 65536.0);
        assertEquals(0.0, position.getInt() / 65536.0);
        assertEquals(HubCalls.PIDF, position.get());
    }

    @Test
    void pidfGainsAreStoredPerChannelAndModeAsSixteenDotSixteen() {
        hub.setPidf(1, HubCalls.VELOCITY, 1.26, 0.126, -0.5, 12.6, HubCalls.PIDF);

        ByteBuffer stored = client.response(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(1).u8(HubCalls.VELOCITY).build());
        assertEquals(HubCalls.fixed(1.26), stored.getInt());
        assertEquals(HubCalls.fixed(0.126), stored.getInt());
        assertEquals(HubCalls.fixed(-0.5), stored.getInt());
        assertEquals(HubCalls.fixed(12.6), stored.getInt());
        assertEquals(HubCalls.PIDF, stored.get());
        assertEquals(10.0, client.response(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(0).u8(HubCalls.VELOCITY).build()).getInt() / 65536.0);
        assertEquals(5.0, client.response(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(1).u8(HubCalls.POSITION).build()).getInt() / 65536.0);
    }

    @Test
    void theLegacyPidCommandsSetAndGetWithoutAFeedForwardTerm() {
        client.ack(SET_MOTOR_PID_COEFFICIENTS, Bytes.of().u8(2).u8(HubCalls.POSITION).i32(10 << 16).i32(3277).i32(0).build());

        ByteBuffer legacy = client.response(GET_MOTOR_PID_COEFFICIENTS, Bytes.of().u8(2).u8(HubCalls.POSITION).build());
        assertEquals(10 << 16, legacy.getInt());
        assertEquals(3277, legacy.getInt());
        assertEquals(0, legacy.getInt());
        ByteBuffer full = client.response(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(2).u8(HubCalls.POSITION).build());
        full.position(16);
        assertEquals(HubCalls.LEGACY_PID, full.get());
    }

    @Test
    void theLegacyGetRefusesGainsThatHaveAFeedForwardTermSoTheSdkFallsBackToPidf() {
        hub.setPidf(0, HubCalls.VELOCITY, 1, 0, 0, 12.6, HubCalls.PIDF);

        assertEquals(PARAM2, client.nack(GET_MOTOR_PID_COEFFICIENTS, Bytes.of().u8(0).u8(HubCalls.VELOCITY).build()));
    }

    @Test
    void onlyTheVelocityAndPositionLoopsHaveGainsAndOnlyTwoAlgorithmsExist() {
        assertEquals(PARAM1, client.nack(GET_MOTOR_PIDF_COEFFICIENTS, Bytes.of().u8(0).u8(HubCalls.OPEN_LOOP).build()));
        assertEquals(PARAM1, client.nack(GET_MOTOR_PID_COEFFICIENTS, Bytes.of().u8(0).u8(3).build()));
        assertEquals(6, client.nack(SET_MOTOR_PIDF_COEFFICIENTS,
                Bytes.of().u8(0).u8(1).i32(0).i32(0).i32(0).i32(0).u8(2).build()));
        assertEquals(PARAM2, client.nack(SET_MOTOR_PID_COEFFICIENTS, Bytes.of().u8(0).u8(1).build()));
    }
}
