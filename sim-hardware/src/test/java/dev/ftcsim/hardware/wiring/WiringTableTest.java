package dev.ftcsim.hardware.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import java.util.List;
import org.junit.jupiter.api.Test;

class WiringTableTest {
    private static WiringTable.Builder controlHubAndExpansionHub() {
        return WiringTable.builder().hub(173, "Control Hub", true, true).hub(2, "Expansion Hub 2", false, false);
    }

    @Test
    void aDevicePlacedByTheProfileIsFoundWhereTheProfileSaidItIs() {
        WiringTable table = controlHubAndExpansionHub()
                .motor("frontLeft", 173, 2, 537.7, 312)
                .servo("claw", 2, 5)
                .i2c("odometry", 173, 3)
                .build();

        assertEquals(new HardwareDirectory.MotorWiring(173, 2, 537.7, 312, true), table.motor("frontLeft"));
        assertEquals(new HardwareDirectory.ServoWiring(2, 5, true), table.servo("claw"));
        assertEquals(new HardwareDirectory.I2cWiring(173, 3, true), table.i2c("odometry"));
        assertEquals(List.of(), table.unmappedNames());
    }

    @Test
    void aNameTheProfileDoesNotMentionGetsTheNextFreePortOnceAndForAllAndIsReported() {
        WiringTable table = controlHubAndExpansionHub()
                .defaultMotor(28, 6000)
                .motor("placed", 173, 0, 537.7, 312)
                .build();

        HardwareDirectory.MotorWiring first = table.motor("shooter");
        HardwareDirectory.MotorWiring second = table.motor("intake");

        assertEquals(new HardwareDirectory.MotorWiring(173, 1, 28, 6000, false), first);
        assertEquals(new HardwareDirectory.MotorWiring(173, 2, 28, 6000, false), second);
        assertSame(first, table.motor("shooter"));
        assertEquals(List.of("shooter", "intake"), table.unmappedNames());
        assertTrue(table.motor("placed").mapped());
        assertFalse(table.motor("shooter").mapped());
    }

    @Test
    void whenTheFirstHubIsFullTheNextHubGetsTheDevice() {
        WiringTable table = controlHubAndExpansionHub().build();

        for (int i = 0; i < 4; i++) {
            assertEquals(173, table.motor("m" + i).hubAddress());
        }

        assertEquals(new HardwareDirectory.MotorWiring(2, 0, 537.7, 312, false), table.motor("m4"));
    }

    @Test
    void runningOutOfPortsIsAnErrorThatNamesTheDevice() {
        WiringTable table = WiringTable.builder().hub(173, "Control Hub", true, true).build();
        for (int i = 0; i < 4; i++) {
            table.motor("m" + i);
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> table.motor("one too many"));

        assertTrue(failure.getMessage().contains("one too many"), failure.getMessage());
    }

    @Test
    void aProfileThatPutsTwoDevicesOnOnePortOrUsesAnUnknownHubIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> controlHubAndExpansionHub().motor("a", 173, 0, 1, 1).motor("b", 173, 0, 1, 1).build());
        assertThrows(IllegalArgumentException.class, () -> controlHubAndExpansionHub().servo("claw", 99, 0).build());
        assertThrows(IllegalArgumentException.class, () -> controlHubAndExpansionHub().motor("far", 173, 4, 1, 1).build());
    }

    @Test
    void i2cDevicesTheProfileDoesNotPlaceShareTheFirstUserBus() {
        WiringTable table = controlHubAndExpansionHub().build();

        assertEquals(new HardwareDirectory.I2cWiring(173, 1, false), table.i2c("color"));
        assertEquals(new HardwareDirectory.I2cWiring(173, 1, false), table.i2c("pinpoint"));
    }

    @Test
    void anImuUnderAnyNameTheProfileDoesNotPlaceIsTheEmbeddedHubsOwnOnBus0AndIsNotReportedAsUnbound() {
        WiringTable table = controlHubAndExpansionHub().build();

        HardwareDirectory.I2cWiring first = table.imu("gyro");

        assertEquals(new HardwareDirectory.I2cWiring(173, 0, false), first);
        assertEquals(first, table.imu("gyro"), "the same name finds it again");
        assertEquals(List.of(), table.unmappedNames());
    }

    @Test
    void theHubsOneImuCannotBeAskedForUnderASecondName() {
        WiringTable table = controlHubAndExpansionHub().build();
        table.imu("imu");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> table.imu("IMU"));

        assertTrue(refused.getMessage().contains("\"IMU\"") && refused.getMessage().contains("\"imu\" already is it"), refused.getMessage());
        assertEquals(new HardwareDirectory.I2cWiring(173, 0, false), table.imu("imu"), "the first name still has it");
    }

    @Test
    void anImuTheProfileListedIsWhereTheProfileSaidAndTheNameItGaveIsTheOnlyNameOfTheEmbeddedOne() {
        WiringTable table = controlHubAndExpansionHub()
                .i2c("imu", 173, 0)
                .embeddedImu("imu")
                .i2c("external", 2, 1)
                .build();

        assertEquals(new HardwareDirectory.I2cWiring(173, 0, true), table.imu("imu"));
        assertEquals(new HardwareDirectory.I2cWiring(2, 1, true), table.imu("external"), "a listed external IMU is wired as listed");
        assertThrows(IllegalStateException.class, () -> table.imu("gyro"));
    }

    @Test
    void aRobotWithoutAControlHubHasNoImuOfItsOwn() {
        WiringTable table = WiringTable.builder().hub(2, "Expansion Hub 2", true, false).build();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> table.imu("imu"));

        assertTrue(refused.getMessage().contains("no Control Hub"), refused.getMessage());
    }

    @Test
    void aProfileThatNamesTwoEmbeddedImusIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> controlHubAndExpansionHub().i2c("a", 173, 0).embeddedImu("a").i2c("b", 173, 0).embeddedImu("b").build());
    }
}
