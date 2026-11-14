package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.StandardCommand.GET_MODULE_LED_COLOR;
import static dev.ftcsim.hardware.lynx.StandardCommand.GET_MODULE_STATUS;
import static dev.ftcsim.hardware.lynx.StandardCommand.KEEP_ALIVE;
import static dev.ftcsim.hardware.lynx.StandardCommand.SET_MODULE_LED_COLOR;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeepAliveTest {

    private static final int KEEP_ALIVE_TIMEOUT = 0x01;
    private static final int DEVICE_RESET = 0x02;
    private static final int FAIL_SAFE = 0x04;

    private final Rig supervised = new Rig(HubConfig.controlHub());
    private final HubCalls calls = new HubCalls(supervised.client);

    @Test
    void aHostThatGoesSilentIsFailSafedWhenTheSdksTwoAndAHalfSecondsAreUp() {
        DcMotorPlant plant = drive(supervised, 0);

        supervised.runMillis(2499);
        assertFalse(plant.isFloating(), "2.5 s of silence is still within the timeout");
        supervised.runMillis(1);

        assertTrue(plant.isFloating());
        assertEquals(KEEP_ALIVE_TIMEOUT | DEVICE_RESET | FAIL_SAFE, status(supervised));
        assertEquals(0, supervised.client.response(DekaCommand.GET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).build()).get());
    }

    @Test
    void anyDatagramCountsAsAKeepAliveNotJustTheKeepAliveCommand() {
        DcMotorPlant plant = drive(supervised, 0);

        for (int i = 0; i < 10; i++) {
            supervised.runMillis(2400);
            calls.bulk();
        }

        assertFalse(plant.isFloating());
        assertEquals(DEVICE_RESET, status(supervised));
    }

    @Test
    void theHubTakesCommandsAgainAfterATimeoutAndIsSupervisedAgain() {
        DcMotorPlant plant = drive(supervised, 0);
        supervised.runMillis(2600);
        assertTrue(plant.isFloating());

        calls.enable(0, true);
        calls.setPower(0, 16384);
        supervised.runMillis(2000);
        assertFalse(plant.isFloating());

        supervised.runMillis(600);
        assertTrue(plant.isFloating(), "silent for another 2.5 s");
    }

    @Test
    void theTimeoutAlsoSwitchesOffServosAndPwmAndMakesTheHubForgetItsLed() {
        supervised.client.ack(DekaCommand.SET_SERVO_CONFIGURATION, Bytes.of().u8(1).u16(20_000).build());
        supervised.client.ack(DekaCommand.SET_SERVO_ENABLE, Bytes.of().u8(1).u8(1).build());
        supervised.client.ack(DekaCommand.SET_PWM_ENABLE, Bytes.of().u8(2).u8(1).build());
        supervised.client.standard(SET_MODULE_LED_COLOR, Bytes.of().u8(1).u8(2).u8(3).build());

        supervised.runMillis(2501);

        assertFalse(supervised.hub.servoEnabled(1));
        assertFalse(supervised.hub.pwmEnabled(2));
        assertArrayEquals(new byte[3], supervised.client.standard(GET_MODULE_LED_COLOR, Bytes.none()).payload());
    }

    @Test
    void silenceBeforeTheHostHasEverSpokenIsNotATimeout() {
        supervised.runMillis(10_000);

        assertEquals(DEVICE_RESET, status(supervised));
    }

    @Test
    void withTheSupervisionSwitchedOffTheHubNeverTimesOut() {
        Rig unsupervised = new Rig();
        DcMotorPlant plant = drive(unsupervised, 0);

        unsupervised.runMillis(60_000);

        assertFalse(plant.isFloating());
        assertEquals(DEVICE_RESET, status(unsupervised));
    }

    @ParameterizedTest(name = "supervision {0}")
    @ValueSource(booleans = {true, false})
    void anExplicitFailSafeStopsTheMotorsWhetherOrNotTheHubIsSupervised(boolean supervision) {
        Rig rig = new Rig(HubConfig.controlHub().withKeepAliveSupervision(supervision));
        DcMotorPlant plant = drive(rig, 0);
        rig.runMillis(10);
        assertFalse(plant.isFloating());

        assertTrue(rig.client.standard(StandardCommand.FAIL_SAFE, Bytes.none()).isAck());

        assertTrue(plant.isFloating());
        assertEquals(DEVICE_RESET | FAIL_SAFE, status(rig));
    }

    @ParameterizedTest(name = "supervision {0}")
    @ValueSource(booleans = {true, false})
    void anExplicitKeepAliveIsAcknowledgedAndHoldsOffTheTimeoutWhenSupervised(boolean supervision) {
        Rig rig = new Rig(HubConfig.controlHub().withKeepAliveSupervision(supervision));
        DcMotorPlant plant = drive(rig, 0);

        for (int i = 0; i < 8; i++) {
            rig.runMillis(1950);
            assertTrue(rig.client.standard(KEEP_ALIVE, Bytes.none()).isAck());
        }

        assertFalse(plant.isFloating());
    }

    private static DcMotorPlant drive(Rig rig, int motor) {
        DcMotorPlant plant = rig.plugGoBilda(motor);
        HubCalls hub = new HubCalls(rig.client);
        hub.enable(motor, true);
        hub.setPower(motor, 16384);
        rig.runMillis(1);
        assertFalse(plant.isFloating());
        return plant;
    }

    private static int status(Rig rig) {
        return rig.client.standard(GET_MODULE_STATUS, Bytes.of().u8(0).build()).data().get() & 0xFF;
    }
}
