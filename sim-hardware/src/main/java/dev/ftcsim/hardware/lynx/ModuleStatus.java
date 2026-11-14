package dev.ftcsim.hardware.lynx;

import java.util.function.DoubleSupplier;

/**
 * The module status word and the keep-alive supervision behind it. Event bits stay set until the host reads the
 * status with its clear flag; the battery bit follows the battery voltage.
 */
final class ModuleStatus {

    static final int KEEP_ALIVE_TIMEOUT = 1;
    static final int DEVICE_RESET = 1 << 1;
    static final int FAIL_SAFE = 1 << 2;
    static final int BATTERY_LOW = 1 << 4;

    /** The SDK's {@code LynxModule.msKeepAliveTimeout}: the host must be heard from at least this often. */
    static final long KEEP_ALIVE_TIMEOUT_NANOS = 2_500_000_000L;

    /** An estimate: the hub reports a low battery below about 7 V. */
    static final double LOW_BATTERY_VOLTS = 7.0;

    private final boolean supervised;
    private final boolean signalAttention;
    private final DoubleSupplier battery;

    private int events = DEVICE_RESET;
    private boolean heardFromHost;
    private long lastHeardNanos;
    private boolean lapseReported;

    ModuleStatus(boolean supervised, boolean signalAttention, DoubleSupplier battery) {
        this.supervised = supervised;
        this.signalAttention = signalAttention;
        this.battery = battery;
    }

    /** Any datagram addressed to the module counts as a keep-alive. */
    void noteHostContact(long nanos) {
        heardFromHost = true;
        lastHeardNanos = nanos;
        lapseReported = false;
    }

    /** True once per silence, at the first tick after the host has been quiet for longer than the timeout. */
    boolean keepAliveLapsed(long nanos) {
        if (!supervised || !heardFromHost || lapseReported || nanos - lastHeardNanos <= KEEP_ALIVE_TIMEOUT_NANOS) {
            return false;
        }
        lapseReported = true;
        return true;
    }

    void noteFailSafe() {
        events |= FAIL_SAFE;
    }

    void noteKeepAliveTimeout() {
        events |= KEEP_ALIVE_TIMEOUT | FAIL_SAFE;
    }

    int bits() {
        return events | (battery.getAsDouble() < LOW_BATTERY_VOLTS ? BATTERY_LOW : 0);
    }

    void clearEvents() {
        events = 0;
    }

    boolean attentionRequired() {
        return signalAttention && bits() != 0;
    }
}
