package dev.ftcsim.hardware.device;

import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import java.util.function.LongConsumer;

/**
 * A world for device tests: a chassis and ray view, stepped at the physics rate of 240 Hz, with the steps at the same
 * instants as the simulator's, and a {@link DeviceHost} whose listeners run after each step. The chassis is usually a
 * scripted one; a test that needs a pose no script describes, a tilted chassis for one, brings a body of its own and
 * the function that moves it to each step's time.
 */
public final class WorldRig {

    private static final long STEPS_PER_SECOND = 240;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public static final long MS = 1_000_000L;
    public static final long SECOND = NANOS_PER_SECOND;

    public final BodyView body;
    public final DeviceHost host;
    private final LongConsumer moveBody;
    private long stepIndex;
    private long now;

    public WorldRig(ScriptedBody body, RayView rays) {
        this(body, body::advanceTo, rays);
    }

    /** @param moveBody told the time of each step before the devices look, so that {@code body} describes that instant */
    public WorldRig(BodyView body, LongConsumer moveBody, RayView rays) {
        this.body = body;
        this.moveBody = moveBody;
        this.host = new DeviceHost(body, rays, () -> now);
    }

    public long now() {
        return now;
    }

    /** Runs every physics step due up to {@code nanos}, then leaves the clock at {@code nanos}. */
    public WorldRig runTo(long nanos) {
        for (;;) {
            long next = (stepIndex + 1) * NANOS_PER_SECOND / STEPS_PER_SECOND;
            if (next > nanos) {
                break;
            }
            stepIndex++;
            now = next;
            moveBody.accept(next);
            host.step(next);
        }
        now = nanos;
        return this;
    }
}
