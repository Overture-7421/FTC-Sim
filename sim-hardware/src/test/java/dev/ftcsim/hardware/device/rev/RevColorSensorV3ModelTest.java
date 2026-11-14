package dev.ftcsim.hardware.device.rev;

import static dev.ftcsim.hardware.device.WorldRig.MS;
import static dev.ftcsim.hardware.device.WorldRig.SECOND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RevColorSensorV3ModelTest {

    private static final int MAIN_CTRL = 0x00;
    private static final int PS_LED = 0x01;
    private static final int PS_PULSES = 0x02;
    private static final int PS_MEAS_RATE = 0x03;
    private static final int LS_MEAS_RATE = 0x04;
    private static final int LS_GAIN = 0x05;
    private static final int PART_ID = 0x06;
    private static final int MAIN_STATUS = 0x07;
    private static final int PS_DATA = 0x08;
    private static final int LS_DATA_IR = 0x0A;
    private static final int LS_DATA_GREEN = 0x0D;
    private static final int LS_DATA_BLUE = 0x10;
    private static final int LS_DATA_RED = 0x13;

    /** A colour whose reflectivity is the 0.8 of the card stock that the SDK's distance fit was made with. */
    private static final int CARD = 0xCCCCCC;
    private static final double METRES_PER_INCH = 0.0254;

    private final FlatWall wall = new FlatWall(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), CARD);

    /** A sensor on the origin of a chassis that stands still, looking straight ahead at {@link #wall}. */
    private WorldRig rig;
    private I2cPeripheral chip;

    private void mount(Map<String, Object> settings, ScriptedBody body, RayView rays) {
        rig = new WorldRig(body, rays);
        chip = DeviceModels.standard().create("rev-color-sensor-v3", "upSensor", settings, rig.host);
    }

    private void mountAtOrigin() {
        mount(sensorAt(0, 0, 0), ScriptedBody.startingAt(0, 0, 0).build(), wall);
    }

    private static Map<String, Object> sensorAt(double x, double y, double z) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("x", x);
        settings.put("y", y);
        settings.put("z", z);
        return settings;
    }

    private void write(int register, int value) {
        chip.write(new byte[] {(byte) register, (byte) value}, rig.now());
    }

    private byte[] read(int register, int count) {
        return chip.writeRead(new byte[] {(byte) register}, count, rig.now());
    }

    /** What the SDK's RevColorSensorV3 writes when it starts the sensor. */
    private void startLikeTheSdk() {
        write(PS_LED, (3 << 4) | 7);
        write(PS_PULSES, 32);
        write(PS_MEAS_RATE, (3 << 3) | 5);
        write(LS_MEAS_RATE, (4 << 4) | 2);
        write(LS_GAIN, 1);
        write(MAIN_CTRL, 0x07);
    }

    private int proximity() {
        byte[] data = read(PS_DATA, 2);
        return ((data[0] & 0xFF) | (data[1] & 0xFF) << 8) & 0x7FF;
    }

    private boolean overflow() {
        return (read(PS_DATA + 1, 1)[0] & 0x08) != 0;
    }

    private int light(int register) {
        byte[] data = read(register, 2);
        return (data[0] & 0xFF) | (data[1] & 0xFF) << 8;
    }

    /** The SDK's own conversion from counts to inches, transcribed from RevColorSensorV3. */
    private static double sdkInches(int counts) {
        if (counts <= 26.980) {
            return 6.0;
        }
        return Math.min(Math.pow((counts - 26.980) / 325.961, -0.75934), 6.0);
    }

    /** Puts the wall the given number of metres in front of the sensor and lets two measurements go by. */
    private void measureWallAt(double metres) {
        wall.moveTo(new Vec3(metres, 0, 0));
        rig.runTo(rig.now() + 250 * MS);
    }

    @Test
    void itIdentifiesItselfAndReportsAPowerOnOnlyOnce() {
        mountAtOrigin();

        assertEquals(0x52, chip.address());
        assertEquals(0xC2, read(PART_ID, 1)[0] & 0xFF);
        assertEquals(0x20, read(MAIN_STATUS, 1)[0] & 0x20);
        assertEquals(0, read(MAIN_STATUS, 1)[0] & 0x20);
    }

    @Test
    void untilItIsEnabledItMeasuresNothing() {
        mountAtOrigin();
        measureWallAt(0.02);

        assertEquals(0, proximity());
        assertEquals(0, light(LS_DATA_GREEN));
    }

    @Test
    void withTheSdksSettingsACardReadsItsTrueDistanceThroughTheSdksOwnConversion() {
        mountAtOrigin();
        startLikeTheSdk();

        for (double inches : new double[] {0.6, 1, 2, 3, 4}) {
            measureWallAt(inches * METRES_PER_INCH);
            assertEquals(inches, sdkInches(proximity()), 0.04 * inches, "at " + inches + " inches");
        }
    }

    @Test
    void aSurfaceAlmostTouchingTheSensorSaturatesItAndSetsTheOverflowBit() {
        mountAtOrigin();
        startLikeTheSdk();

        measureWallAt(0.003);

        assertEquals(2047, proximity());
        assertTrue(overflow());
        assertEquals(0.25, sdkInches(proximity()), 0.01);
    }

    @Test
    void nothingWithinRangeReadsTheOffsetTheSdkConvertsToItsMaximumDistance() {
        mountAtOrigin();
        startLikeTheSdk();

        measureWallAt(0.5);

        assertEquals(27, proximity());
        assertEquals(6.0, sdkInches(proximity()), 0.0);
        assertFalse(overflow());
    }

    @Test
    void aDarkSurfaceReadsFartherThanAWhiteOneAndBlackReadsNothing() {
        mountAtOrigin();
        startLikeTheSdk();

        wall.paint(0xFFFFFF);
        measureWallAt(0.03);
        int white = proximity();
        wall.paint(0x6A3D9A);
        measureWallAt(0.03);
        int purple = proximity();
        wall.paint(0x000000);
        measureWallAt(0.01);
        int black = proximity();

        assertTrue(white > purple, white + " vs " + purple);
        assertTrue(purple > 27 + 20, "a coloured plastic still reflects: " + purple);
        assertEquals(27, black);
    }

    @Test
    void thePulseCountTheLedCurrentAndTheResolutionScaleTheSignal() {
        mountAtOrigin();
        startLikeTheSdk();
        measureWallAt(0.03);
        int normal = proximity() - 27;

        write(PS_PULSES, 16);
        measureWallAt(0.03);
        int halfPulses = proximity() - 27;
        write(PS_PULSES, 32);
        write(PS_LED, (3 << 4) | 6);
        measureWallAt(0.03);
        int lessCurrent = proximity() - 27;
        write(PS_LED, (3 << 4) | 7);
        write(PS_MEAS_RATE, (0 << 3) | 5);
        measureWallAt(0.03);
        int eightBit = proximity();

        assertEquals(normal / 2.0, halfPulses, 1.5);
        assertEquals(normal * 100.0 / 125.0, lessCurrent, 1.5);
        assertEquals((normal + 27) / 8.0, eightBit, 1.0);
    }

    @Test
    void theDataRegistersHoldTheLastMeasurementUntilTheNextOneIsDue() {
        mountAtOrigin();
        wall.moveTo(new Vec3(0.05, 0, 0));
        startLikeTheSdk();

        rig.runTo(99 * MS);
        assertEquals(0, read(MAIN_STATUS, 1)[0] & 0x09, "nothing is ready before the first period is over");
        assertEquals(0, proximity());
        rig.runTo(100 * MS);
        int first = proximity();
        assertTrue(first > 60, "the first measurement arrives with the period: " + first);
        wall.moveTo(new Vec3(0.01, 0, 0));
        rig.runTo(190 * MS);
        assertEquals(first, proximity(), "the wall moved but no measurement has been made since");
        rig.runTo(200 * MS);

        assertTrue(proximity() > first + 100);
    }

    @Test
    void theStatusRegisterSaysNewDataIsReadyOnceAndForgetsWhenItIsRead() {
        mountAtOrigin();
        startLikeTheSdk();
        read(MAIN_STATUS, 1);

        rig.runTo(110 * MS);
        int ready = read(MAIN_STATUS, 1)[0];
        int afterReading = read(MAIN_STATUS, 1)[0];

        assertEquals(0x09, ready & 0x09, "proximity and light data are ready");
        assertEquals(0, afterReading & 0x09);
    }

    @Test
    void aRedSurfaceIsRedAndAWhiteOneBalancesOnceTheSdkHasScaledTheChannels() {
        mountAtOrigin();
        startLikeTheSdk();

        wall.paint(0xFF0000);
        measureWallAt(0.01);
        double red = light(LS_DATA_RED) * 1.07;
        double green = light(LS_DATA_GREEN);
        double blue = light(LS_DATA_BLUE) * 1.55;
        wall.paint(0xFFFFFF);
        measureWallAt(0.01);
        double whiteRed = light(LS_DATA_RED) * 1.07;
        double whiteGreen = light(LS_DATA_GREEN);
        double whiteBlue = light(LS_DATA_BLUE) * 1.55;

        assertTrue(red > 5 * green && red > 5 * blue, red + " " + green + " " + blue);
        assertEquals(whiteGreen, whiteRed, 0.02 * whiteGreen);
        assertEquals(whiteGreen, whiteBlue, 0.02 * whiteGreen);
        assertTrue(whiteGreen > 10_000, "a white surface at contact is bright: " + whiteGreen);
    }

    @Test
    void theGainAndTheAmbientLightScaleTheColourCounts() {
        Map<String, Object> dim = sensorAt(0, 0, 0);
        dim.put("ambientLight", 0.5);
        mount(dim, ScriptedBody.startingAt(0, 0, 0).build(), wall);
        startLikeTheSdk();
        measureWallAt(0.01);
        int dimmed = light(LS_DATA_GREEN);
        int infrared = light(LS_DATA_IR);

        mountAtOrigin();
        startLikeTheSdk();
        measureWallAt(0.01);
        int normal = light(LS_DATA_GREEN);
        write(LS_GAIN, 2);
        measureWallAt(0.01);
        int doubleGain = light(LS_DATA_GREEN);

        assertEquals(normal / 2.0, dimmed, 2.0);
        assertEquals(2.0 * normal, doubleGain, 2.0);
        assertTrue(infrared > 0);
    }

    @Test
    void theSensorLooksAlongItsMountedAxisFromWhereItIsMountedOnTheChassis() {
        // Mounted 0.05 m ahead of the centre and 0.05 m up, looking straight down at a floor.
        Map<String, Object> down = sensorAt(0.05, 0, 0.05);
        down.put("elevationDegrees", -90);
        FlatWall floor = new FlatWall(new Vec3(0, 0, 0), new Vec3(0, 0, 1), CARD);
        mount(down, ScriptedBody.startingAt(0, 0, 0).build(), floor);
        startLikeTheSdk();

        rig.runTo(250 * MS);

        assertEquals(0.05 / METRES_PER_INCH, sdkInches(proximity()), 0.05);
    }

    @Test
    void theChassisOrientationTurnsTheRay() {
        // The chassis faces +Y. A sensor on its left side looking left looks along -X, from 0.05 m towards -X of the centre.
        Map<String, Object> left = sensorAt(0, 0.05, 0);
        left.put("azimuthDegrees", 90);
        FlatWall behind = new FlatWall(new Vec3(-0.1, 0, 0), new Vec3(1, 0, 0), CARD);
        mount(left, ScriptedBody.startingAt(0, 0, Math.PI / 2).build(), behind);
        startLikeTheSdk();

        rig.runTo(250 * MS);

        assertEquals(0.05 / METRES_PER_INCH, sdkInches(proximity()), 0.05);
    }

    @Test
    void disablingTheSensorStopsItsMeasurements() {
        mountAtOrigin();
        startLikeTheSdk();
        measureWallAt(0.02);
        int before = proximity();

        write(MAIN_CTRL, 0);
        measureWallAt(0.002);

        assertEquals(before, proximity());
    }

    @Test
    void theSettingsAreChecked() {
        WorldRig world = new WorldRig(ScriptedBody.startingAt(0, 0, 0).build(), wall);
        Map<String, Object> noHeight = new HashMap<>(Map.of("x", 0, "y", 0));
        Map<String, Object> misspelt = sensorAt(0, 0, 0);
        misspelt.put("azimuth", 90);

        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> DeviceModels.standard().create("rev-color-sensor-v3", "s", noHeight, world.host))
                .getMessage()
                .contains("\"z\""));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> DeviceModels.standard().create("rev-color-sensor-v3", "s", misspelt, world.host))
                .getMessage()
                .contains("\"azimuth\""));
    }

    @Test
    void measurementsFollowTheClockNotHowOftenTheyAreReadSoTheSameRunReadsTheSame() {
        mountAtOrigin();
        wall.moveTo(new Vec3(0.04, 0, 0));
        startLikeTheSdk();
        rig.runTo(SECOND);
        int whole = proximity();

        mountAtOrigin();
        wall.moveTo(new Vec3(0.04, 0, 0));
        startLikeTheSdk();
        for (long t = 0; t < SECOND; t += 3 * MS) {
            rig.runTo(t);
            proximity();
        }
        rig.runTo(SECOND);

        assertEquals(whole, proximity());
    }
}
