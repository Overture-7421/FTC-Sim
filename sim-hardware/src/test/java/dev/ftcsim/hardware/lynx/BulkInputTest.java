package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.GET_BULK_INPUT_DATA;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_SINGLE_DIO_INPUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BulkInputTest {

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;
    private final HubCalls hub = new HubCalls(client);

    @Test
    void theResponseIsThirtyFourBytesInTheDocumentedOrder() {
        RhspClient.Reply reply = client.deka(GET_BULK_INPUT_DATA, Bytes.none());

        assertEquals(0x9000, reply.packetId());
        assertEquals(1 + 4 * 4 + 1 + 4 * 2 + 4 * 2, reply.payload().length);
    }

    @Test
    void aFreshHubReportsHighInputsZeroEncodersAndNothingBusy() {
        HubCalls.Bulk bulk = hub.bulk();

        assertEquals(0xFF, bulk.digitalInputs());
        assertArrayEquals(new int[4], bulk.encoders());
        assertArrayEquals(new int[4], bulk.velocities());
        assertArrayEquals(new int[4], bulk.analogMillivolts());
        assertEquals(0xF0, bulk.motorStatus(), "every channel reports at target, none over current");
    }

    @Test
    void agreesWithTheIndividualReadsOfTheSameInstant() {
        DcMotorPlant first = rig.plugGoBilda(0);
        DcMotorPlant second = rig.plugGoBilda(3);
        hub.enable(0, true);
        hub.enable(3, true);
        hub.setPower(0, 20000);
        hub.setPower(3, -9000);
        rig.runMillis(300);
        rig.hub.analogPort(1).connect(nanos -> 2.5);
        rig.hub.digitalPort(5).connect(nanos -> false);

        HubCalls.Bulk bulk = hub.bulk();

        assertEquals(hub.position(0), bulk.encoders()[0]);
        assertEquals(hub.position(3), bulk.encoders()[3]);
        assertEquals(Math.round(first.encoderCountsPerSecond()), bulk.velocities()[0]);
        assertEquals(Math.round(second.encoderCountsPerSecond()), bulk.velocities()[3]);
        assertTrue(bulk.velocities()[0] > 0 && bulk.velocities()[3] < 0);
        assertEquals(hub.adc(1), bulk.analogMillivolts()[1]);
        assertEquals(2500, bulk.analogMillivolts()[1]);
        assertEquals(0, client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(5).build()).get());
        assertEquals(0xDF, bulk.digitalInputs());
    }

    @Test
    void encodersAreThirtyTwoBitCountersThatWrapLikeTheHardwareOnes() {
        StubPlant plant = new StubPlant();
        rig.hub.motorPort(1).connect(plant);

        plant.counts = Integer.MAX_VALUE;
        assertEquals(Integer.MAX_VALUE, hub.bulk().encoders()[1]);
        plant.counts = Integer.MAX_VALUE + 5L;
        assertEquals(Integer.MIN_VALUE + 4, hub.bulk().encoders()[1]);
        assertEquals(Integer.MIN_VALUE + 4, hub.position(1));
        plant.counts = -3;
        assertEquals(-3, hub.bulk().encoders()[1]);
    }

    @Test
    void velocitiesAreRoundedToCountsPerSecondAndSaturateAsSixteenBitIntegers() {
        StubPlant plant = new StubPlant();
        rig.hub.motorPort(0).connect(plant);

        int[][] cases = {{1234, 1234}, {-1234, -1234}, {32767, 32767}, {40000, 32767}, {-32768, -32768}, {-90000, -32768}};
        for (int[] pair : cases) {
            plant.countsPerSecond = pair[0];
            assertEquals(pair[1], hub.bulk().velocities()[0], "velocity " + pair[0]);
        }
        plant.countsPerSecond = 1234.4;
        assertEquals(1234, hub.bulk().velocities()[0]);
        plant.countsPerSecond = 1234.6;
        assertEquals(1235, hub.bulk().velocities()[0]);
        plant.countsPerSecond = -1234.6;
        assertEquals(-1235, hub.bulk().velocities()[0]);
    }

    @Test
    void statusBitsSplitIntoOverCurrentLowAndAtTargetHigh() {
        StubPlant plant = new StubPlant();
        plant.amps = 12.0;
        rig.hub.motorPort(2).connect(plant);
        hub.setMode(2, HubCalls.POSITION, false);
        hub.setTarget(2, 50, 5);

        HubCalls.Bulk away = hub.bulk();
        assertTrue(away.overCurrent(2));
        assertFalse(away.atTarget(2));
        assertEquals(0b1011_0100, away.motorStatus());

        plant.counts = 48;
        plant.amps = 1.0;
        HubCalls.Bulk near = hub.bulk();
        assertFalse(near.overCurrent(2));
        assertTrue(near.atTarget(2));
        assertEquals(0b1111_0000, near.motorStatus());
    }

    @Test
    void theByteOrderIsLittleEndianThroughout() {
        StubPlant plant = new StubPlant();
        plant.counts = 0x01020304;
        plant.countsPerSecond = 0x0506;
        rig.hub.motorPort(0).connect(plant);
        rig.hub.analogPort(0).connect(nanos -> 1.2346);

        ByteBuffer raw = ByteBuffer.wrap(client.deka(GET_BULK_INPUT_DATA, Bytes.none()).payload());

        assertEquals(0x04, raw.get(1));
        assertEquals(0x01, raw.get(4));
        assertEquals(0x06, raw.get(1 + 16 + 1));
        assertEquals(0x05, raw.get(1 + 16 + 2));
        assertEquals(1235 & 0xFF, raw.get(1 + 16 + 1 + 8) & 0xFF);
        assertEquals(1235 >> 8, raw.get(1 + 16 + 1 + 9));
    }
}
