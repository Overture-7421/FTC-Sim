package dev.ftcsim.hardware.device.digitalchickenlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.hardware.device.DeviceHost;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.TurretView;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.i2c.LittleEndian;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The OctoQuad at register level: the bytes the SDK's {@code OctoQuadImpl} writes and reads, against the turret's true angle. */
class OctoQuadModelTest {
    private static final long MS = 1_000_000L;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final int COMMAND = 0x04;
    private static final int POSITIONS = 0x1C;
    private static final int VELOCITIES = 0x3C;

    /** A turret that turns as the test says. */
    private static final class Turret implements TurretView {
        double yaw;

        @Override
        public double yawRadians() {
            return yaw;
        }

        @Override
        public double yawRateRadPerSec() {
            return 0.0;
        }
    }

    private final Turret turret = new Turret();
    private long now;
    private DeviceHost host;
    private I2cPeripheral octo;

    private void build(Map<String, Object> settings) {
        host = new DeviceHost(() -> BodyPose.atOrigin(now), (origin, direction, maxMeters) -> null, () -> now).provideTurret(turret);
        octo = DeviceModels.standard().create(DeviceModels.OCTOQUAD, "octo", settings, host);
    }

    /** The turret's two encoders as the BIOBUZZ robot has them: 5.5 turns per turret turn on port 0, 44/7 on port 1. */
    private static Map<String, Object> turretEncoders() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("encoder0.turns", 5.5);
        settings.put("encoder1.turns", 44.0 / 7.0);
        settings.put("encoder1.mirrored", 1);
        return settings;
    }

    private void runTo(long nanos) {
        for (long t = now + 4 * MS; t <= nanos; t += 4 * MS) {
            now = t;
            host.step(t);
        }
        now = nanos;
    }

    private void turnTo(double yawRadians, long nanos) {
        turret.yaw = yawRadians;
        runTo(nanos);
    }

    private byte[] read(int register, int count) {
        return octo.writeRead(new byte[] {(byte) register}, count, now);
    }

    private void write(int register, int... bytes) {
        byte[] message = new byte[bytes.length + 1];
        message[0] = (byte) register;
        for (int i = 0; i < bytes.length; i++) {
            message[i + 1] = (byte) bytes[i];
        }
        octo.write(message, now);
    }

    private void command(int... commandAndData) {
        write(COMMAND, commandAndData);
    }

    private int position(int channel) {
        return LittleEndian.intAt(read(POSITIONS + 4 * channel, 4), 0);
    }

    private int velocity(int channel) {
        byte[] bytes = read(VELOCITIES + 2 * channel, 2);
        return (short) ((bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8);
    }

    private void setBankConfig(int config) {
        command(1, 2, config);
    }

    /** CRC-16/PROFIBUS the way the SDK computes it, from a table. */
    private static int crc16(byte[] data, int length) {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1DCF : crc << 1;
            }
            table[i] = crc & 0xFFFF;
        }
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc = ((crc << 8) ^ table[((crc >> 8) ^ data[i]) & 0xFF]) & 0xFFFF;
        }
        return crc ^ 0xFFFF;
    }

    @Test
    void theCrcAlgorithmOfTheTestIsProfibusAndTheDeviceIdentifiesAsAnOctoQuadWithFirmware31() {
        assertEquals(0xA819, crc16("123456789".getBytes(StandardCharsets.US_ASCII), 9), "the check value of CRC-16/PROFIBUS");
        build(turretEncoders());

        assertEquals(0x51, read(0x00, 1)[0]);
        assertEquals(3, read(0x01, 1)[0]);
        assertEquals(1, read(0x02, 1)[0]);
        assertEquals(0, read(0x03, 1)[0]);
        assertEquals(OctoQuadModel.ADDRESS, octo.address());
    }

    @Test
    void theBulkReadOfPositionsAndVelocitiesCarriesACrcOverItsFortyEightBytes() {
        build(turretEncoders());
        turnTo(1.0, 100 * MS);

        byte[] block = read(POSITIONS, 50);

        int crc = (block[48] & 0xFF) | (block[49] & 0xFF) << 8;
        assertEquals(crc16(block, 48), crc);
        assertEquals(LittleEndian.intAt(block, 0), position(0), "the first position of the block is the port 0 register");
    }

    @Test
    void anAbsoluteEncoderOnABankOfPulseWidthPortsReportsItsPulseInMicroseconds() {
        build(turretEncoders());
        setBankConfig(1);

        turnTo(0.0, 20 * MS);
        assertEquals(4, position(0), "angle 0 is the shortest pulse of a version 2 encoder, 3.884 microseconds, as a counter sees it");
        assertEquals(4, position(1));
        assertEquals(0, position(2), "a port with no encoder has no pulse");

        // Half a turn of the first encoder: 2 pi / 5.5 / 2 of turret yaw.
        turnTo(Math.PI / 5.5, 40 * MS);
        assertEquals(Math.round(3.884 + 0.5 * (998.06 - 3.884)), position(0));
        // The second is mirrored and turns 44/7 turns per turn, so at the same yaw its pulse is a different place on its turn.
        double secondTurns = -(44.0 / 7.0) * (Math.PI / 5.5) / TWO_PI;
        double fraction = secondTurns - Math.floor(secondTurns);
        assertEquals(Math.round(3.884 + fraction * (998.06 - 3.884)), position(1));
    }

    @Test
    void theZeroOffsetAndTheDirectionBitMoveAndInvertThePulse() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("encoder0.turns", 5.5);
        settings.put("encoder0.zeroOffsetDegrees", 90.0);
        build(settings);
        setBankConfig(1);
        turnTo(0.0, 20 * MS);
        int quarter = position(0);
        assertEquals(Math.round(3.884 + 0.25 * (998.06 - 3.884)), quarter, "a quarter of a turn when the turret points forward");

        command(1, 0, 0x01);

        assertEquals(1 + 1024 - quarter, position(0), "reversed, the pulse is mirrored in the programmed range, which starts at 1 to 1024");
    }

    @Test
    void theIncrementalCountsFollowTheTurretAt8192ToATurnOfTheEncoderAndAResetZerosThem() {
        build(turretEncoders());
        setBankConfig(0);

        turnTo(TWO_PI / 5.5, 40 * MS);
        assertEquals(8192, position(0), "one turn of the encoder is 8192 counts");
        assertEquals(-Math.round(8192 * (44.0 / 7.0) / 5.5), position(1), "the mirrored one counts down");

        command(21, 0x01);
        assertEquals(0, position(0), "reset port 0");
        assertTrue(position(1) != 0, "and not port 1");
    }

    @Test
    void withWrapTrackingThePositionKeepsCountingPastAFullTurnAndAResetLeavesTheRawPulse() {
        build(turretEncoders());
        setBankConfig(1);
        command(1, 4, 0, 4, 0, 0xE6, 0x03);   // port 0: minimum 4, maximum 998
        command(1, 5, 0x01);                  // port 0 tracks wraps

        double yaw = 0.0;
        for (int step = 1; step <= 600; step++) {
            yaw = step * TWO_PI * 3.0 / 5.5 / 600.0;
            turnTo(yaw, step * 4 * MS);
        }

        int span = 998 - 4;
        double turns = 5.5 * yaw / TWO_PI;
        assertEquals(turns * span + 4, position(0), 4.0, "three turns of the encoder: the pulse and the wraps times the span");

        command(21, 0x01);
        int raw = position(0);
        assertTrue(raw >= 4 && raw <= 998, "after a reset the position is the raw pulse again: " + raw);
    }

    @Test
    void theVelocityIsTheChangeOfPositionOverTheSampleIntervalAndTheIntervalCanBeSet() {
        build(turretEncoders());
        setBankConfig(0);
        command(2, 3, 0);
        assertEquals(50, read(0x05, 1)[0], "the default interval is 50 milliseconds");

        // 5 rad/s of turret for 200 ms.
        for (long t = 4 * MS; t <= 200 * MS; t += 4 * MS) {
            turnTo(5.0 * t / 1e9, t);
        }
        double countsPerSample = 5.0 * 0.05 / TWO_PI * 5.5 * 8192;
        assertEquals(countsPerSample, velocity(0), 0.08 * countsPerSample, "samples are taken on the 4 ms steps, so an interval is a little over 50 ms");

        command(1, 3, 0, 100);
        command(2, 3, 0);
        assertEquals(100, read(0x05, 1)[0]);
    }

    @Test
    void parametersReadBackAsWrittenSaveToFlashKeepsThemForAResetOfEverythingAndTheUptimeIsTheSimulatedClock() {
        build(turretEncoders());
        command(1, 4, 2, 0x10, 0x00, 0xF0, 0x03);
        command(2, 4, 2);
        byte[] answer = read(0x05, 4);
        assertEquals(16, (answer[0] & 0xFF) | (answer[1] & 0xFF) << 8);
        assertEquals(1008, (answer[2] & 0xFF) | (answer[3] & 0xFF) << 8);

        command(3);
        command(1, 4, 2, 0x20, 0x00, 0xF0, 0x03);
        command(20);
        command(2, 4, 2);
        assertEquals(16, read(0x05, 1)[0], "everything goes back to what the flash held");

        runTo(2_500 * MS);
        command(2, 200);
        assertEquals(2500, LittleEndian.intAt(read(0x05, 4), 0));
    }

    @Test
    void aSettingThatIsWrongOrNotKnownIsRefusedByName() {
        Map<String, Object> noTurns = new LinkedHashMap<>();
        noTurns.put("encoder0.turns", 0.0);
        assertThrows(IllegalArgumentException.class, () -> build(noTurns));
        Map<String, Object> typo = new LinkedHashMap<>();
        typo.put("encoder0.turns", 5.5);
        typo.put("encoder0.turnz", 3.0);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> build(typo));
        assertTrue(refused.getMessage().contains("turnz"), refused.getMessage());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("encoder0.turns", 5.5);
        model.put("encoder0.model", "nonsense");
        assertThrows(IllegalArgumentException.class, () -> build(model));
    }
}
