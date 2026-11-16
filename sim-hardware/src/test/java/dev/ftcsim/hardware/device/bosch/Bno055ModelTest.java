package dev.ftcsim.hardware.device.bosch;

import static dev.ftcsim.hardware.device.bosch.Bno055Rig.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.DeviceModels;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

/**
 * The BNO055 model at register level, read and written as the SDK's drivers do, against chassis states whose answers
 * are worked out by hand: a left turn, a nose up, a lean to the right, a spin, a push.
 */
class Bno055ModelTest {

    private static final double DEG = Math.PI / 180.0;

    private static Map<String, Object> settings(Object... keysAndValues) {
        Map<String, Object> settings = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            settings.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return settings;
    }

    /** A chassis that stands facing {@code before} and turns on the spot to face {@code after} at {@code atNanos}. */
    private static LongFunction<BodyPose> turnsAt(long atNanos, double before, double after) {
        return nanos -> pose(nanos, nanos < atNanos ? before : after, 0, 0, Vec3.ZERO, Vec3.ZERO);
    }

    private static Bno055Rig running(LongFunction<BodyPose> truth, int units) {
        Bno055Rig rig = new Bno055Rig(truth);
        rig.startIn(IMU, units);
        return rig;
    }

    private static void assertVector(int expectedX, int expectedY, int expectedZ, int tolerance, int[] actual) {
        assertEquals(expectedX, actual[0], tolerance, "x of " + java.util.Arrays.toString(actual));
        assertEquals(expectedY, actual[1], tolerance, "y of " + java.util.Arrays.toString(actual));
        assertEquals(expectedZ, actual[2], tolerance, "z of " + java.util.Arrays.toString(actual));
    }

    // ---- identity and boot -----------------------------------------------------------------------------------

    @Test
    void itIdentifiesItselfAsABno055AtTheControlHubsAddress() {
        Bno055Rig rig = new Bno055Rig(standing(0, 0, 0));
        rig.world.runTo(SECOND);

        assertEquals(0x28, rig.chip.address());
        assertEquals(0xA0, rig.readByte(CHIP_ID));
        assertEquals(0xFB, rig.readByte(ACC_ID));
        assertEquals(0x32, rig.readByte(MAG_ID));
        assertEquals(0x0F, rig.readByte(GYR_ID));
    }

    @Test
    void anAddressOrDriftSettingIsRefusedBecauseTheChipIsIdealAndAtOneAddress() {
        IllegalArgumentException address = assertThrows(
                IllegalArgumentException.class, () -> new Bno055Rig(standing(0, 0, 0), settings("address", 41)));
        IllegalArgumentException drift = assertThrows(
                IllegalArgumentException.class, () -> new Bno055Rig(standing(0, 0, 0), settings("gyroBiasRadPerSec", 0.01)));

        assertTrue(address.getMessage().contains("\"address\""), address.getMessage());
        assertTrue(drift.getMessage().contains("\"gyroBiasRadPerSec\""), drift.getMessage());
    }

    @Test
    void itIsAlreadyBootedWhenTheSimulationStartsAndIdlesInConfigMode() {
        Bno055Rig rig = new Bno055Rig(standing(0, 0, 0));

        assertEquals(0xA0, rig.readByte(CHIP_ID), "a robot's chips have been powered long before its program runs");
        assertEquals(CONFIG, rig.readByte(OPR_MODE));
        assertEquals(0, rig.readByte(SYS_STAT), "idle");
        assertEquals(0x0F, rig.readByte(ST_RESULT), "the self test passed");
        assertEquals(0, rig.readByte(SYS_ERR));
    }

    @Test
    void aResetSilencesTheChipForSixHundredAndFiftyMillisecondsAndRestoresItsDefaultsAndItsHeadingReference() {
        double[] facing = {0};
        Bno055Rig rig = new Bno055Rig(nanos -> pose(nanos, facing[0], 0, 0, Vec3.ZERO, Vec3.ZERO));
        rig.startIn(IMU, UNITS_RADIANS);
        facing[0] = 1.0;
        rig.world.runTo(2 * SECOND);
        assertEquals(UNITS_RADIANS, rig.readByte(UNIT_SEL));

        rig.write(OPR_MODE, CONFIG);
        rig.write(SYS_TRIGGER, 0x20);
        rig.world.runTo(2 * SECOND + 300 * MS);
        assertEquals(0xFF, rig.readByte(CHIP_ID), "a chip that is booting reads as all ones");
        rig.write(OPR_MODE, IMU);
        rig.world.runTo(2 * SECOND + 649 * MS);
        assertEquals(0xFF, rig.readByte(CHIP_ID));
        rig.world.runTo(2 * SECOND + 650 * MS);

        assertEquals(0xA0, rig.readByte(CHIP_ID));
        assertEquals(CONFIG, rig.readByte(OPR_MODE), "a write while it booted was lost");
        assertEquals(UNITS_DEGREES, rig.readByte(UNIT_SEL), "back to its default");
        rig.write(OPR_MODE, IMU);
        rig.world.runTo(rig.world.now() + 100 * MS);
        assertEquals(0, rig.readShort(EUL_DATA), "heading zero is where the chassis faced when the reset finished");
    }

    // ---- reference, orientation, in the units the driver selected -------------------------------------------

    @Test
    void headingIsRelativeToWhereTheChassisFacedWhenTheChipPoweredUpAndNotWhenTheModeWasEntered() {
        // The chassis faces 0.5 rad at power-up and turns to 1.5 rad before the code enters a mode.
        Bno055Rig rig = new Bno055Rig(turnsAt(SECOND, 0.5, 1.5));
        rig.startIn(IMU, UNITS_RADIANS);
        rig.world.runTo(2 * SECOND);

        // Facing 1.0 rad further counter-clockwise: a compass heading of 2 pi - 1.
        assertEquals(Math.round((2 * Math.PI - 1.0) * 900), rig.readShort(EUL_DATA), 1);
    }

    @Test
    void levelAndStillItReadsHeadingZeroFullQuaternionAndGravityAlongItsZAxis() {
        Bno055Rig rig = running(standing(0.7, 0, 0), UNITS_DEGREES);

        assertEquals(0, rig.readShort(EUL_DATA));
        assertEquals(0, rig.readShort(EUL_DATA + 2));
        assertEquals(0, rig.readShort(EUL_DATA + 4));
        assertArrayEquals(new int[] {16384, 0, 0, 0}, new int[] {
            rig.readShort(QUA_DATA), rig.readShort(QUA_DATA + 2), rig.readShort(QUA_DATA + 4), rig.readShort(QUA_DATA + 6)});
        assertVector(0, 0, 981, 1, rig.readVector(GRV_DATA));
        assertVector(0, 0, 981, 1, rig.readVector(ACC_DATA));
        assertVector(0, 0, 0, 0, rig.readVector(LIA_DATA));
    }

    @Test
    void aLeftTurnOfThirtyDegreesIsACompassHeadingOf330AndAQuaternionAboutZ() {
        Bno055Rig rig = new Bno055Rig(turnsAt(SECOND, 0, 30 * DEG));
        rig.startIn(IMU, UNITS_DEGREES);
        rig.world.runTo(2 * SECOND);

        assertEquals(330 * 16, rig.readShort(EUL_DATA), 1, "the compass heading grows clockwise");
        assertEquals(Math.cos(15 * DEG) * 16384, rig.readShort(QUA_DATA), 1);
        assertEquals(0, rig.readShort(QUA_DATA + 2), 1);
        assertEquals(0, rig.readShort(QUA_DATA + 4), 1);
        assertEquals(Math.sin(15 * DEG) * 16384, rig.readShort(QUA_DATA + 6), 1);
    }

    @Test
    void theEulerAnglesAreInRadiansWhenTheDriverSelectedThem() {
        Bno055Rig rig = new Bno055Rig(turnsAt(SECOND, 0, 30 * DEG));
        rig.startIn(IMU, UNITS_RADIANS);
        rig.world.runTo(2 * SECOND);

        assertEquals((2 * Math.PI - 30 * DEG) * 900, rig.readShort(EUL_DATA), 1);
    }

    @Test
    void aNoseUpOfTwentyDegreesIsANegativeRollAboutTheChipsYAxisAndGravityTiltsTowardsItsFrontAxis() {
        Bno055Rig rig = running(standing(0, -20 * DEG, 0), UNITS_DEGREES);

        assertEquals(0, rig.readShort(EUL_DATA), "heading is untouched");
        assertEquals(-20 * 16, rig.readShort(EUL_DATA + 2), 1, "roll: the chip's Y axis points left, the nose rose about it");
        assertEquals(0, rig.readShort(EUL_DATA + 4), 1);
        // The chip's X axis points to the back: gravity, which points up in the accelerometer's reading, is behind it.
        assertVector((int) Math.round(-Math.sin(20 * DEG) * G * 100), 0, (int) Math.round(Math.cos(20 * DEG) * G * 100), 1,
                rig.readVector(GRV_DATA));
    }

    @Test
    void aLeanOfTenDegreesToTheRightIsAPositivePitchAboutTheForwardAxis() {
        Bno055Rig rig = running(standing(0, 0, 10 * DEG), UNITS_DEGREES);

        assertEquals(0, rig.readShort(EUL_DATA + 2), 1);
        assertEquals(10 * 16, rig.readShort(EUL_DATA + 4), 1);
        assertVector(0, (int) Math.round(-Math.sin(10 * DEG) * G * 100), (int) Math.round(Math.cos(10 * DEG) * G * 100), 1,
                rig.readVector(GRV_DATA));
    }

    @Test
    void theWindowsPitchConventionReversesThePitchAngle() {
        Bno055Rig rig = running(standing(0, 0, 10 * DEG), 0x00);

        assertEquals(-10 * 16, rig.readShort(EUL_DATA + 4), 1);
    }

    @Test
    void accelerationsAreInThousandthsOfAGravityWhenTheDriverSelectedThem() {
        Bno055Rig rig = running(standing(0, 0, 0), UNITS_DEGREES | 1);

        assertVector(0, 0, 1000, 1, rig.readVector(GRV_DATA));
    }

    @Test
    void theTemperatureIsInCelsiusOrInTwoFahrenheitDegreeSteps() {
        Bno055Rig celsius = running(standing(0, 0, 0), UNITS_DEGREES);
        Bno055Rig fahrenheit = running(standing(0, 0, 0), UNITS_DEGREES | 0x10);

        assertEquals(30, celsius.readByte(TEMP));
        assertEquals(43, fahrenheit.readByte(TEMP), "86 degrees Fahrenheit, two to the least significant bit");
    }

    // ---- motion ------------------------------------------------------------------------------------------------

    @Test
    void theGyroscopeReadsTheTrueSpinInTheChipsAxesAndUnits() {
        LongFunction<BodyPose> spinning = nanos -> pose(nanos, 0, 0, 0, Vec3.ZERO, new Vec3(0, 0, 0.5));
        Bno055Rig degrees = running(spinning, UNITS_DEGREES);
        Bno055Rig radians = running(spinning, UNITS_RADIANS);

        assertVector(0, 0, (int) Math.round(0.5 / DEG * 16), 1, degrees.readVector(GYR_DATA));
        assertVector(0, 0, 450, 1, radians.readVector(GYR_DATA));
    }

    @Test
    void aTippingChassisIsSeenAboutTheChipsOwnAxesWhichPointBackwardAndRight() {
        // Pitching nose-down at 1 rad/s is a spin about the chassis' left axis, which is minus the chip's Y axis.
        LongFunction<BodyPose> pitchingDown = nanos -> pose(nanos, 0, 0, 0, Vec3.ZERO, new Vec3(0, 1.0, 0));
        Bno055Rig rig = running(pitchingDown, UNITS_RADIANS);

        assertVector(0, -900, 0, 1, rig.readVector(GYR_DATA));
    }

    @Test
    void theGyroscopeSaturatesAtTheRangeTheDriverConfigured() {
        LongFunction<BodyPose> spinning = nanos -> pose(nanos, 0, 0, 0, Vec3.ZERO, new Vec3(0, 0, 10.0));
        Bno055Rig rig = new Bno055Rig(spinning);
        rig.world.runTo(700 * MS);
        rig.write(PAGE_ID, 1);
        rig.write(GYR_CONFIG_0, 0x38 | 3);
        rig.write(PAGE_ID, 0);
        rig.write(UNIT_SEL, UNITS_DEGREES);
        rig.write(OPR_MODE, IMU);
        rig.world.runTo(rig.world.now() + 100 * MS);

        assertVector(0, 0, 250 * 16, 0, rig.readVector(GYR_DATA));
    }

    @Test
    void aChassisThatSpeedsUpShowsItsAccelerationAsLinearAccelerationAndInTheAccelerometer() {
        // Two metres per second squared forward from one second on: the chip's X axis points backward.
        LongFunction<BodyPose> pushed = nanos -> {
            double t = Math.max(0, nanos / 1e9 - 1.0);
            return pose(nanos, 0, 0, 0, new Vec3(2.0 * t, 0, 0), Vec3.ZERO);
        };
        Bno055Rig rig = new Bno055Rig(pushed);
        rig.startIn(IMU, UNITS_DEGREES);
        rig.world.runTo(2 * SECOND);

        assertVector(-200, 0, 0, 2, rig.readVector(LIA_DATA));
        assertVector(-200, 0, 981, 2, rig.readVector(ACC_DATA));
        assertVector(0, 0, 981, 1, rig.readVector(GRV_DATA));
    }

    @Test
    void theOutputsUpdateAt100HzAndHoldBetweenUpdates() {
        LongFunction<BodyPose> spinning = nanos -> pose(nanos, nanos / 1e9, 0, 0, Vec3.ZERO, new Vec3(0, 0, 1.0));
        Bno055Rig rig = new Bno055Rig(spinning);
        rig.startIn(IMU, UNITS_DEGREES);

        Set<Integer> distinct = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            rig.world.runTo(rig.world.now() + MS);
            distinct.add(rig.readShort(EUL_DATA));
        }

        assertTrue(distinct.size() >= 9 && distinct.size() <= 11, "about one value in every 10 ms: " + distinct.size());
    }

    // ---- modes and what only CONFIG mode may change --------------------------------------------------------

    @Test
    void afterEnteringAFusionModeTheOutputsReadZeroFor50MillisecondsAndThenAreValid() {
        Bno055Rig rig = new Bno055Rig(standing(0, 0, 0));
        rig.world.runTo(700 * MS);
        rig.write(UNIT_SEL, UNITS_DEGREES);
        rig.write(OPR_MODE, IMU);

        assertEquals(IMU, rig.readByte(OPR_MODE));
        assertEquals(5, rig.readByte(SYS_STAT), "running fusion");
        rig.world.runTo(740 * MS);
        assertArrayEquals(new byte[8], rig.read(QUA_DATA, 8), "all zeros is what the SDK driver treats as not yet valid");
        rig.world.runTo(760 * MS);
        assertEquals(16384, rig.readShort(QUA_DATA));
    }

    @Test
    void aModeWithoutFusionRunsOnlyItsSensorsAndReportsRunningWithoutFusion() {
        Bno055Rig accelerometerOnly = new Bno055Rig(standing(0, 0, 0));
        accelerometerOnly.startIn(ACCONLY, UNITS_DEGREES);
        Bno055Rig gyroOnly = new Bno055Rig(standing(0, 0, 0));
        gyroOnly.startIn(GYRONLY, UNITS_DEGREES);

        assertEquals(6, accelerometerOnly.readByte(SYS_STAT));
        assertVector(0, 0, 981, 1, accelerometerOnly.readVector(ACC_DATA));
        assertArrayEquals(new byte[8], accelerometerOnly.read(QUA_DATA, 8), "no fusion, no orientation");
        assertVector(0, 0, 0, 0, gyroOnly.readVector(ACC_DATA));
    }

    @Test
    void theUnitsCanOnlyBeChangedInConfigMode() {
        Bno055Rig rig = running(standing(0, 0, 0), UNITS_DEGREES);

        rig.write(UNIT_SEL, UNITS_RADIANS);
        assertEquals(UNITS_DEGREES, rig.readByte(UNIT_SEL), "the write was ignored in IMU mode");
        rig.write(OPR_MODE, CONFIG);
        rig.write(UNIT_SEL, UNITS_RADIANS);
        assertEquals(UNITS_RADIANS, rig.readByte(UNIT_SEL));
        assertEquals(0, rig.readByte(TEMP), "sensors are suspended in CONFIG mode");
    }

    @Test
    void leavingAndReenteringAFusionModeKeepsTheHeadingReference() {
        Bno055Rig rig = new Bno055Rig(turnsAt(SECOND, 0, 1.0));
        rig.startIn(IMU, UNITS_RADIANS);
        rig.world.runTo(2 * SECOND);
        int before = rig.readShort(EUL_DATA);

        rig.write(OPR_MODE, CONFIG);
        rig.write(OPR_MODE, IMU);
        rig.world.runTo(rig.world.now() + 100 * MS);

        assertEquals(before, rig.readShort(EUL_DATA), "the reference belongs to the powered chip, not to the mode");
    }

    @Test
    void calibrationLevelsRiseWithTimeInTheModeAndTheMagnetometerNeverCalibrates() {
        Bno055Rig rig = running(standing(0, 0, 0), UNITS_DEGREES);

        assertEquals(0, rig.readByte(CALIB_STAT) >> 4, "nothing is calibrated a moment after the start");
        rig.world.runTo(rig.world.now() + SECOND);
        assertEquals(0x10, rig.readByte(CALIB_STAT), "1.1 s in: the gyroscope is at level 1 of 3, the rest at 0");
        rig.world.runTo(rig.world.now() + 6 * SECOND);
        assertEquals(0xFC, rig.readByte(CALIB_STAT), "system, gyroscope and accelerometer at 3, magnetometer at 0");
    }

    // ---- mounting and remapping ------------------------------------------------------------------------------

    @Test
    void theBoardsMountingDecidesWhichOfTheChipsAxesGravityLiesAlong() {
        // The chip's Z axis is along the logo, its Y axis is port x logo, its X axis is opposite to the port.
        assertGravity("DOWN", "FORWARD", 0, 0, -981);
        assertGravity("FORWARD", "UP", -981, 0, 0);
        assertGravity("LEFT", "UP", -981, 0, 0);
        assertGravity("RIGHT", "FORWARD", 0, -981, 0);
        assertGravity("BACKWARD", "DOWN", 981, 0, 0);
    }

    private static void assertGravity(String logo, String port, int x, int y, int z) {
        Bno055Rig rig = new Bno055Rig(standing(0, 0, 0), settings("mount.logo", logo, "mount.port", port));
        rig.startIn(IMU, UNITS_DEGREES);

        assertVector(x, y, z, 1, rig.readVector(GRV_DATA));
    }

    @Test
    void aMountThatTheSdkCallsPhysicallyImpossibleIsRefusedAndSoIsAMisspeltOne() {
        IllegalArgumentException same = assertThrows(IllegalArgumentException.class,
                () -> new Bno055Rig(standing(0, 0, 0), settings("mount.logo", "UP", "mount.port", "DOWN")));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> new Bno055Rig(standing(0, 0, 0), settings("mount.logo", "SIDEWAYS")));
        IllegalArgumentException misspelt = assertThrows(IllegalArgumentException.class,
                () -> new Bno055Rig(standing(0, 0, 0), settings("mount.usb", "FORWARD")));

        assertTrue(same.getMessage().contains("mount.port"), same.getMessage());
        assertTrue(unknown.getMessage().contains("mount.logo") && unknown.getMessage().contains("BACKWARD"), unknown.getMessage());
        assertTrue(misspelt.getMessage().contains("\"mount.usb\""), misspelt.getMessage());
    }

    @Test
    void theAxisRemapRegistersTurnTheChipsOutputAxes() {
        Bno055Rig rig = new Bno055Rig(standing(0, -20 * DEG, 0));
        rig.world.runTo(700 * MS);
        // The remap the datasheet calls placement 0: X and Y exchanged.
        rig.write(AXIS_MAP_CONFIG, 0x21);
        rig.write(AXIS_MAP_SIGN, 0x00);
        rig.write(UNIT_SEL, UNITS_DEGREES);
        rig.write(OPR_MODE, IMU);
        rig.world.runTo(rig.world.now() + 100 * MS);

        assertVector(0, (int) Math.round(-Math.sin(20 * DEG) * G * 100), (int) Math.round(Math.cos(20 * DEG) * G * 100), 1,
                rig.readVector(GRV_DATA));
    }

    @Test
    void aChipThatNobodyUsesLooksAtTheWorldWhenItIsBuiltAndAtItsFirstStepAndNoMore() {
        AtomicInteger looks = new AtomicInteger();
        Bno055Rig rig = new Bno055Rig(nanos -> {
            looks.incrementAndGet();
            return pose(nanos, 0, 0, 0, Vec3.ZERO, Vec3.ZERO);
        });

        rig.world.runTo(20 * SECOND);

        assertEquals(2, looks.get(), "once when it was built and once at the first step, for the heading reference");
    }

    @Test
    void aChipInUseLooksAtTheWorldAtEveryPhysicsStep() {
        AtomicInteger looks = new AtomicInteger();
        Bno055Rig rig = new Bno055Rig(nanos -> {
            looks.incrementAndGet();
            return pose(nanos, 0, 0, 0, Vec3.ZERO, Vec3.ZERO);
        });
        rig.startIn(IMU, UNITS_DEGREES);
        int before = looks.get();

        rig.world.runTo(rig.world.now() + 10 * SECOND);

        assertEquals(2400, looks.get() - before, 2);
    }

    @Test
    void anOutputThatIsOverdueBecauseTheWorldCannotStepIsLatchedWhenTheChipIsReadFromTheLatestSampleOfTheWorld() {
        // The chassis faces 0.5 rad at power-up and 1.5 rad from 1 s on; the chip is in use from 0.7 s.
        Bno055Rig rig = new Bno055Rig(turnsAt(SECOND, 0.5, 1.5));
        rig.startIn(IMU, UNITS_RADIANS);
        rig.world.runTo(SECOND + 50 * MS);
        long baton = rig.world.now();

        // Robot code now holds the baton: its clock moves, no physics step runs. It enters the mode again and polls.
        rig.chip.write(new byte[] {(byte) OPR_MODE, (byte) CONFIG}, baton);
        rig.chip.write(new byte[] {(byte) OPR_MODE, (byte) IMU}, baton + 10 * MS);
        byte[] tooSoon = rig.chip.writeRead(new byte[] {(byte) QUA_DATA}, 8, baton + 55 * MS);
        byte[] polled = rig.chip.writeRead(new byte[] {(byte) QUA_DATA}, 8, baton + 71 * MS);
        byte[] again = rig.chip.writeRead(new byte[] {(byte) QUA_DATA}, 8, baton + 74 * MS);

        assertArrayEquals(new byte[8], tooSoon, "nothing in its first 50 ms, whether the world steps or not");
        // 1.0 rad to the left of where it faced at power-up: w = cos 0.5, z = sin 0.5.
        assertEquals(Math.round(Math.cos(0.5) * 16384), (short) ((polled[0] & 0xFF) | polled[1] << 8), 1);
        assertEquals(Math.round(Math.sin(0.5) * 16384), (short) ((polled[6] & 0xFF) | polled[7] << 8), 1);
        assertArrayEquals(polled, again, "the output holds until its next period");
    }

    @Test
    void readingTheChipNeverChangesWhatItDoes() {
        LongFunction<BodyPose> spinning = nanos -> pose(nanos, nanos / 2e9, 0, 0.1, Vec3.ZERO, new Vec3(0, 0, 0.5));
        Bno055Rig quiet = new Bno055Rig(spinning);
        Bno055Rig busy = new Bno055Rig(spinning);
        quiet.startIn(IMU, UNITS_RADIANS);
        busy.startIn(IMU, UNITS_RADIANS);

        for (int i = 0; i < 500; i++) {
            busy.world.runTo(busy.world.now() + 3 * MS + i);
            busy.read(QUA_DATA, 8);
            busy.read(EUL_DATA, 6);
        }
        quiet.world.runTo(busy.world.now());

        assertArrayEquals(quiet.read(ACC_DATA, 0x34 - ACC_DATA), busy.read(ACC_DATA, 0x34 - ACC_DATA));
    }

    // ---- the register pages ------------------------------------------------------------------------------------

    @Test
    void theSecondPageHoldsTheSensorConfigurationAndAUniqueId() {
        Bno055Rig rig = new Bno055Rig(standing(0, 0, 0));
        rig.world.runTo(700 * MS);

        rig.write(PAGE_ID, 1);
        assertEquals(1, rig.readByte(PAGE_ID));
        assertEquals(0x38, rig.readByte(GYR_CONFIG_0), "the gyroscope defaults to 2000 degrees per second and 32 Hz");
        assertEquals(0x0D, rig.readByte(ACC_CONFIG));
        assertNotEquals(0, rig.read(UNIQUE_ID_FIRST, 16)[3]);
        rig.write(PAGE_ID, 0);
        assertEquals(0xA0, rig.readByte(CHIP_ID));
    }

    @Test
    void calibrationOffsetsWrittenInConfigModeCanBeReadBack() {
        Bno055Rig rig = new Bno055Rig(standing(0, 0, 0));
        rig.world.runTo(700 * MS);

        rig.write(ACC_OFFSET_X_LSB, 0x34, 0x12, 0x78, 0x56);

        assertEquals(0x1234, rig.readShort(ACC_OFFSET_X_LSB));
        assertEquals(0x5678, rig.readShort(ACC_OFFSET_X_LSB + 2));
    }

    @Test
    void theRegistryBuildsItAndRejectsASettingNobodyReads() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> DeviceModels.standard().create(DeviceModels.BNO055_IMU, "imu", Map.of("gyroBias", 1), new Bno055Rig(standing(0, 0, 0)).world.host));

        assertTrue(refused.getMessage().contains("\"gyroBias\""), refused.getMessage());
        assertTrue(refused.getMessage().contains("mount.logo"), "it says what the chip does read: " + refused.getMessage());
    }
}
