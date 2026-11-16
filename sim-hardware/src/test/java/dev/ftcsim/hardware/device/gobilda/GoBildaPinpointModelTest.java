package dev.ftcsim.hardware.device.gobilda;

import static dev.ftcsim.hardware.device.WorldRig.MS;
import static dev.ftcsim.hardware.device.WorldRig.SECOND;
import static dev.ftcsim.hardware.device.gobilda.PinpointRig.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.hardware.i2c.LittleEndian;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoBildaPinpointModelTest {

    private static final int NOT_READY = 0;
    private static final int READY = 1;
    private static final int CALIBRATING = 2;

    private static ScriptedBody standing() {
        return ScriptedBody.startingAt(0, 0, 0).build();
    }

    /** The goBILDA driver's own CRC-8, transcribed from its source: the chip must answer with what the driver checks. */
    private static byte sdkCrc8(byte[] data) {
        byte crc = (byte) 0x90;
        for (byte b : data) {
            crc ^= b;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = (byte) ((crc << 1) ^ 0x31);
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }

    /** A chip whose code has reset the pose and the IMU at power-up, as the drivers' setups do: ready at 250 ms. */
    private static PinpointRig resetRig(ScriptedBody body) {
        PinpointRig rig = new PinpointRig(body);
        rig.writeInt(DEVICE_CONTROL, RESET_POSITION_AND_IMU);
        return rig;
    }

    @Test
    void itAnswersAtItsAddressWithItsIdentityAndTheLoopTimeOfA1500HzChip() {
        PinpointRig rig = new PinpointRig(standing());

        assertEquals(0x31, rig.chip.address());
        assertEquals(2, rig.readInt(DEVICE_ID));
        assertEquals(3, rig.readInt(DEVICE_VERSION));
        assertEquals(667, rig.readInt(LOOP_TIME));
    }

    @Test
    void aSingleRegisterReadCarriesTheCrcTheDriverChecks() {
        PinpointRig rig = new PinpointRig(standing());

        byte[] read = rig.read(DEVICE_VERSION, 5);

        assertEquals(sdkCrc8(Arrays.copyOf(read, 4)), read[4]);
    }

    @Test
    void afterPowerUpItIsNotReadyThenCalibratesThenIsReadyAndNeverReportsAMissingPod() {
        PinpointRig rig = new PinpointRig(standing());

        rig.world.runTo(50 * MS);
        assertEquals(NOT_READY, rig.readInt(DEVICE_STATUS));
        rig.world.runTo(200 * MS);
        assertEquals(CALIBRATING, rig.readInt(DEVICE_STATUS));
        rig.world.runTo(400 * MS);
        assertEquals(READY, rig.readInt(DEVICE_STATUS));
    }

    @Test
    void drivingStraightReadsTheTrueDistanceDrivenAlongTheHeading() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(2, 0.5, 0, 0).build());

        rig.world.runTo(3 * SECOND);

        assertEquals(1000.0, rig.x(), 0.1);
        assertEquals(0.0, rig.y(), 0.1);
        assertEquals(0.0, rig.heading(), 1e-6);
    }

    @Test
    void theChipReportsInTheFrameOfWhereTheRobotStoodWhenItWasReset() {
        // Facing +Y at (2, 3): the chip's own frame has its X along the robot's forward.
        PinpointRig rig = resetRig(ScriptedBody.startingAt(2, 3, Math.PI / 2).stay(0.5).move(1, 0, 0.4, 0).build());

        rig.world.runTo(2 * SECOND);

        assertEquals(0.0, rig.x(), 0.1);
        assertEquals(400.0, rig.y(), 0.1);
    }

    @Test
    void anArcEndsWhereTheGeometryOfTheCircleSays() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(Math.PI / 2, 1, 0, 1).build());

        rig.world.runTo(Math.round((0.5 + Math.PI / 2) * SECOND) + 100 * MS);

        assertEquals(1000.0, rig.x(), 0.3);
        assertEquals(1000.0, rig.y(), 0.3);
        assertEquals(Math.PI / 2, rig.heading(), 1e-4);
    }

    @Test
    void spinningInPlaceDoesNotMoveItAndTheHeadingKeepsCountingPastAHalfTurn() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(2, 0, 0, 2 * Math.PI).build());

        rig.world.runTo(3 * SECOND);

        assertEquals(0.0, rig.x(), 0.3);
        assertEquals(0.0, rig.y(), 0.3);
        assertEquals(4 * Math.PI, rig.heading(), 1e-4);
    }

    @Test
    void whatTheCodeProgramsAboutItsPodsIsAcceptedAndChangesNothing() {
        ScriptedBody drive = ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(1, 1, 0, 0).move(1, 0, 0, 1).build();
        ScriptedBody sameDrive = ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(1, 1, 0, 0).move(1, 0, 0, 1).build();
        PinpointRig plain = resetRig(drive);
        PinpointRig misprogrammed = new PinpointRig(sameDrive);
        misprogrammed.writeInt(DEVICE_CONTROL, X_REVERSED | Y_REVERSED);
        misprogrammed.writeFloat(YAW_SCALAR, 1.5f);
        misprogrammed.configure(250.0, -90.0, 3.0);

        plain.world.runTo(3 * SECOND);
        misprogrammed.world.runTo(3 * SECOND);

        assertEquals(1000.0, plain.x(), 0.1);
        assertEquals(1.0, plain.heading(), 1e-5);
        assertEquals(plain.x(), misprogrammed.x(), 1e-3);
        assertEquals(plain.y(), misprogrammed.y(), 1e-3);
        assertEquals(plain.heading(), misprogrammed.heading(), 1e-6);
        assertEquals(250.0f, misprogrammed.readFloat(X_POD_OFFSET), "what was written reads back");
        assertEquals(1.5f, misprogrammed.readFloat(YAW_SCALAR));
    }

    @Test
    void theEncoderRegistersCountTheTrueTravelForwardAndLeftAtTheResolutionTheCodeWrote() {
        PinpointRig rig = new PinpointRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(1, 1, 0.2, 0).build());
        rig.configure(0, 0, FOUR_BAR_TICKS_PER_MM);

        rig.world.runTo(2 * SECOND);

        assertEquals(19894, rig.readInt(X_ENCODER_VALUE));
        assertEquals(3979, rig.readInt(Y_ENCODER_VALUE));
    }

    @Test
    void theChipHoldsItsOutputsWhileItCalibratesAndRestartsFromZeroAfterAReset() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).move(10, 0.5, 0, 0).build());
        rig.world.runTo(SECOND);
        assertEquals(375.0, rig.x(), 0.2);

        rig.writeInt(DEVICE_CONTROL, RESET_POSITION_AND_IMU);
        rig.world.runTo(SECOND + 100 * MS);
        assertEquals(CALIBRATING, rig.readInt(DEVICE_STATUS));
        assertEquals(0.0, rig.x(), 0.0);
        assertEquals(0, rig.readFloat(X_VELOCITY), 0.0);
        rig.world.runTo(2 * SECOND);

        assertEquals(READY, rig.readInt(DEVICE_STATUS));
        assertEquals(375.0, rig.x(), 0.2);
    }

    @Test
    void recalibratingKeepsThePose() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).move(1, 0.5, 0, 0).build());
        rig.world.runTo(SECOND);
        double before = rig.x();

        rig.writeInt(DEVICE_CONTROL, RECALIBRATE_IMU);
        rig.world.runTo(SECOND + 100 * MS);

        assertEquals(CALIBRATING, rig.readInt(DEVICE_STATUS));
        assertEquals(before, rig.x(), 0.0);
    }

    @Test
    void writingThePositionMovesTheReportedFrameAndTheChipCarriesOnFromThere() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(1, 0.5, 0, 0).build());
        rig.world.runTo(500 * MS);

        rig.writeFloat(X_POSITION, 600f);
        rig.writeFloat(Y_POSITION, 1200f);
        rig.writeFloat(H_ORIENTATION, (float) (Math.PI / 2));
        assertEquals(600.0, rig.x(), 0.0);
        rig.world.runTo(2 * SECOND);

        // The chip now believes it faces +Y, so the half metre driven forward is a half metre of +Y.
        assertEquals(600.0, rig.x(), 0.2);
        assertEquals(1700.0, rig.y(), 0.2);
        assertEquals(Math.PI / 2, rig.heading(), 1e-5);
    }

    @Test
    void velocitiesAreTheTrueOnesInTheReportedFrame() {
        PinpointRig driving = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(5, 0.5, 0, 0).build());
        PinpointRig turned = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(5, 0.5, 0, 0).build());
        PinpointRig spinning = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(5, 0, 0, 1).build());
        turned.world.runTo(SECOND);
        turned.writeFloat(H_ORIENTATION, (float) (Math.PI / 2));

        driving.world.runTo(2 * SECOND);
        turned.world.runTo(2 * SECOND);
        spinning.world.runTo(2 * SECOND);

        assertEquals(500.0, driving.readFloat(X_VELOCITY), 1e-3);
        assertEquals(0.0, driving.readFloat(Y_VELOCITY), 1e-3);
        assertEquals(0.0, turned.readFloat(X_VELOCITY), 1e-2);
        assertEquals(500.0, turned.readFloat(Y_VELOCITY), 1e-3);
        assertEquals(1.0, spinning.readFloat(H_VELOCITY), 1e-5);
    }

    @Test
    void theDefaultBulkReadIsTheTenRegistersTheDriverExpectsInOrder() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(1, 0.5, 0.1, 0.2).build());
        rig.world.runTo(1500 * MS);

        byte[] bulk = rig.read(BULK_READ, 40);

        int[] order = {DEVICE_STATUS, LOOP_TIME, X_ENCODER_VALUE, Y_ENCODER_VALUE, X_POSITION, Y_POSITION, H_ORIENTATION,
            X_VELOCITY, Y_VELOCITY, H_VELOCITY};
        for (int i = 0; i < order.length; i++) {
            assertArrayEquals(Arrays.copyOf(rig.read(order[i], 4), 4), Arrays.copyOfRange(bulk, 4 * i, 4 * i + 4), "register " + order[i]);
        }
    }

    @Test
    void aNarrowerBulkScopeReturnsOnlyWhatWasAskedForAndTheCrcFollowsIt() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(1, 0.5, 0, 0).build());
        rig.world.runTo(1500 * MS);

        rig.write(SET_BULK_READ, new byte[] {X_POSITION, H_ORIENTATION});
        byte[] bulk = rig.read(BULK_READ, 9);

        assertEquals(rig.readFloat(X_POSITION), LittleEndian.floatAt(bulk, 0));
        assertEquals(rig.readFloat(H_ORIENTATION), LittleEndian.floatAt(bulk, 4));
        assertEquals(sdkCrc8(Arrays.copyOf(bulk, 8)), bulk[8]);
    }

    @Test
    void theInertialRegistersGiveTheTiltAndTheOrientationSinceTheLastZeroing() {
        PinpointRig rig = resetRig(ScriptedBody.startingAt(0, 0, 1.0).stay(0.5).move(1, 0, 0, 1).build());

        rig.world.runTo(2 * SECOND);

        assertEquals(0.0, rig.readFloat(PITCH), 1e-6);
        assertEquals(0.0, rig.readFloat(ROLL), 1e-6);
        assertEquals(Math.cos(0.5), rig.readFloat(QUATERNION_W), 1e-5);
        assertEquals(0.0, rig.readFloat(QUATERNION_X), 1e-6);
        assertEquals(0.0, rig.readFloat(QUATERNION_Y), 1e-6);
        assertEquals(Math.sin(0.5), rig.readFloat(QUATERNION_Z), 1e-5);
    }

    @Test
    void theRegistersTheChipDoesNotWriteIgnoreWrites() {
        PinpointRig rig = new PinpointRig(standing());

        rig.writeInt(DEVICE_ID, 99);
        rig.writeInt(LOOP_TIME, 1);

        assertEquals(2, rig.readInt(DEVICE_ID));
        assertEquals(667, rig.readInt(LOOP_TIME));
    }

    @Test
    void whatTheChipReportsDoesNotDependOnHowTheWorldWasSteppedOrWhenItWasRead() {
        PinpointRig whole = resetRig(wanderingScript());
        PinpointRig sliced = resetRig(wanderingScript());

        whole.world.runTo(3 * SECOND);
        for (long t = 0; t < 3 * SECOND; t += 7 * MS) {
            sliced.world.runTo(t);
            sliced.readInt(DEVICE_STATUS);
        }
        sliced.world.runTo(3 * SECOND);

        assertArrayEquals(whole.read(BULK_READ, 40), sliced.read(BULK_READ, 40));
    }

    private static ScriptedBody wanderingScript() {
        return ScriptedBody.startingAt(0, 0, 0).stay(0.5).move(1, 0.5, 0.1, 0.5).build();
    }

    @Test
    void itTakesNoSettings() {
        WorldRig world = new WorldRig(standing(), (origin, direction, maxMeters) -> null);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DeviceModels.standard().create(DeviceModels.PINPOINT, "odopods", Map.of("xPod.x", 0.1), world.host));

        assertTrue(failure.getMessage().contains("\"odopods\"") && failure.getMessage().contains("\"xPod.x\""), failure.getMessage());
    }
}
