package dev.ftcsim.hardware.pin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.hardware.lynx.AnalogInput;
import dev.ftcsim.hardware.lynx.DigitalInput;
import dev.ftcsim.hardware.pin.PinSources.Side;
import org.junit.jupiter.api.Test;

class PinSourcesTest {

    /** An encoder whose count the test sets, and which counts how often it is read. */
    private static final class Shaft implements EncoderSource {
        long counts;
        int reads;

        @Override
        public long encoderCounts() {
            reads++;
            return counts;
        }

        @Override
        public double encoderCountsPerSecond() {
            return 0;
        }
    }

    private static boolean high(DigitalInput pin) {
        return pin.level(0);
    }

    @Test
    void aSwitchThatClosesAboveItsThresholdReadsHighUntilTheCountPassesItAndThenLowBecauseItPullsTheLineToGround() {
        Shaft slide = new Shaft();
        DigitalInput limit = PinSources.limitSwitch(slide, 1200, Side.ABOVE);

        slide.counts = -50;
        assertTrue(high(limit), "far below the threshold the switch is open and the pull-up holds the line high");
        slide.counts = 1200;
        assertTrue(high(limit), "at the threshold itself the switch is still open");
        slide.counts = 1201;
        assertFalse(high(limit), "one count past it the switch is closed");
        slide.counts = 5000;
        assertFalse(high(limit));
        slide.counts = 900;
        assertTrue(high(limit), "it opens again when the slide comes back");
    }

    @Test
    void aSwitchThatClosesBelowItsThresholdIsTheMirrorImage() {
        Shaft arm = new Shaft();
        DigitalInput home = PinSources.limitSwitch(arm, 0, Side.BELOW);

        arm.counts = 300;
        assertTrue(high(home));
        arm.counts = 0;
        assertTrue(high(home), "at the threshold itself the switch is open");
        arm.counts = -1;
        assertFalse(high(home));
    }

    @Test
    void aThresholdBetweenTwoCountsIsHonouredAsGiven() {
        Shaft slide = new Shaft();
        DigitalInput limit = PinSources.limitSwitch(slide, 99.5, Side.ABOVE);

        slide.counts = 99;
        assertTrue(high(limit));
        slide.counts = 100;
        assertFalse(high(limit));
    }

    @Test
    void theEncoderIsReadAtEveryReadOfThePinSoTheSwitchHasNoMemoryAndNoDelay() {
        Shaft slide = new Shaft();
        DigitalInput limit = PinSources.limitSwitch(slide, 10, Side.ABOVE);

        slide.counts = 11;
        assertFalse(limit.level(1));
        slide.counts = 9;
        assertTrue(limit.level(1), "the same simulated time, a different answer: only the shaft decides");
        assertEquals(2, slide.reads);
    }

    @Test
    void aPotentiometerIsItsVoltageAtZeroPlusTheSlopeTimesTheCountsInBothDirections() {
        Shaft arm = new Shaft();
        AnalogInput pot = PinSources.potentiometer(arm, 0.5, 0.0004);

        assertEquals(0.5, pot.volts(0), 0.0);
        arm.counts = 2500;
        assertEquals(1.5, pot.volts(0), 1e-12);
        arm.counts = -1000;
        assertEquals(0.1, pot.volts(0), 1e-12);
    }

    @Test
    void aPotentiometerNeverLeavesItsSuppliesAndOneWiredTheOtherWayRoundFalls() {
        Shaft arm = new Shaft();
        AnalogInput forward = PinSources.potentiometer(arm, 0.5, 0.001);
        AnalogInput reversed = PinSources.potentiometer(arm, 3.0, -0.001);

        arm.counts = 100_000;
        assertEquals(AnalogInput.FULL_SCALE_VOLTS, forward.volts(0), 0.0);
        assertEquals(0.0, reversed.volts(0), 0.0);
        arm.counts = -100_000;
        assertEquals(0.0, forward.volts(0), 0.0);
        assertEquals(AnalogInput.FULL_SCALE_VOLTS, reversed.volts(0), 0.0);
        arm.counts = 1000;
        assertEquals(1.5, forward.volts(0), 1e-12);
        assertEquals(2.0, reversed.volts(0), 1e-12);
    }

    @Test
    void aPinHeldAtALevelOrAVoltageNeverChanges() {
        assertTrue(PinSources.level(true).level(123));
        assertFalse(PinSources.level(false).level(456));
        assertEquals(1.65, PinSources.volts(1.65).volts(789), 0.0);
    }

    @Test
    void aSourceThatCouldNeverBeRightIsRefusedWhenItIsMadeInsteadOfSilentlyReadingWrong() {
        Shaft slide = new Shaft();

        assertThrows(IllegalArgumentException.class, () -> PinSources.limitSwitch(slide, Double.NaN, Side.ABOVE));
        assertThrows(IllegalArgumentException.class, () -> PinSources.potentiometer(slide, 0.5, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> PinSources.potentiometer(slide, Double.NaN, 0.001));
        assertThrows(IllegalArgumentException.class, () -> PinSources.volts(Double.NaN));
        assertThrows(NullPointerException.class, () -> PinSources.limitSwitch(null, 0, Side.ABOVE));
        assertThrows(NullPointerException.class, () -> PinSources.limitSwitch(slide, 0, null));
    }
}
