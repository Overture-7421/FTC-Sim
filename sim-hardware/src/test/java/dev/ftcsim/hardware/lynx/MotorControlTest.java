package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The motor control law against a DC motor plant: power, braking, velocity and position regulation. */
class MotorControlTest {

    private static final double FULL_POWER = 32767.0;
    private static final int TARGET_CPS = 1500;

    private final Rig rig = new Rig();
    private final HubCalls hub = new HubCalls(rig.client);

    @Test
    void openLoopPowerBecomesThatShareOfTheBatteryVoltageAtTheTerminals() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.enable(0, true);

        hub.setPower(0, 32767);
        rig.runMillis(1);
        assertEquals(12.0, plant.volts(), 1e-9);

        rig.batteryVolts[0] = 10.0;
        hub.setPower(0, -16384);
        rig.runMillis(1);
        assertEquals(-16384 / FULL_POWER * 10.0, plant.volts(), 1e-9);
        assertFalse(plant.isFloating());
    }

    @Test
    void aPowerOnPortDrivesNothingUntilItIsEnabled() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setPower(0, 32767);

        rig.runMillis(5);
        assertTrue(plant.isFloating());

        hub.enable(0, true);
        rig.runMillis(1);
        assertEquals(12.0, plant.volts(), 1e-9);

        hub.enable(0, false);
        rig.runMillis(1);
        assertTrue(plant.isFloating());
    }

    @Test
    void zeroPowerEitherShortsTheTerminalsOrLeavesThemOpenAsConfigured() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.enable(0, true);
        hub.setPower(0, 16384);
        rig.runMillis(10);

        hub.setPower(0, 0);
        rig.runMillis(1);
        assertFalse(plant.isFloating());
        assertEquals(0.0, plant.volts());

        hub.setMode(0, HubCalls.OPEN_LOOP, true);
        rig.runMillis(1);
        assertTrue(plant.isFloating());

        hub.setMode(0, HubCalls.OPEN_LOOP, false);
        rig.runMillis(1);
        assertFalse(plant.isFloating());
    }

    @Test
    void brakingStopsTheShaftMuchFasterThanFloating() {
        DcMotorPlant braked = rig.plugGoBilda(0);
        DcMotorPlant floated = rig.plugGoBilda(1);
        hub.setMode(1, HubCalls.OPEN_LOOP, true);
        for (int motor = 0; motor < 2; motor++) {
            hub.enable(motor, true);
            hub.setPower(motor, 16384);
        }
        rig.runMillis(500);
        double cruising = braked.encoderCountsPerSecond();
        assertEquals(cruising, floated.encoderCountsPerSecond(), 1e-6);

        hub.setPower(0, 0);
        hub.setPower(1, 0);
        rig.runMillis(100);

        assertTrue(braked.encoderCountsPerSecond() < 0.35 * cruising, "braked: " + braked.encoderCountsPerSecond());
        assertTrue(floated.encoderCountsPerSecond() > 0.9 * cruising, "floated: " + floated.encoderCountsPerSecond());
    }

    @Test
    void velocityModeWithThePowerOnGainsConvergesWellDamped() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setMode(0, HubCalls.VELOCITY, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.enable(0, true);

        Response response = record(plant, 2000);

        assertTrue(response.peak < 1.15 * TARGET_CPS, "overshoot: peak " + response.peak);
        assertTrue(response.settledAtMillis(TARGET_CPS, 0.02) <= 500, "settled at " + response.settledAtMillis(TARGET_CPS, 0.02));
        assertTrue(response.maxDeviation(800, TARGET_CPS) < 0.005 * TARGET_CPS, "still moving: " + response.maxDeviation(800, TARGET_CPS));
    }

    @Test
    void revsTuningRecipeTracksTheTargetQuicklyWithLittleOvershoot() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        double maxVelocity = plant.settlingCountsPerSecond(12.0);
        double f = FULL_POWER / maxVelocity;
        hub.setPidf(0, HubCalls.VELOCITY, 0.1 * f, 0.01 * f, 0, f, HubCalls.PIDF);
        hub.setMode(0, HubCalls.VELOCITY, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.enable(0, true);

        Response response = record(plant, 1000);

        assertTrue(response.peak < 1.06 * TARGET_CPS, "overshoot: peak " + response.peak);
        assertTrue(response.settledAtMillis(TARGET_CPS, 0.05) <= 200, "settled at " + response.settledAtMillis(TARGET_CPS, 0.05));
    }

    @Test
    void theFeedForwardTermAloneIsExactAtTheVelocityItWasComputedFrom() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        double f = FULL_POWER / plant.settlingCountsPerSecond(12.0);
        hub.setPidf(0, HubCalls.VELOCITY, 0, 0, 0, f, HubCalls.PIDF);
        hub.setMode(0, HubCalls.VELOCITY, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.enable(0, true);

        rig.runMillis(1500);

        assertEquals(TARGET_CPS, plant.encoderCountsPerSecond(), 0.002 * TARGET_CPS);
    }

    @Test
    void theLegacyAlgorithmIgnoresTheFeedForwardTerm() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setPidf(0, HubCalls.VELOCITY, 0, 0, 0, 12.0, HubCalls.LEGACY_PID);
        hub.setMode(0, HubCalls.VELOCITY, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.enable(0, true);

        rig.runMillis(500);

        assertEquals(0.0, plant.encoderCountsPerSecond(), 1e-9);
    }

    @Test
    void integralActionCarriesTheTargetThroughABatterySag() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setMode(0, HubCalls.VELOCITY, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.enable(0, true);
        rig.runMillis(1000);

        rig.batteryVolts[0] = 9.5;
        rig.runMillis(2000);

        assertEquals(TARGET_CPS, plant.encoderCountsPerSecond(), 0.01 * TARGET_CPS);
    }

    @Test
    void runToPositionSettlesInsideTheToleranceWithoutExceedingItsSpeedLimit() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setTarget(0, 1000, 5);
        hub.setMode(0, HubCalls.POSITION, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.enable(0, true);
        assertFalse(hub.bulk().atTarget(0));

        double fastest = 0;
        long furthest = 0;
        for (int ms = 0; ms < 3000; ms++) {
            rig.runMillis(1);
            fastest = Math.max(fastest, plant.encoderCountsPerSecond());
            furthest = Math.max(furthest, plant.encoderCounts());
        }

        assertTrue(fastest <= 1.1 * TARGET_CPS, "fastest " + fastest);
        assertTrue(fastest > 0.9 * TARGET_CPS, "never reached the limit: " + fastest);
        assertTrue(furthest <= 1000 + 15, "overshot to " + furthest);
        assertEquals(1000, hub.position(0), 5);
        assertTrue(hub.bulk().atTarget(0));
    }

    @Test
    void atTargetFollowsTheToleranceWhileTheMotorIsStillMoving() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setTarget(0, 1000, 100);
        hub.setMode(0, HubCalls.POSITION, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.enable(0, true);

        int millis = 0;
        while (!hub.bulk().atTarget(0)) {
            rig.runMillis(1);
            assertTrue(++millis < 3000, "never arrived");
        }

        assertTrue(plant.encoderCounts() >= 900 && plant.encoderCounts() < 1000, "position " + plant.encoderCounts());
        assertTrue(plant.encoderCountsPerSecond() > 200, "arrived while moving: " + plant.encoderCountsPerSecond());
        assertEquals(1, rig.client.response(DekaCommand.IS_MOTOR_AT_TARGET, Bytes.of().u8(0).build()).get());
    }

    @Test
    void runToPositionHoldsTheTargetAgainstAnImmediateReversal() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setMode(0, HubCalls.POSITION, false);
        hub.setVelocity(0, TARGET_CPS);
        hub.setTarget(0, 600, 5);
        hub.enable(0, true);
        rig.runMillis(2000);

        hub.setTarget(0, -200, 5);
        rig.runMillis(2500);

        assertEquals(-200, hub.position(0), 5);
        assertTrue(Math.abs(plant.encoderCountsPerSecond()) < 20);
    }

    @Test
    void aZeroSpeedLimitKeepsPositionModeFromMovingTowardItsTarget() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.setMode(0, HubCalls.POSITION, false);
        hub.setTarget(0, 1000, 5);
        hub.setVelocity(0, 0);
        hub.enable(0, true);

        rig.runMillis(500);

        assertEquals(0, plant.encoderCounts(), 1);
    }

    @Test
    void resettingTheEncoderZeroesTheCountAndLeavesTheMotionAlone() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.enable(0, true);
        hub.setPower(0, 16384);
        rig.runMillis(500);
        int before = hub.position(0);
        double velocity = plant.encoderCountsPerSecond();
        assertTrue(before > 300);

        hub.resetEncoder(0);

        assertEquals(0, hub.position(0));
        assertEquals(velocity, plant.encoderCountsPerSecond(), 1e-9);
        rig.runMillis(100);
        assertEquals(velocity * 0.1, hub.position(0), velocity * 0.01);
    }

    @Test
    void overCurrentFollowsTheAlertLevelAgainstTheMotorsCurrent() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.enable(0, true);
        hub.setPower(0, 32767);
        rig.runMillis(2);
        assertTrue(plant.currentAmps() > 8.0);
        assertEquals(plant.currentAmps() * 1000.0, hub.adc(8), 1.0);
        assertFalse(hub.bulk().overCurrent(0), "the default alert level is 10 A");

        rig.client.ack(DekaCommand.SET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, Bytes.of().u8(0).u16(5000).build());
        assertTrue(hub.bulk().overCurrent(0));

        rig.runMillis(1500);
        assertTrue(plant.currentAmps() < 0.5);
        assertFalse(hub.bulk().overCurrent(0));
        rig.client.ack(DekaCommand.SET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, Bytes.of().u8(0).u16(50).build());
        assertTrue(hub.bulk().overCurrent(0));
    }

    @Test
    void failSafeDeEnergisesMotorsAtOnceAndTheyStayOffUntilEnabledAgain() {
        DcMotorPlant plant = rig.plugGoBilda(0);
        hub.enable(0, true);
        hub.setPower(0, 32767);
        rig.runMillis(20);
        assertFalse(plant.isFloating());

        rig.client.standard(StandardCommand.FAIL_SAFE, Bytes.none());

        assertTrue(plant.isFloating());
        rig.runMillis(20);
        assertTrue(plant.isFloating());
        assertEquals(0, rig.client.response(DekaCommand.GET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).build()).get());
        assertEquals(0, rig.client.response(DekaCommand.GET_MOTOR_CONSTANT_POWER, Bytes.of().u8(0).build()).getShort());

        hub.enable(0, true);
        hub.setPower(0, 8192);
        rig.runMillis(1);
        assertEquals(8192 / FULL_POWER * 12.0, plant.volts(), 1e-9);
    }

    @Test
    void anOpenPortReadsAsZeroAndIsReportedOnlyOnceItIsDriven() {
        hub.enable(2, true);
        hub.setPower(2, 0);
        rig.runMillis(5);
        assertTrue(rig.hub.unboundMotorPorts().isEmpty());

        hub.setPower(2, 20000);
        rig.runMillis(5);

        assertEquals(List.of(2), rig.hub.unboundMotorPorts());
        assertEquals(0, hub.position(2));
        HubCalls.Bulk bulk = hub.bulk();
        assertEquals(0, bulk.encoders()[2]);
        assertEquals(0, bulk.velocities()[2]);
        assertEquals(0, hub.adc(10));
    }

    @Test
    void positionModeOnAnOpenPortNeverArrives() {
        hub.setMode(1, HubCalls.POSITION, false);
        hub.setTarget(1, 500, 5);
        hub.setVelocity(1, 1000);
        hub.enable(1, true);

        rig.runMillis(1000);

        assertFalse(hub.bulk().atTarget(1));
        assertEquals(List.of(1), rig.hub.unboundMotorPorts());
    }

    @Test
    void aPortCanHoldOnlyOnePlant() {
        rig.plugGoBilda(0);

        assertThrows(IllegalStateException.class, () -> rig.hub.motorPort(0).connect(DcMotorPlant.goBilda435(0)));
        assertThrows(IllegalArgumentException.class, () -> rig.hub.motorPort(4));
    }

    private Response record(DcMotorPlant plant, int millis) {
        double[] cps = new double[millis];
        for (int ms = 0; ms < millis; ms++) {
            rig.runMillis(1);
            cps[ms] = plant.encoderCountsPerSecond();
        }
        return new Response(cps);
    }

    /** The velocity of a motor at the end of every millisecond. */
    private record Response(double[] cps, double peak) {

        Response(double[] cps) {
            this(cps, Arrays.stream(cps).max().orElse(0));
        }

        /** The first millisecond after which the velocity stays within the band around the target. */
        int settledAtMillis(double target, double band) {
            int settled = cps.length;
            for (int ms = cps.length - 1; ms >= 0 && Math.abs(cps[ms] - target) <= band * target; ms--) {
                settled = ms;
            }
            return settled;
        }

        /** The largest distance from the target from the given millisecond on. */
        double maxDeviation(int fromMillis, double target) {
            double worst = 0;
            for (int ms = fromMillis; ms < cps.length; ms++) {
                worst = Math.max(worst, Math.abs(cps[ms] - target));
            }
            return worst;
        }
    }
}
