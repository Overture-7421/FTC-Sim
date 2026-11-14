package dev.ftcsim.contracts.hooks;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The wall clock of the robot world: the instants of {@link Hooks#currentTimeNanos}, which is simulated time on top of a
 * fixed epoch, in the zone it was asked for. Robot code gets it where the JDK would have given it the system clock.
 */
final class SimulatedClock extends Clock {
    private final ZoneId zone;

    SimulatedClock(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return newZone.equals(zone) ? this : new SimulatedClock(newZone);
    }

    @Override
    public long millis() {
        return Hooks.currentTimeMillis();
    }

    @Override
    public Instant instant() {
        long nanos = Hooks.currentTimeNanos();
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SimulatedClock clock && clock.zone.equals(zone);
    }

    @Override
    public int hashCode() {
        return zone.hashCode() + 1;
    }

    @Override
    public String toString() {
        return "SimulatedClock[" + zone + "]";
    }
}
