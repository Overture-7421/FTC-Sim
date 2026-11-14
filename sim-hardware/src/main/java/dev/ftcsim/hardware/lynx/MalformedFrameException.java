package dev.ftcsim.hardware.lynx;

/** Thrown by {@link Datagram#parse} for bytes that a real hub would discard. */
public final class MalformedFrameException extends Exception {

    private final FrameFault fault;

    MalformedFrameException(FrameFault fault) {
        super(fault.name(), null, false, false);
        this.fault = fault;
    }

    public FrameFault fault() {
        return fault;
    }
}
