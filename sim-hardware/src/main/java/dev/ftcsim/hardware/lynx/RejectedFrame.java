package dev.ftcsim.hardware.lynx;

/**
 * Datagrams the bus ignored for one reason.
 *
 * @param fault          why they were ignored
 * @param firstSeenNanos simulated time of the first one
 * @param count          how many there were
 */
public record RejectedFrame(FrameFault fault, long firstSeenNanos, int count) {}
