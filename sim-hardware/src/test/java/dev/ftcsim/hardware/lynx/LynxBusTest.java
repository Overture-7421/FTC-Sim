package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HubWire;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LynxBusTest {

    private static final int KEEP_ALIVE = 0x7F04;
    private static final int DISCOVERY = 0x7F0F;

    private final AtomicLong clock = new AtomicLong();
    private final LynxBus bus = new LynxBus(clock::get);
    private final LynxHub parent = hub(HubConfig.controlHub());
    private final LynxHub childTwo = hub(HubConfig.expansionHub(2, false, "child 2"));
    private final LynxHub childThree = hub(HubConfig.expansionHub(3, false, "child 3"));

    LynxBusTest() {
        bus.attach(parent);
        bus.attach(childThree);
        bus.attach(childTwo);
    }

    @Test
    void aDatagramGoesToTheHubItIsAddressedToAndTheReplyComesFromThatHub() {
        for (int address : new int[] {173, 2, 3}) {
            HubWire.Exchange exchange = send(address, KEEP_ALIVE, Bytes.none());

            assertEquals(1, exchange.replies().size());
            assertEquals(address, RhspClient.decode(exchange.replies().get(0), 0).source());
        }
    }

    @Test
    void aDatagramForAModuleThatIsNotOnTheBusGetsNoReplyAndIsRecorded() {
        clock.set(5_000_000);

        assertTrue(send(9, KEEP_ALIVE, Bytes.none()).replies().isEmpty());
        assertTrue(send(9, KEEP_ALIVE, Bytes.none()).replies().isEmpty());

        assertEquals(List.of(new RejectedFrame(FrameFault.NO_SUCH_MODULE, 5_000_000, 2)), bus.rejectedFrames());
    }

    @Test
    void malformedDatagramsGetNoReplyAndAreCountedPerReason() {
        byte[] good = RhspClient.frame(173, 1, 0, KEEP_ALIVE, Bytes.none());
        byte[] badChecksum = good.clone();
        badChecksum[good.length - 1]++;
        byte[] badLength = good.clone();
        badLength[2]++;
        byte[] badFrame = good.clone();
        badFrame[0] = 0;

        clock.set(1_000_000);
        assertTrue(bus.transmit(badChecksum).replies().isEmpty());
        clock.set(2_000_000);
        assertTrue(bus.transmit(badChecksum).replies().isEmpty());
        assertTrue(bus.transmit(badLength).replies().isEmpty());
        assertTrue(bus.transmit(badFrame).replies().isEmpty());
        assertTrue(bus.transmit(new byte[3]).replies().isEmpty());

        assertEquals(
                List.of(
                        new RejectedFrame(FrameFault.TOO_SHORT, 2_000_000, 1),
                        new RejectedFrame(FrameFault.BAD_FRAME_BYTES, 2_000_000, 1),
                        new RejectedFrame(FrameFault.LENGTH_MISMATCH, 2_000_000, 1),
                        new RejectedFrame(FrameFault.BAD_CHECKSUM, 1_000_000, 2)),
                bus.rejectedFrames());
        assertEquals(1, send(173, KEEP_ALIVE, Bytes.none()).replies().size(), "the bus still works");
    }

    @Test
    void aChildCannotBeReachedUntilItsParentIsOnTheBus() {
        LynxBus orphans = new LynxBus(clock::get);
        orphans.attach(hub(HubConfig.expansionHub(2, false, "orphan")));

        assertTrue(orphans.transmit(RhspClient.frame(2, 1, 0, KEEP_ALIVE, Bytes.none())).replies().isEmpty());

        orphans.attach(hub(HubConfig.controlHub()));
        assertEquals(1, orphans.transmit(RhspClient.frame(2, 1, 0, KEEP_ALIVE, Bytes.none())).replies().size());
    }

    @Test
    void theBusHasOneParentAndNoTwoHubsShareAnAddress() {
        assertThrows(IllegalArgumentException.class, () -> bus.attach(hub(HubConfig.expansionHub(1, true, "usb"))));
        assertThrows(IllegalArgumentException.class, () -> bus.attach(hub(HubConfig.expansionHub(2, false, "again"))));
        assertEquals(List.of(2, 3, 173), bus.hubs().stream().map(LynxHub::address).toList());
    }

    @Test
    void anExchangeCostsTheLinkTimeOfItsBytesPlusAFixedTurnaroundAndMoreForAChild() {
        // eleven bytes out, twelve back, at 20 us each, on top of 1.0 ms
        assertEquals(1_000_000 + 23 * 20_000, send(173, KEEP_ALIVE, Bytes.none()).latencyNanos());
        assertEquals(1_000_000 + 800_000 + 23 * 20_000, send(2, KEEP_ALIVE, Bytes.none()).latencyNanos());
        // a datagram nobody answers still occupies the link
        assertEquals(11 * 20_000, send(9, KEEP_ALIVE, Bytes.none()).latencyNanos());
        assertEquals(3 * 20_000, bus.transmit(new byte[3]).latencyNanos());
    }

    @Test
    void theLatencyModelCanBeReplaced() {
        LynxBus custom = new LynxBus(clock::get, new BusLatency(100, 1, 10, 7));
        custom.attach(hub(HubConfig.controlHub()));
        custom.attach(hub(HubConfig.expansionHub(2, false, "child")));

        assertEquals(100 + 23, custom.transmit(RhspClient.frame(173, 1, 0, KEEP_ALIVE, Bytes.none())).latencyNanos());
        assertEquals(100 + 10 + 23, custom.transmit(RhspClient.frame(2, 1, 0, KEEP_ALIVE, Bytes.none())).latencyNanos());
        assertThrows(IllegalArgumentException.class, () -> new BusLatency(-1, 0, 0, 0));
    }

    @Test
    void discoveryIsAnsweredByEveryHubTheParentFirstAndTheChildrenByAddress() {
        HubWire.Exchange exchange = send(255, DISCOVERY, Bytes.none());

        List<RhspClient.Reply> replies = RhspClient.decodeAll(exchange);
        assertEquals(List.of(173, 2, 3), replies.stream().map(RhspClient.Reply::source).toList());
        assertEquals(List.of(1, 0, 0), replies.stream().map(reply -> reply.payload()[0] & 0xFF).toList());
        assertTrue(replies.stream().allMatch(reply -> reply.packetId() == 0xFF0F));
        // 254 possible children at 2 ms each, which the SDK documents as about half a second
        assertTrue(exchange.latencyNanos() >= 508_000_000L && exchange.latencyNanos() < 520_000_000L);
    }

    @Test
    void anyOtherBroadcastIsIgnoredAndCostsOnlyItsBytes() {
        HubWire.Exchange exchange = send(255, KEEP_ALIVE, Bytes.none());

        assertTrue(exchange.replies().isEmpty());
        assertEquals(11 * 20_000, exchange.latencyNanos());
    }

    @Test
    void everyExchangeFirstBringsAllHubsUpToTheClockEvenThoseItIsNotFor() {
        StubPlant plant = new StubPlant();
        childTwo.motorPort(0).connect(plant);
        send(2, 0x1000 + DekaCommand.SET_MOTOR_CHANNEL_ENABLE.ordinal(), Bytes.of().u8(0).u8(1).build());
        send(2, 0x1000 + DekaCommand.SET_MOTOR_CONSTANT_POWER.ordinal(), Bytes.of().u8(0).u16(32767).build());
        assertTrue(plant.floating, "nothing has ticked yet");

        clock.set(40_000_000);
        send(173, KEEP_ALIVE, Bytes.none());

        assertEquals(40_000_000, childTwo.nowNanos());
        assertEquals(40_000_000, childThree.nowNanos());
        assertFalse(plant.floating);
        assertEquals(12.0, plant.volts, 1e-9);
    }

    @Test
    void anExchangeNeverMovesAHubBackwardsWhenTheClockLagsTheHostsLastExplicitAdvance() {
        clock.set(5_000_000);
        bus.advanceTo(10_000_000);

        assertEquals(1, send(173, KEEP_ALIVE, Bytes.none()).replies().size());

        assertEquals(10_000_000, parent.nowNanos());
        assertThrows(IllegalArgumentException.class, () -> bus.advanceTo(9_000_000));
    }

    @Test
    void hubsAreAdvancedInAddressOrderWhateverTheOrderTheyJoinedIn() {
        List<Integer> order = new ArrayList<>();
        for (LynxHub hub : new LynxHub[] {parent, childTwo, childThree}) {
            hub.motorPort(0).connect(new StubPlant() {
                @Override
                public void applyVoltage(double terminalVolts) {
                    order.add(hub.address());
                }
            });
            RhspClient client = new RhspClient(bus, hub.address());
            client.ack(DekaCommand.SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).u8(1).build());
            client.ack(DekaCommand.SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(0).u16(100).build());
        }

        bus.advanceTo(1_000_000);

        assertEquals(List.of(2, 3, 173), order);
    }

    private HubWire.Exchange send(int address, int packetId, byte[] payload) {
        return bus.transmit(RhspClient.frame(address, 1, 0, packetId, payload));
    }

    private LynxHub hub(HubConfig config) {
        return new LynxHub(config.withKeepAliveSupervision(false), () -> 12.0, 0);
    }
}
