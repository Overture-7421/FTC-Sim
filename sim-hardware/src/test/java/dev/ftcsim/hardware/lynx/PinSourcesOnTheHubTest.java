package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.GET_SINGLE_DIO_INPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.hardware.pin.PinSources;
import dev.ftcsim.hardware.pin.PinSources.Side;
import org.junit.jupiter.api.Test;

/** Switches and potentiometers on a motor's shaft, read through the hub's own commands while the motor turns. */
class PinSourcesOnTheHubTest {

    private static final int SLIDE_PIN = 2;
    private static final int POT_CHANNEL = 1;

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;
    private final HubCalls hub = new HubCalls(client);

    private boolean pinIsHigh() {
        return client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(SLIDE_PIN).build()).get() != 0;
    }

    private DcMotorPlant spinUpAtHalfPower() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.enable(0, true);
        hub.setPower(0, 16384);
        return plant;
    }

    @Test
    void aSwitchOnTheShaftReadsLowFromTheFirstReadAfterTheShaftPassesItsThresholdAndNeverBefore() {
        DcMotorPlant plant = spinUpAtHalfPower();
        rig.hub.digitalPort(SLIDE_PIN).connect(PinSources.limitSwitch(plant, 600, Side.ABOVE));

        long lastOpen = Long.MIN_VALUE;
        int millis = 0;
        while (pinIsHigh()) {
            lastOpen = plant.encoderCounts();
            rig.runMillis(1);
            assertTrue(++millis < 2000, "the shaft never reached the threshold: " + plant.encoderCounts());
        }

        assertTrue(lastOpen <= 600, "the switch read open at " + lastOpen + " counts");
        assertTrue(plant.encoderCounts() > 600 && plant.encoderCounts() <= 600 + 4, "first closed read at " + plant.encoderCounts());
        assertEquals(0, hub.bulk().digitalInputs() & (1 << SLIDE_PIN), "the bulk read agrees with the single read");
        assertTrue(rig.hub.unboundDigitalPorts().isEmpty());
    }

    @Test
    void theSwitchFollowsTheShaftNotTheEncoderRobotCodeLastZeroed() {
        DcMotorPlant plant = spinUpAtHalfPower();
        rig.hub.digitalPort(SLIDE_PIN).connect(PinSources.limitSwitch(plant, 300, Side.ABOVE));
        rig.runMillis(400);
        assertTrue(plant.encoderCounts() > 300);
        assertFalse(pinIsHigh());

        hub.resetEncoder(0);

        assertEquals(0, hub.position(0), "the hub's own reading restarts at zero");
        assertFalse(pinIsHigh(), "the switch is bolted to the mechanism, so it is still closed");
        assertEquals(0, hub.bulk().digitalInputs() & (1 << SLIDE_PIN));
    }

    @Test
    void aPotentiometerOnTheShaftReadsItsVoltageInMillivoltsAsTheShaftTurnsAndTheBulkReadAgrees() {
        DcMotorPlant plant = spinUpAtHalfPower();
        rig.hub.analogPort(POT_CHANNEL).connect(PinSources.potentiometer(plant, 0.5, 0.0002));

        assertEquals(500, hub.adc(POT_CHANNEL), "at zero counts it reads the voltage at zero");
        for (int step = 0; step < 4; step++) {
            rig.runMillis(150);
            long counts = plant.encoderCounts();
            int expected = (int) Math.round((0.5 + 0.0002 * counts) * 1000.0);
            assertTrue(counts > 100 * (step + 1), "the shaft should be turning: " + counts);
            assertEquals(expected, hub.adc(POT_CHANNEL), "at " + counts + " counts");
            assertEquals(expected, hub.bulk().analogMillivolts()[POT_CHANNEL]);
        }
        assertTrue(rig.hub.unboundAnalogPorts().isEmpty());
    }
}
