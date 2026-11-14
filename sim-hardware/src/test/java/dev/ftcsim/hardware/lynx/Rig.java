package dev.ftcsim.hardware.lynx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A bus with one Control Hub on it, a settable clock and battery, and the plants wired to its motor ports. */
final class Rig {

    static final long MS = 1_000_000L;

    final AtomicLong clock = new AtomicLong();
    final double[] batteryVolts = {12.0};
    final LynxBus bus;
    final LynxHub hub;
    final RhspClient client;

    private final List<DcMotorPlant> plants = new ArrayList<>();

    /** A hub whose keep-alive supervision is off, as the simulator runs it. */
    Rig() {
        this(HubConfig.controlHub().withKeepAliveSupervision(false));
    }

    Rig(HubConfig config) {
        this(config, BusLatency.estimate());
    }

    private Rig(HubConfig config, BusLatency latency) {
        bus = new LynxBus(clock::get, latency);
        hub = new LynxHub(config, () -> batteryVolts[0], 0);
        bus.attach(hub);
        client = new RhspClient(bus, hub.address());
    }

    /** A rig whose exchanges take no time, so that a test that sets the clock itself sets every time exactly. */
    static Rig instant() {
        return new Rig(HubConfig.controlHub().withKeepAliveSupervision(false), new BusLatency(0, 0, 0, 0));
    }

    DcMotorPlant plug(int channel, DcMotorPlant plant) {
        hub.motorPort(channel).connect(plant);
        plants.add(plant);
        return plant;
    }

    DcMotorPlant plugGoBilda(int channel) {
        return plug(channel, DcMotorPlant.goBilda435(2.0e-3));
    }

    /** Runs the world in 1 ms steps: the plants move, then the clock and the hubs catch up. */
    void runMillis(int millis) {
        for (int i = 0; i < millis; i++) {
            plants.forEach(plant -> plant.step(1.0e-3));
            clock.addAndGet(MS);
            bus.advanceTo(clock.get());
        }
    }

    /** Moves the clock without stepping any plant. */
    void advanceTo(long nanos) {
        clock.set(nanos);
        bus.advanceTo(nanos);
    }

    /** A client that lets simulated time pass during every exchange, as the SDK adapter does. */
    RhspClient timedClient() {
        return new RhspClient(bus, hub.address()).advancingTimeWith(latency -> advanceTo(clock.get() + latency));
    }
}
