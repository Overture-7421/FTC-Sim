package dev.ftcsim.hardware.device.rev;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;

/**
 * The REV Color Sensor V3, whose chip is the Broadcom APDS-9151, as the SDK's {@code RevColorSensorV3} talks to it.
 * It measures by looking along one ray into the world: proximity from how much of its infrared LED comes back, and
 * red, green, blue and infrared light from the ambient light the surface reflects.
 *
 * <p>Proximity follows the fit REV calibrated the SDK's distance conversion with, {@code counts = a * inches^b + c},
 * for a card at 32 pulses and 125 mA, so with the SDK's settings and a card-like surface the SDK reads back the true
 * distance. Other surfaces scale the signal by their reflectivity, which is taken from the brightest channel of the
 * surface colour as sRGB encodes it (dyes and plastics reflect near-infrared about as well as their strongest
 * visible band; only black absorbs it), so a dark surface reads farther than it is. Very near surfaces saturate at
 * the resolution's largest count with the overflow bit set, and nothing within range reads the fit's offset, which
 * the SDK converts to its 6 inch maximum. The pulse count, LED current and resolution registers scale the signal
 * too.
 *
 * <p>Light is the ambient light of the room, blended with the surface colour as the surface gets close, scaled by
 * the gain and resolution registers and the profile's {@code ambientLight}. Every measurement happens at the rate
 * the rate registers say, 100 ms with the SDK's settings, and the data registers hold the last result between
 * measurements. All of it is estimated, not measured.
 *
 * <p>Profile settings: {@code x}, {@code y}, {@code z} (metres, body frame) of the sensor's front face,
 * {@code azimuthDegrees} (default 0: forward; 90: left) and {@code elevationDegrees} (default 0; -90: straight
 * down) of the direction it looks, and {@code ambientLight} (default 1, normal indoor lighting).
 */
public final class RevColorSensorV3Model extends RegisterFilePeripheral {

    public static final int ADDRESS = 0x52;

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
    private static final int LAST_READ_ONLY = 0x15;

    private static final int PART_ID_VALUE = 0xC2;

    private static final int CTRL_PS_ENABLE = 0x01;
    private static final int CTRL_LS_ENABLE = 0x02;
    private static final int CTRL_RESET = 0x10;
    private static final int STATUS_PS_DATA = 0x01;
    private static final int STATUS_LS_DATA = 0x08;
    private static final int STATUS_POWER_ON = 0x20;
    private static final int PS_OVERFLOW = 0x08;

    private static final double[] LED_MILLIAMPS = {2.5, 5, 10, 25, 50, 75, 100, 125};
    private static final double[] GAINS = {1, 3, 6, 9, 18};
    /** The gain at which the full-scale reading is defined. */
    private static final double REFERENCE_GAIN = 3;
    private static final long[] PS_PERIOD_NANOS = {
        100_000_000L, 6_250_000L, 12_500_000L, 25_000_000L, 50_000_000L, 100_000_000L, 200_000_000L, 400_000_000L
    };
    private static final long[] LS_PERIOD_NANOS = {
        25_000_000L, 50_000_000L, 100_000_000L, 200_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L,
        2_000_000_000L
    };
    private static final int[] LS_BITS = {20, 19, 18, 17, 16, 13, 16, 16};

    // The fit of the SDK's RevColorSensorV3: counts = A * inches^B + C, from aParam, binvParam and cParam.
    private static final double FIT_A = 325.961;
    private static final double FIT_B = 1.0 / -0.75934;
    private static final double FIT_C = 26.980;
    private static final double CALIBRATION_PULSES = 32;
    private static final double CALIBRATION_MILLIAMPS = 125;
    /** The card stock the fit was made with, as a share of the light a perfect white would return. */
    private static final double CARD_REFLECTIVITY = 0.8;
    private static final double METRES_PER_INCH = 0.0254;
    /** Beyond this the LED's light is lost in the offset; the world is not asked about anything farther. */
    private static final double RANGE_METRES = 0.30;

    /** What the green channel counts at 16 bits and gain 3 for a white surface touching the sensor in normal light. */
    private static final double LIGHT_FULL_SCALE = 20_000;
    /** How much of the light the sensor sees from the room when nothing is near, against a white surface. */
    private static final double LIGHT_ROOM_FRACTION = 0.05;
    /** The distance at which the surface fills half the sensor's view. */
    private static final double LIGHT_BLEND_METRES = 0.03;
    private static final double INFRARED_FRACTION = 0.5;
    /** The SDK multiplies red by 1.07 and blue by 1.55 to balance the channels; the chip is that much less sensitive. */
    private static final double RED_SENSITIVITY = 1.0 / 1.07;
    private static final double BLUE_SENSITIVITY = 1.0 / 1.55;

    private final BodyView body;
    private final RayView rays;
    private final Vec3 position;
    private final Vec3 axis;
    private final double ambientLight;
    private long nextProximityNanos;
    private long nextLightNanos;

    RevColorSensorV3Model(
            DeviceContext context, Vec3 position, double azimuthRadians, double elevationRadians, double ambientLight) {
        super(ADDRESS);
        this.body = context.body();
        this.rays = context.rays();
        this.position = position;
        this.axis = new Vec3(
                StrictMath.cos(elevationRadians) * StrictMath.cos(azimuthRadians),
                StrictMath.cos(elevationRadians) * StrictMath.sin(azimuthRadians),
                StrictMath.sin(elevationRadians));
        this.ambientLight = ambientLight;
        loadDefaults();
    }

    /** The registry's factory. */
    public static I2cPeripheral create(DeviceConfig config, DeviceContext context) {
        Vec3 position = new Vec3(config.number("x"), config.number("y"), config.number("z"));
        double azimuth = StrictMath.toRadians(config.number("azimuthDegrees", 0));
        double elevation = StrictMath.toRadians(config.number("elevationDegrees", 0));
        double ambient = config.number("ambientLight", 1.0);
        if (ambient < 0) {
            throw config.rejected("ambientLight", "must not be negative, not " + ambient);
        }
        RevColorSensorV3Model model = new RevColorSensorV3Model(context, position, azimuth, elevation, ambient);
        context.onStep(model::afterStep);
        return model;
    }

    /** Takes the measurements that have fallen due since the previous physics step. */
    void afterStep(long stepNanos) {
        int control = peek(MAIN_CTRL);
        if ((control & CTRL_PS_ENABLE) != 0 && stepNanos >= nextProximityNanos) {
            measureProximity();
            nextProximityNanos = following(nextProximityNanos, stepNanos, proximityPeriodNanos());
        }
        if ((control & CTRL_LS_ENABLE) != 0 && stepNanos >= nextLightNanos) {
            measureLight();
            nextLightNanos = following(nextLightNanos, stepNanos, lightPeriodNanos());
        }
    }

    @Override
    protected int readRegister(int register, long nowNanos) {
        int value = peek(register);
        if (register == MAIN_STATUS) {
            poke(MAIN_STATUS, value & ~(STATUS_PS_DATA | STATUS_LS_DATA | STATUS_POWER_ON));
        }
        return value;
    }

    @Override
    protected void writeRegister(int register, int value, long nowNanos) {
        if (register >= PART_ID && register <= LAST_READ_ONLY) {
            return;
        }
        int before = peek(register);
        poke(register, value);
        if (register == MAIN_CTRL) {
            if ((value & CTRL_RESET) != 0) {
                loadDefaults();
                return;
            }
            if ((value & CTRL_PS_ENABLE) != 0 && (before & CTRL_PS_ENABLE) == 0) {
                nextProximityNanos = nowNanos + proximityPeriodNanos();
            }
            if ((value & CTRL_LS_ENABLE) != 0 && (before & CTRL_LS_ENABLE) == 0) {
                nextLightNanos = nowNanos + lightPeriodNanos();
            }
        }
    }

    private void loadDefaults() {
        for (int register = 0; register <= LAST_READ_ONLY; register++) {
            poke(register, 0);
        }
        poke(PS_LED, 0x36);
        poke(PS_PULSES, 0x08);
        poke(PS_MEAS_RATE, 0x05);
        poke(LS_MEAS_RATE, 0x22);
        poke(LS_GAIN, 0x01);
        poke(PART_ID, PART_ID_VALUE);
        poke(MAIN_STATUS, STATUS_POWER_ON);
    }

    private void measureProximity() {
        SurfaceHit hit = cast();
        double signal = 0;
        if (hit != null) {
            double inches = hit.distanceMeters() / METRES_PER_INCH;
            double pulses = peek(PS_PULSES) / CALIBRATION_PULSES;
            double led = LED_MILLIAMPS[peek(PS_LED) & 7] / CALIBRATION_MILLIAMPS;
            signal = reflectivity(hit.rgb()) / CARD_REFLECTIVITY * FIT_A * StrictMath.pow(inches, FIT_B) * pulses * led;
        }
        int bits = 8 + ((peek(PS_MEAS_RATE) >> 3) & 3);
        double counts = Math.scalb(FIT_C + signal, bits - 11);
        int largest = (1 << bits) - 1;
        int value = (int) Math.min(Math.round(counts), largest);
        poke(PS_DATA, value & 0xFF);
        poke(PS_DATA + 1, (value >> 8) | (counts > largest ? PS_OVERFLOW : 0));
        poke(MAIN_STATUS, peek(MAIN_STATUS) | STATUS_PS_DATA);
    }

    private void measureLight() {
        SurfaceHit hit = cast();
        double blend = hit == null ? 0 : 1.0 / (1.0 + square(hit.distanceMeters() / LIGHT_BLEND_METRES));
        int rgb = hit == null ? 0 : hit.rgb();
        int bits = LS_BITS[(peek(LS_MEAS_RATE) >> 4) & 7];
        double gain = GAINS[Math.min(peek(LS_GAIN) & 7, GAINS.length - 1)] / REFERENCE_GAIN;
        double scale = Math.scalb(ambientLight * gain * LIGHT_FULL_SCALE, bits - 16);
        int largest = (1 << bits) - 1;
        storeLight(LS_DATA_RED, mixed(blend, linear(rgb >> 16)) * RED_SENSITIVITY * scale, largest);
        storeLight(LS_DATA_GREEN, mixed(blend, linear(rgb >> 8)) * scale, largest);
        storeLight(LS_DATA_BLUE, mixed(blend, linear(rgb)) * BLUE_SENSITIVITY * scale, largest);
        storeLight(LS_DATA_IR, mixed(blend, reflectivity(rgb)) * INFRARED_FRACTION * scale, largest);
        poke(MAIN_STATUS, peek(MAIN_STATUS) | STATUS_LS_DATA);
    }

    private static double mixed(double blend, double surface) {
        return (1 - blend) * LIGHT_ROOM_FRACTION + blend * surface;
    }

    private void storeLight(int register, double counts, int largest) {
        int value = (int) Math.min(Math.round(counts), largest);
        poke(register, value & 0xFF);
        poke(register + 1, (value >> 8) & 0xFF);
        poke(register + 2, (value >> 16) & 0xFF);
    }

    private SurfaceHit cast() {
        BodyPose pose = body.sample();
        return rays.cast(pose.pointToField(position), pose.directionToField(axis), RANGE_METRES);
    }

    private long proximityPeriodNanos() {
        return PS_PERIOD_NANOS[peek(PS_MEAS_RATE) & 7];
    }

    private long lightPeriodNanos() {
        return LS_PERIOD_NANOS[peek(LS_MEAS_RATE) & 7];
    }

    /** The first instant on the measurement grid that begins at {@code due} and is later than {@code now}. */
    private static long following(long due, long now, long period) {
        return due + ((now - due) / period + 1) * period;
    }

    private static double reflectivity(int rgb) {
        return Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF)) / 255.0;
    }

    /** The share of light an 8-bit sRGB channel stands for. */
    private static double linear(int channel) {
        double encoded = (channel & 0xFF) / 255.0;
        return encoded <= 0.04045 ? encoded / 12.92 : StrictMath.pow((encoded + 0.055) / 1.055, 2.4);
    }

    private static double square(double value) {
        return value * value;
    }
}
