package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.GET_ADC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalogMeasurementsTest {

    private static final int USER0 = 0;
    private static final int GPIO_CURRENT = 4;
    private static final int I2C_BUS_CURRENT = 5;
    private static final int SERVO_CURRENT = 6;
    private static final int BATTERY_CURRENT = 7;
    private static final int MOTOR0_CURRENT = 8;
    private static final int FIVE_VOLT_MONITOR = 12;
    private static final int BATTERY_MONITOR = 13;
    private static final int CONTROLLER_TEMPERATURE = 14;

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;
    private final HubCalls hub = new HubCalls(client);

    @Test
    void userInputsReadInMillivoltsAndSaturateAtTheConvertersRange() {
        double[] volts = {1.65, 5.0, -0.4, 0.0};
        for (int port = 0; port < 4; port++) {
            double level = volts[port];
            rig.hub.analogPort(port).connect(nanos -> level);
        }

        assertEquals(1650, hub.adc(USER0));
        assertEquals(3300, hub.adc(USER0 + 1));
        assertEquals(0, hub.adc(USER0 + 2));
        assertEquals(0, hub.adc(USER0 + 3));
        assertTrue(rig.hub.unboundAnalogPorts().isEmpty());
    }

    @Test
    void anInputFollowsItsSourceAtTheSimulatedTimeOfTheRead() {
        rig.hub.analogPort(2).connect(nanos -> nanos / 1e9);

        rig.advanceTo(2_000_000_000L / 3);
        assertEquals(667, hub.adc(2));
        rig.advanceTo(3_000_000_000L);
        assertEquals(3000, hub.adc(2));
    }

    @Test
    void anOpenInputReadsZeroAndIsReportedWhenReadOnItsOwnButNotInTheBulkRead() {
        hub.bulk();
        assertTrue(rig.hub.unboundAnalogPorts().isEmpty());

        assertEquals(0, hub.adc(USER0 + 3));
        assertEquals(0, hub.adc(USER0 + 1));

        assertEquals(List.of(1, 3), rig.hub.unboundAnalogPorts());
    }

    @Test
    void rawModeIsTwelveBitsOverTheThreePointThreeVoltRangeForTheUserInputsOnly() {
        rig.hub.analogPort(0).connect(nanos -> 1.65);
        rig.hub.analogPort(1).connect(nanos -> 3.3);

        assertEquals(2048, client.response(GET_ADC, Bytes.of().u8(0).u8(1).build()).getShort());
        assertEquals(4095, client.response(GET_ADC, Bytes.of().u8(1).u8(1).build()).getShort());

        assertEquals(1, client.nack(GET_ADC, Bytes.of().u8(BATTERY_MONITOR).u8(1).build()));
        List<UnsupportedUse> unsupported = rig.hub.unsupported();
        assertEquals(1, unsupported.size());
        assertEquals(GET_ADC.number(), unsupported.get(0).packetId());
    }

    @Test
    void channelsAndModesOutsideTheCommandAreRefused() {
        assertEquals(0, client.nack(GET_ADC, Bytes.of().u8(15).u8(0).build()));
        assertEquals(1, client.nack(GET_ADC, Bytes.of().u8(0).u8(2).build()));
        assertEquals(1, client.nack(GET_ADC, Bytes.of().u8(0).build()));
    }

    @Test
    void theBatteryMonitorAndTheFiveVoltRailReadInMillivolts() {
        rig.batteryVolts[0] = 12.34;

        assertEquals(12_340, hub.adc(BATTERY_MONITOR));
        assertEquals(5000, hub.adc(FIVE_VOLT_MONITOR));
    }

    @Test
    void motorCurrentsComeFromThePlantsInMilliampsAndAnOpenPortDrawsNone() {
        StubPlant first = new StubPlant();
        first.amps = 3.2504;
        StubPlant third = new StubPlant();
        third.amps = 40.0;
        rig.hub.motorPort(0).connect(first);
        rig.hub.motorPort(2).connect(third);

        assertEquals(3250, hub.adc(MOTOR0_CURRENT));
        assertEquals(0, hub.adc(MOTOR0_CURRENT + 1));
        assertEquals(32_767, hub.adc(MOTOR0_CURRENT + 2));
        assertEquals(0, hub.adc(MOTOR0_CURRENT + 3));
    }

    @Test
    void theOtherCurrentsAndTheTemperatureAreTheDocumentedEstimates() {
        StubPlant plant = new StubPlant();
        plant.amps = 9.0;
        rig.hub.motorPort(0).connect(plant);
        rig.hub.attach(1, new Sensor(0x28));
        rig.hub.attach(2, new Sensor(0x29));
        client.ack(DekaCommand.SET_DIO_DIRECTION, Bytes.of().u8(0).u8(1).build());
        client.ack(DekaCommand.SET_SINGLE_DIO_OUTPUT, Bytes.of().u8(0).u8(1).build());
        client.ack(DekaCommand.SET_SERVO_CONFIGURATION, Bytes.of().u8(0).u16(20_000).build());
        client.ack(DekaCommand.SET_SERVO_ENABLE, Bytes.of().u8(0).u8(1).build());
        client.ack(DekaCommand.SET_SERVO_CONFIGURATION, Bytes.of().u8(5).u16(20_000).build());
        client.ack(DekaCommand.SET_SERVO_ENABLE, Bytes.of().u8(5).u8(1).build());

        assertEquals(2, hub.adc(GPIO_CURRENT));
        assertEquals(10, hub.adc(I2C_BUS_CURRENT));
        assertEquals(20, hub.adc(SERVO_CURRENT));
        assertEquals(200 + 9000 + 20 + 10 + 2, hub.adc(BATTERY_CURRENT));
        assertEquals(250 + 2 * 9, hub.adc(CONTROLLER_TEMPERATURE));
    }

    @Test
    void aDeviceInsideTheHubAnswersOnItsBusButNoPortSuppliesIt() {
        rig.hub.attach(1, new Sensor(0x28));
        rig.hub.attachInternal(0, new Sensor(0x28));

        assertTrue(rig.hub.answersAt(0, 0x28));
        assertTrue(rig.hub.answersAt(1, 0x28));
        assertFalse(rig.hub.answersAt(2, 0x28));
        assertEquals(5, hub.adc(I2C_BUS_CURRENT), "only the device on the port draws from the ports' supply");
        assertThrows(IllegalStateException.class, () -> rig.hub.attach(0, new Sensor(0x28)), "it still takes its address on the bus");
    }

    @Test
    void anIdleHubReportsRoomTemperatureAndItsOwnQuiescentCurrent() {
        assertEquals(250, hub.adc(CONTROLLER_TEMPERATURE));
        assertEquals(200, hub.adc(BATTERY_CURRENT));
        assertEquals(0, hub.adc(GPIO_CURRENT));
        assertEquals(0, hub.adc(I2C_BUS_CURRENT));
        assertEquals(0, hub.adc(SERVO_CURRENT));
    }

    @Test
    void theAnalogPortsOfTheHostApiAreFourAndTakeOneSourceEach() {
        rig.hub.analogPort(3).connect(nanos -> 0.5);

        assertThrows(IllegalStateException.class, () -> rig.hub.analogPort(3).connect(nanos -> 0.6));
        assertThrows(IllegalArgumentException.class, () -> rig.hub.analogPort(4));
    }

    /** A device that only has to be present on a bus. */
    private static final class Sensor extends RegisterFilePeripheral {
        Sensor(int address) {
            super(address);
        }
    }
}
