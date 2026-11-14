package dev.ftcsim.hardware.lynx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the robot code did that the simulated electronics could not honour, collected for the UI. The UI reads it from
 * a thread of its own while the hub goes on, so every access takes this object's monitor, which is never held while
 * anything that could wait is called.
 */
final class Diagnostics {

    private final LongSupplier clock;
    private final SortedSet<Integer> unboundMotors = new TreeSet<>();
    private final SortedSet<Integer> unboundDigital = new TreeSet<>();
    private final SortedSet<Integer> unboundAnalog = new TreeSet<>();
    private final SortedSet<I2cTarget> unansweredI2c = new TreeSet<>();
    private final Map<String, UnsupportedUse> unsupported = new LinkedHashMap<>();
    private boolean muted;

    Diagnostics(LongSupplier clock) {
        this.clock = clock;
    }

    /** Runs the action without noting anything it does, for a caller whose use of the hub is not the robot code's. */
    <T> T quietly(Supplier<T> action) {
        boolean wasMuted = mute(true);
        try {
            return action.get();
        } finally {
            mute(wasMuted);
        }
    }

    private synchronized boolean mute(boolean on) {
        boolean was = muted;
        muted = on;
        return was;
    }

    synchronized void motorDrivenWhileUnbound(int channel) {
        if (!muted) {
            unboundMotors.add(channel);
        }
    }

    synchronized void digitalReadWhileUnbound(int pin) {
        if (!muted) {
            unboundDigital.add(pin);
        }
    }

    synchronized void analogReadWhileUnbound(int channel) {
        if (!muted) {
            unboundAnalog.add(channel);
        }
    }

    synchronized void i2cAddressUnanswered(int bus, int address) {
        if (!muted) {
            unansweredI2c.add(new I2cTarget(bus, address));
        }
    }

    synchronized void unsupported(int packetId, String description) {
        if (muted) {
            return;
        }
        unsupported.merge(
                packetId + " " + description,
                new UnsupportedUse(packetId, description, clock.getAsLong(), 1),
                (first, again) -> new UnsupportedUse(packetId, description, first.firstSeenNanos(), first.count() + 1));
    }

    synchronized List<Integer> unboundMotorPorts() {
        return List.copyOf(unboundMotors);
    }

    synchronized List<Integer> unboundDigitalPorts() {
        return List.copyOf(unboundDigital);
    }

    synchronized List<Integer> unboundAnalogPorts() {
        return List.copyOf(unboundAnalog);
    }

    synchronized List<I2cTarget> unansweredI2cTargets() {
        return List.copyOf(unansweredI2c);
    }

    synchronized List<UnsupportedUse> unsupported() {
        return List.copyOf(unsupported.values());
    }
}
