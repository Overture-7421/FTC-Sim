package dev.ftcsim.hardware.device.digitalchickenlabs;

import static dev.ftcsim.hardware.device.WorldRig.MS;
import static dev.ftcsim.hardware.device.WorldRig.SECOND;
import static dev.ftcsim.hardware.device.digitalchickenlabs.OctoQuadRig.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.hardware.i2c.LittleEndian;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OctoQuadModelTest {

    private static final int NOT_READY = 1;
    private static final int WARMING_UP = 2;
    private static final int CALIBRATING = 3;
    private static final int RUNNING = 4;

    private static WorldRig worldOf(ScriptedBody body) {
        return new WorldRig(body, (origin, direction, maxMeters) -> null);
    }

    private static WorldRig standing() {
        return worldOf(ScriptedBody.startingAt(0, 0, 0).build());
    }

    /** An OctoQuad whose channel {@code channel} counts what {@code source} counts. */
    private static OctoQuadRig counting(WorldRig world, int channel, EncoderSource source) {
        world.host.provideEncoder("encoder", source);
        Map<String, Object> settings = new HashMap<>();
        settings.put("channel" + channel + ".encoder", "encoder");
        return new OctoQuadRig(world, settings);
    }

    /** An OctoQuad with pods on channels 0 and 1 that has been started as a localizer for them. */
    private static OctoQuadRig localizing(ScriptedBody body, double tcpX, double tcpY) {
        OctoQuadRig rig = new OctoQuadRig(worldOf(body), pods());
        rig.startLocalizer(0, 1, TICKS_PER_MM, TICKS_PER_MM, tcpX, tcpY);
        return rig;
    }

    /** Where the real tracking centre point is, as a vector from the robot's centre, negated: what the offset must be. */
    private static final double TCP_X_MM = 4.0;
    private static final double TCP_Y_MM = 113.5;

    @Test
    void itAnswersWithTheChipIdTheFirmwareVersionAndItsAddress() {
        OctoQuadRig rig = new OctoQuadRig(standing(), new HashMap<>());

        assertEquals(0x30, rig.chip.address());
        assertEquals(0x51, rig.read(CHIP_ID, 1)[0]);
        assertArrayEquals(new byte[] {3, 1, 0}, rig.read(FIRMWARE_MAJOR, 3));
    }

    @Test
    void theFirmwareVersionCanBeChosenButOnlyFirmwareThreeIsModelled() {
        Map<String, Object> older = new HashMap<>(Map.of("firmware", "3.0.2"));
        Map<String, Object> wrong = new HashMap<>(Map.of("firmware", "2.0.0"));

        assertArrayEquals(new byte[] {3, 0, 2}, new OctoQuadRig(standing(), older).read(FIRMWARE_MAJOR, 3));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new OctoQuadRig(standing(), wrong))
                .getMessage()
                .contains("\"firmware\""));
    }

    @Test
    void aChannelCountsWhatItIsWiredToAndAnOpenChannelCountsNothing() {
        ManualSource source = new ManualSource();
        OctoQuadRig rig = counting(standing(), 3, source);

        source.counts = 1234;
        assertEquals(1234, rig.readInt(ENCODER_POSITION_0 + 3 * 4));
        source.counts = -5;
        assertEquals(-5, rig.readInt(ENCODER_POSITION_0 + 3 * 4));
        assertEquals(0, rig.readInt(ENCODER_POSITION_0));
    }

    @Test
    void theCrcOfThePublishedAlgorithmMatchesItsCheckValue() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);

        assertEquals(0xA819, Crc16Profibus.of(check, 0, check.length));
    }

    @Test
    void oneReadOfTheLocalizerAndEncoderBlocksCarriesTheCrcsTheDriverChecks() {
        ManualSource source = new ManualSource();
        OctoQuadRig rig = counting(standing(), 2, source);
        source.counts = 987_654;
        rig.world.runTo(SECOND);

        byte[] block = rig.read(LOCALIZER_STATUS, 0x4E - LOCALIZER_STATUS);

        assertEquals(Crc16Profibus.of(block, 0, 13), LittleEndian.unsignedShortAt(block, LOCALIZER_CRC - LOCALIZER_STATUS));
        assertEquals(
                Crc16Profibus.of(block, ENCODER_POSITION_0 - LOCALIZER_STATUS, 48),
                LittleEndian.unsignedShortAt(block, ENCODER_CRC - LOCALIZER_STATUS));
        assertEquals(987_654, LittleEndian.intAt(block, ENCODER_POSITION_0 + 8 - LOCALIZER_STATUS));
    }

    @Test
    void reversingAChannelKeepsTheCountAndCountsTheNextStepsTheOtherWay() {
        ManualSource source = new ManualSource();
        OctoQuadRig rig = counting(standing(), 0, source);
        source.counts = 100;

        rig.setDirection(0, true);
        assertEquals(100, rig.readInt(ENCODER_POSITION_0));
        source.counts = 110;

        assertEquals(90, rig.readInt(ENCODER_POSITION_0));
        assertEquals(1, rig.readParameter(DIRECTIONS, 0, 1)[0]);
        rig.setDirection(0, false);
        assertEquals(0, rig.readParameter(DIRECTIONS, 0, 1)[0]);
    }

    @Test
    void resettingChannelsZeroesJustTheOnesInTheMask() {
        ManualSource first = new ManualSource();
        ManualSource second = new ManualSource();
        WorldRig world = standing();
        world.host.provideEncoder("first", first).provideEncoder("second", second);
        OctoQuadRig rig = new OctoQuadRig(world, new HashMap<>(Map.of("channel0.encoder", "first", "channel1.encoder", "second")));
        first.counts = 10;
        second.counts = 20;

        rig.command(RESET_ENCODERS, 0b01);
        first.counts = 15;

        assertEquals(5, rig.readInt(ENCODER_POSITION_0));
        assertEquals(20, rig.readInt(ENCODER_POSITION_0 + 4));
    }

    @Test
    void aVelocityIsTheChangeOfTheCountDuringTheSamplingInterval() {
        WorldRig world = standing();
        world.host.provideEncoder("encoder", new RampSource(world, 1000));
        OctoQuadRig rig = new OctoQuadRig(world, new HashMap<>(Map.of("channel0.encoder", "encoder")));

        world.runTo(SECOND);
        assertEquals(50, rig.readShort(ENCODER_VELOCITY_0));
        assertEquals(50, rig.readParameter(VELOCITY_INTERVAL, 0, 1)[0]);
        rig.setParameter(VELOCITY_INTERVAL, 0, 100);
        world.runTo(2 * SECOND);

        assertEquals(100, rig.readShort(ENCODER_VELOCITY_0));
        assertEquals(100, rig.readParameter(VELOCITY_INTERVAL, 0, 1)[0]);
    }

    @Test
    void aVelocityTooBigForSixteenBitsSaturates() {
        WorldRig world = standing();
        world.host.provideEncoder("encoder", new RampSource(world, 2_000_000));
        OctoQuadRig rig = new OctoQuadRig(world, new HashMap<>(Map.of("channel0.encoder", "encoder")));

        world.runTo(SECOND);

        assertEquals(32767, rig.readShort(ENCODER_VELOCITY_0));
    }

    @Test
    void aPulseWidthChannelReportsTheWidthOfAnAbsoluteEncodersPulseAndCanAccumulateItsTurns() {
        ManualSource turn = new ManualSource();
        WorldRig world = standing();
        world.host.provideEncoder("turn", turn);
        OctoQuadRig rig = new OctoQuadRig(
                world, new HashMap<>(Map.of("channel0.encoder", "turn", "channel0.countsPerRev", 8192)));
        rig.setParameter(BANK_CONFIG, 1);

        assertEquals(1, rig.readInt(ENCODER_POSITION_0));
        turn.counts = 4096;
        assertEquals(513, rig.readInt(ENCODER_POSITION_0));
        rig.setParameter(PULSE_WIDTH_TRACKS_WRAP, 1);
        turn.counts = 20_480;
        assertEquals(513 + 2 * 1023, rig.readInt(ENCODER_POSITION_0));
        rig.command(RESET_ENCODERS, 1);
        assertEquals(513, rig.readInt(ENCODER_POSITION_0));
    }

    @Test
    void aWrongPulseRangeMakesTheAccumulatedPositionWrongAndTheRangeReadsBack() {
        ManualSource turn = new ManualSource();
        WorldRig world = standing();
        world.host.provideEncoder("turn", turn);
        OctoQuadRig rig = new OctoQuadRig(
                world, new HashMap<>(Map.of("channel0.encoder", "turn", "channel0.countsPerRev", 8192)));
        rig.setParameter(BANK_CONFIG, 1);
        rig.setParameter(PULSE_WIDTH_TRACKS_WRAP, 1);
        rig.setParameter(PULSE_WIDTH_RANGE, 0, 3, 0, 0xFC, 3);

        turn.counts = 20_480;

        assertArrayEquals(new byte[] {3, 0, (byte) 0xFC, 3}, rig.readParameter(PULSE_WIDTH_RANGE, 0, 4));
        assertEquals(513 + 2 * 1017, rig.readInt(ENCODER_POSITION_0));
    }

    @Test
    void aReversedPulseWidthChannelRunsTheOtherWayRound() {
        ManualSource turn = new ManualSource();
        WorldRig world = standing();
        world.host.provideEncoder("turn", turn);
        OctoQuadRig rig = new OctoQuadRig(
                world, new HashMap<>(Map.of("channel4.encoder", "turn", "channel4.countsPerRev", 8192)));
        rig.setParameter(BANK_CONFIG, 2);
        rig.setDirection(4, true);

        turn.counts = 2048;

        assertEquals(768, rig.readInt(ENCODER_POSITION_0 + 16));
        assertEquals(0, rig.readInt(ENCODER_POSITION_0));
    }

    @Test
    void theLocalizerIsNotReadyUntilItIsResetThenWarmsUpCalibratesAndRuns() {
        OctoQuadRig rig = localizing(ScriptedBody.startingAt(0, 0, 0).build(), TCP_X_MM, TCP_Y_MM);
        OctoQuadRig idle = new OctoQuadRig(standing(), pods());
        idle.world.runTo(SECOND);

        assertEquals(NOT_READY, idle.read(LOCALIZER_STATUS, 1)[0]);
        rig.world.runTo(50 * MS);
        assertEquals(WARMING_UP, rig.read(LOCALIZER_STATUS, 1)[0]);
        assertEquals(0, rig.read(YAW_AXIS, 1)[0]);
        rig.world.runTo(200 * MS);
        assertEquals(CALIBRATING, rig.read(LOCALIZER_STATUS, 1)[0]);
        rig.world.runTo(500 * MS);
        assertEquals(RUNNING, rig.read(LOCALIZER_STATUS, 1)[0]);
        assertEquals(5, rig.read(YAW_AXIS, 1)[0]);
    }

    @Test
    void theLocalizerReadsTheDistanceDrivenInMillimetresAlongTheHeading() {
        OctoQuadRig rig = localizing(ScriptedBody.startingAt(0, 0, 0).stay(1).move(2, 0.5, 0, 0).build(), TCP_X_MM, TCP_Y_MM);

        rig.world.runTo(3500 * MS);

        assertEquals(1000, rig.readShort(LOCALIZER_X), 1);
        assertEquals(0, rig.readShort(LOCALIZER_Y), 1);
        assertEquals(0, rig.readShort(LOCALIZER_H), 1);
    }

    @Test
    void anArcEndsWhereTheCircleSaysAndTheHeadingIsInFiveThousandthsOfARadian() {
        OctoQuadRig rig = localizing(
                ScriptedBody.startingAt(0, 0, 0).stay(1).move(Math.PI / 2, 1, 0, 1).build(), TCP_X_MM, TCP_Y_MM);

        rig.world.runTo(3 * SECOND);

        assertEquals(1000, rig.readShort(LOCALIZER_X), 1);
        assertEquals(1000, rig.readShort(LOCALIZER_Y), 1);
        assertEquals(Math.PI / 2 * 5000, rig.readShort(LOCALIZER_H), 1);
    }

    @Test
    void withTheTcpOffsetOfThePodsGeometrySpinningInPlaceMovesNothingAndTheHeadingWraps() {
        OctoQuadRig rig = localizing(
                ScriptedBody.startingAt(0, 0, 0).stay(1).move(1, 0, 0, 1.5 * Math.PI).build(), TCP_X_MM, TCP_Y_MM);

        rig.world.runTo(3 * SECOND);

        assertEquals(0, rig.readShort(LOCALIZER_X), 1);
        assertEquals(0, rig.readShort(LOCALIZER_Y), 1);
        assertEquals(-Math.PI / 2 * 5000, rig.readShort(LOCALIZER_H), 1);
    }

    @Test
    void withoutATcpOffsetTheTrackingPointIsWhereThePodsLinesCrossAndSwingsWithTheRobot() {
        OctoQuadRig rig = localizing(ScriptedBody.startingAt(0, 0, 0).stay(1).move(1, 0, 0, Math.PI).build(), 0, 0);

        rig.world.runTo(3 * SECOND);

        // The lines cross 4 mm behind and 113.5 mm right of the centre; half a turn about the centre swings that
        // point to the opposite side: the pose moves by twice its offset.
        assertEquals(8, rig.readShort(LOCALIZER_X), 1);
        assertEquals(227, rig.readShort(LOCALIZER_Y), 1);
    }

    @Test
    void theLocalizersSettingsTakeEffectOnlyAtTheNextReset() {
        OctoQuadRig kept = localizing(twoSeparateMetres(), TCP_X_MM, TCP_Y_MM);
        OctoQuadRig reset = localizing(twoSeparateMetres(), TCP_X_MM, TCP_Y_MM);
        kept.world.runTo(2500 * MS);
        reset.world.runTo(2500 * MS);
        assertEquals(1000, kept.readShort(LOCALIZER_X), 1);

        kept.setFloatParameter(X_TICKS_PER_MM, (float) (2 * TICKS_PER_MM));
        reset.setFloatParameter(X_TICKS_PER_MM, (float) (2 * TICKS_PER_MM));
        reset.command(RESET_LOCALIZER);
        kept.world.runTo(4500 * MS);
        reset.world.runTo(4500 * MS);

        assertEquals(2000, kept.readShort(LOCALIZER_X), 1);
        assertEquals(500, reset.readShort(LOCALIZER_X), 1);
    }

    private static ScriptedBody twoSeparateMetres() {
        return ScriptedBody.startingAt(0, 0, 0).stay(1).move(1, 1, 0, 0).stay(1).move(1, 1, 0, 0).build();
    }

    @Test
    void writingThePoseRegistersTeleportsTheLocalizer() {
        OctoQuadRig rig = localizing(ScriptedBody.startingAt(0, 0, 0).stay(1).move(1, 1, 0, 0).build(), TCP_X_MM, TCP_Y_MM);
        rig.world.runTo(900 * MS);

        byte[] pose = new byte[6];
        System.arraycopy(LittleEndian.shortBytes(600), 0, pose, 0, 2);
        System.arraycopy(LittleEndian.shortBytes(-300), 0, pose, 2, 2);
        System.arraycopy(LittleEndian.shortBytes(7854), 0, pose, 4, 2);
        rig.writeBytes(LOCALIZER_X, pose);
        assertEquals(600, rig.readShort(LOCALIZER_X));
        assertEquals(-300, rig.readShort(LOCALIZER_Y));
        rig.world.runTo(3 * SECOND);

        // Now facing +Y by the chip's reckoning, the metre driven forward is a metre of +Y.
        assertEquals(600, rig.readShort(LOCALIZER_X), 1);
        assertEquals(700, rig.readShort(LOCALIZER_Y), 1);
        assertEquals(7854, rig.readShort(LOCALIZER_H), 1);
    }

    @Test
    void theLocalizersVelocitiesAreAveragedOverItsIntervalAndScaled() {
        OctoQuadRig driving = localizing(ScriptedBody.startingAt(0, 0, 0).stay(1).move(5, 0.5, 0, 0).build(), TCP_X_MM, TCP_Y_MM);
        OctoQuadRig spinning = localizing(ScriptedBody.startingAt(0, 0, 0).stay(1).move(5, 0, 0, 1).build(), TCP_X_MM, TCP_Y_MM);

        driving.world.runTo(3 * SECOND);
        spinning.world.runTo(3 * SECOND);

        assertEquals(500, driving.readShort(LOCALIZER_VX), 3);
        assertEquals(0, driving.readShort(LOCALIZER_VY), 3);
        assertEquals(600, spinning.readShort(LOCALIZER_VH), 1);
    }

    @Test
    void firmware31ReportsItsUptimeAndTheImuDiagnosticAndFirmware30DoesNotKnowThem() {
        OctoQuadRig current = new OctoQuadRig(standing(), new HashMap<>());
        OctoQuadRig older = new OctoQuadRig(standing(), new HashMap<>(Map.of("firmware", "3.0.0")));
        current.world.runTo(1234 * MS);
        older.world.runTo(1234 * MS);

        assertEquals(1234, LittleEndian.intAt(current.readParameter(UPTIME_MS, 0, 4), 0));
        assertEquals(0, LittleEndian.intAt(current.readParameter(IMU_DIAGNOSTIC, 0, 4), 0));
        // Firmware 3.0 does not answer: the registers still hold the parameter number the master wrote.
        assertEquals(UPTIME_MS, LittleEndian.intAt(older.readParameter(UPTIME_MS, 0, 4), 0));
    }

    @Test
    void resettingEverythingRestoresTheFactorySettings() {
        ManualSource source = new ManualSource();
        OctoQuadRig rig = counting(standing(), 0, source);
        source.counts = 77;
        rig.setDirection(0, true);
        rig.setParameter(BANK_CONFIG, 1);
        rig.setParameter(VELOCITY_INTERVAL, 0, 100);

        rig.command(RESET_EVERYTHING);

        assertEquals(0, rig.readParameter(DIRECTIONS, 0, 1)[0]);
        assertEquals(0, rig.readParameter(BANK_CONFIG, 0, 1)[0]);
        assertEquals(50, rig.readParameter(VELOCITY_INTERVAL, 0, 1)[0]);
        assertEquals(0, rig.readInt(ENCODER_POSITION_0));
    }

    @Test
    void registersThatAreReadOnlyIgnoreWrites() {
        ManualSource source = new ManualSource();
        OctoQuadRig rig = counting(standing(), 0, source);
        source.counts = 42;

        rig.write(CHIP_ID, 0);
        rig.write(ENCODER_POSITION_0, 0, 0, 0, 0);

        assertEquals(0x51, rig.read(CHIP_ID, 1)[0]);
        assertEquals(42, rig.readInt(ENCODER_POSITION_0));
    }

    @Test
    void theProfileCannotWireAChannelTwiceNorToAnEncoderTheRobotLacks() {
        WorldRig world = standing();
        Map<String, Object> twice = pods();
        twice.put("channel0.encoder", "flywheel");
        Map<String, Object> unknown = new HashMap<>(Map.of("channel2.encoder", "flywheel"));
        Map<String, Object> misspelt = new HashMap<>(Map.of("chanel2.encoder", "flywheel"));

        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> DeviceModels.standard().create("octoquad-ftc-mk2", "oq", twice, world.host))
                .getMessage()
                .contains("channel0.encoder"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> DeviceModels.standard().create("octoquad-ftc-mk2", "oq", unknown, world.host))
                .getMessage()
                .contains("flywheel"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> DeviceModels.standard().create("octoquad-ftc-mk2", "oq", misspelt, world.host))
                .getMessage()
                .contains("\"chanel2.encoder\""));
    }

    @Test
    void whatTheChipReportsDoesNotDependOnHowTheWorldWasSteppedOrWhenItWasRead() {
        OctoQuadRig whole = localizing(wanderingScript(), TCP_X_MM, TCP_Y_MM);
        OctoQuadRig sliced = localizing(wanderingScript(), TCP_X_MM, TCP_Y_MM);

        whole.world.runTo(4 * SECOND);
        for (long t = 0; t < 4 * SECOND; t += 11 * MS) {
            sliced.world.runTo(t);
            sliced.read(LOCALIZER_STATUS, 1);
        }
        sliced.world.runTo(4 * SECOND);

        byte[] block = whole.read(LOCALIZER_STATUS, 0x4E - LOCALIZER_STATUS);
        assertArrayEquals(block, sliced.read(LOCALIZER_STATUS, 0x4E - LOCALIZER_STATUS));
        assertNotEquals(0, LittleEndian.intAt(block, LOCALIZER_X - LOCALIZER_STATUS));
    }

    private static ScriptedBody wanderingScript() {
        return ScriptedBody.startingAt(0, 0, 0).stay(1).move(2, 0.4, 0.1, 0.5).build();
    }
}
