package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HubTimeTest {

    private final Rig rig = new Rig();
    private final AtomicInteger ticks = new AtomicInteger();

    private void driveACountingMotor(LynxHub hub, RhspClient client) {
        hub.motorPort(0).connect(new StubPlant() {
            @Override
            public void applyVoltage(double terminalVolts) {
                ticks.incrementAndGet();
            }
        });
        client.ack(DekaCommand.SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).u8(1).build());
        client.ack(DekaCommand.SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(0).u16(1000).build());
    }

    @Test
    void theFirmwareRunsOneTickForEveryMillisecondBoundaryThatSimulatedTimePasses() {
        driveACountingMotor(rig.hub, rig.client);

        rig.advanceTo(999_999);
        assertEquals(0, ticks.get());
        rig.advanceTo(1_000_000);
        assertEquals(1, ticks.get());
        rig.advanceTo(1_000_000);
        assertEquals(1, ticks.get());
        rig.advanceTo(11_500_000);
        assertEquals(11, ticks.get(), "a jump runs every tick it spans, one after the other");
    }

    @Test
    void timeCannotRunBackwards() {
        rig.advanceTo(5_000_000);

        assertThrows(IllegalArgumentException.class, () -> rig.hub.advanceTo(4_999_999));
        assertEquals(5_000_000, rig.hub.nowNanos());
    }

    @Test
    void aHubPoweredOnLaterDoesNotReplayThePastAndTicksOnTheGlobalMillisecondGrid() {
        rig.advanceTo(7_300_000);
        LynxHub late = new LynxHub(HubConfig.expansionHub(2, false, "late").withKeepAliveSupervision(false), () -> 12.0, 7_300_000);
        rig.bus.attach(late);
        driveACountingMotor(late, new RhspClient(rig.bus, 2));

        rig.advanceTo(7_900_000);
        assertEquals(0, ticks.get());
        rig.advanceTo(8_000_000);
        assertEquals(1, ticks.get());
    }
}
