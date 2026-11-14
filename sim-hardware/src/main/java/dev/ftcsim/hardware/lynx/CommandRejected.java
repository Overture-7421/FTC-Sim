package dev.ftcsim.hardware.lynx;

/** A command the firmware refuses; the hub turns it into a NACK carrying {@link #reason()}. */
final class CommandRejected extends RuntimeException {

    private final int reason;

    CommandRejected(int reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    int reason() {
        return reason;
    }
}
