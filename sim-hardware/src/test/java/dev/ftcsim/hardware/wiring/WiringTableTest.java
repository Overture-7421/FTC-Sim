package dev.ftcsim.hardware.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.HardwareDirectory.Sensor;
import dev.ftcsim.contracts.hardware.UnmappedDevice;
import java.util.List;
import org.junit.jupiter.api.Test;

class WiringTableTest {
    private static WiringTable.Builder controlHubAndExpansionHub() {
        return WiringTable.builder().hub(173, "Control Hub", true, true).hub(2, "Expansion Hub 2", false, false);
    }

    @Test
    void aMotorTheMappingPlacedIsFoundWhereTheMappingSaidItIs() {
        WiringTable table = controlHubAndExpansionHub().motor("frontLeft", 173, 2, 537.7, 312).build();

        assertEquals(new HardwareDirectory.MotorWiring(173, 2, 537.7, 312, true), table.motor("frontLeft"));
        assertEquals(List.of(), table.unmappedNames());
    }

    @Test
    void aNameTheMappingDoesNotMentionGetsTheNextFreePortOnceAndForAllAndIsReported() {
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
    void everyNameTheMappingDidNotPlaceIsReportedWithTheKindOfDeviceThatAskedForIt() {
        WiringTable table = controlHubAndExpansionHub().motor("placed", 173, 0, 537.7, 312).build();

        table.motor("shooter");
        table.servo("claw");
        table.digital("limit");
        table.analog("pot");
        table.motor("shooter");
        table.motor("placed");

        assertEquals(
                List.of(
                        new UnmappedDevice(UnmappedDevice.Kind.MOTOR, "shooter"),
                        new UnmappedDevice(UnmappedDevice.Kind.SERVO, "claw"),
                        new UnmappedDevice(UnmappedDevice.Kind.DIGITAL, "limit"),
                        new UnmappedDevice(UnmappedDevice.Kind.ANALOG, "pot")),
                table.unmapped());
    }

    @Test
    void aServoTheMappingPlacedKeepsItsPortFromOtherServosAndIsNotReported() {
        WiringTable table = controlHubAndExpansionHub().servo("turret", 173, 0).build();

        assertEquals(new HardwareDirectory.ServoWiring(173, 0, true), table.servo("turret"));
        assertEquals(new HardwareDirectory.ServoWiring(173, 1, false), table.servo("claw"), "another servo takes the next free port");
        assertEquals(List.of(new UnmappedDevice(UnmappedDevice.Kind.SERVO, "claw")), table.unmapped());
        assertThrows(IllegalArgumentException.class, () -> controlHubAndExpansionHub().servo("a", 173, 0).servo("b", 173, 0).build());
        assertThrows(IllegalArgumentException.class, () -> controlHubAndExpansionHub().servo("a", 173, 6).build());
    }

    @Test
    void theOctoQuadIsOnlyOnARobotWhoseDesignPutsOneOnItAndItsAbsenceIsAnErrorThatNamesIt() {
        WiringTable without = controlHubAndExpansionHub().build();
        WiringTable with = controlHubAndExpansionHub().sensor(Sensor.OCTOQUAD).build();

        assertFalse(without.hasSensor(Sensor.OCTOQUAD));
        assertTrue(without.hasSensor(Sensor.PINPOINT), "the other sensors are on every robot");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> without.sensor("octo", Sensor.OCTOQUAD));
        assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("octo"), refused.getMessage());
        assertEquals(3, with.sensor("octo", Sensor.OCTOQUAD).bus());
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
    void aMappingThatPutsTwoMotorsOnOnePortOrUsesAnUnknownHubOrPortIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> controlHubAndExpansionHub().motor("a", 173, 0, 1, 1).motor("b", 173, 0, 1, 1).build());
        assertThrows(IllegalArgumentException.class, () -> controlHubAndExpansionHub().motor("far", 99, 0, 1, 1).build());
        assertThrows(IllegalArgumentException.class, () -> controlHubAndExpansionHub().motor("far", 173, 4, 1, 1).build());
    }

    @Test
    void eachSensorIsOnItsOwnBusOfTheFirstHubWhateverNameAsksForItAndIsNotReportedAsUnbound() {
        WiringTable table = controlHubAndExpansionHub().build();

        assertEquals(new HardwareDirectory.I2cWiring(173, 1, true), table.sensor("odopods", Sensor.PINPOINT));
        assertEquals(new HardwareDirectory.I2cWiring(173, 2, true), table.sensor("upSensor", Sensor.COLOR_SENSOR));
        assertEquals(table.sensor("odopods", Sensor.PINPOINT), table.sensor("odopods", Sensor.PINPOINT), "the same name finds it again");
        assertEquals(List.of(), table.unmappedNames());
    }

    @Test
    void aSensorHasOneNameAndASecondNameForItIsRefused() {
        WiringTable table = controlHubAndExpansionHub().build();
        table.sensor("pinpoint", Sensor.PINPOINT);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> table.sensor("other", Sensor.PINPOINT));

        assertTrue(refused.getMessage().contains("\"other\"") && refused.getMessage().contains("\"pinpoint\" already is it"), refused.getMessage());
        assertEquals(new HardwareDirectory.I2cWiring(173, 2, true), table.sensor("other", Sensor.COLOR_SENSOR), "another kind of sensor is free");
    }

    @Test
    void anImuUnderAnyNameIsTheEmbeddedHubsOwnOnBus0() {
        WiringTable table = controlHubAndExpansionHub().build();

        HardwareDirectory.I2cWiring first = table.imu("gyro");

        assertEquals(new HardwareDirectory.I2cWiring(173, 0, true), first);
        assertEquals(first, table.imu("gyro"), "the same name finds it again");
        assertEquals(List.of(), table.unmappedNames());
    }

    @Test
    void theHubsOneImuCannotBeAskedForUnderASecondName() {
        WiringTable table = controlHubAndExpansionHub().build();
        table.imu("imu");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> table.imu("IMU"));

        assertTrue(refused.getMessage().contains("\"IMU\"") && refused.getMessage().contains("\"imu\" already is it"), refused.getMessage());
        assertEquals(new HardwareDirectory.I2cWiring(173, 0, true), table.imu("imu"), "the first name still has it");
    }

    @Test
    void aRobotWithoutAControlHubHasNoImuOfItsOwn() {
        WiringTable table = WiringTable.builder().hub(2, "Expansion Hub 2", true, false).build();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> table.imu("imu"));

        assertTrue(refused.getMessage().contains("no Control Hub"), refused.getMessage());
    }

    @Test
    void whatTheSimulatorCannotProvideIsRecordedOnceWithTheNameAndKindRobotCodeUsed() {
        WiringTable table = controlHubAndExpansionHub().build();

        table.unsupported("octo", "OctoQuad");
        table.unsupported("octo", "OctoQuad");
        table.unsupported("webcam", "WebcamName");

        assertEquals(2, table.unsupported().size());
        assertTrue(table.unsupported().get(0).contains("'octo'") && table.unsupported().get(0).contains("OctoQuad"), table.unsupported().toString());
        assertTrue(table.unsupported().get(1).contains("WebcamName"), table.unsupported().toString());
        assertEquals(List.of(), table.unmappedNames(), "unsupported is not the same as unmapped");
    }
}
