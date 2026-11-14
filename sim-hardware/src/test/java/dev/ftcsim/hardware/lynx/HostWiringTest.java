package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HubWire;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The wiring a host does, as the package documentation shows it. */
class HostWiringTest {

    @Test
    void aHostWiresAHubAndRunsItInLockstepWithItsWorld() {
        DcMotorPlant leftWheel = DcMotorPlant.goBilda435(2.0e-3);
        MemoryDevice imu = new MemoryDevice(0x28).with(0x00, 0xA0);
        boolean touchPressed = true;

        AtomicLong simNanos = new AtomicLong();
        LynxBus bus = new LynxBus(simNanos::get);
        HubConfig config = HubConfig.controlHub().withKeepAliveSupervision(false).withAttentionSignalling(false);
        LynxHub hub = new LynxHub(config, () -> 12.4, simNanos.get());
        bus.attach(hub);
        hub.motorPort(0).connect(leftWheel);
        hub.analogPort(1).connect(nanos -> 1.65);
        hub.digitalPort(3).connect(nanos -> !touchPressed);
        hub.attach(1, imu);

        RhspClient robot = new RhspClient(bus, hub.address());
        HubCalls calls = new HubCalls(robot);
        calls.enable(0, true);
        calls.setPower(0, 16384);
        for (int step = 0; step < 100; step++) {
            leftWheel.step(0.001);
            simNanos.addAndGet(1_000_000);
            bus.advanceTo(simNanos.get());
        }
        HubWire.Exchange exchange = bus.transmit(RhspClient.frame(173, 9, 0, 0x1000, Bytes.none()));

        assertEquals(1, exchange.replies().size());
        HubCalls.Bulk bulk = calls.bulk();
        assertTrue(bulk.encoders()[0] > 50);
        assertEquals(1650, bulk.analogMillivolts()[1]);
        assertEquals(0xF7, bulk.digitalInputs());
    }
}
