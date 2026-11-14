package dev.ftcsim.hardware.lynx;

/**
 * A command or option that robot code used and the emulation does not model; the hub answered with a NACK.
 *
 * @param packetId       the packet id of the command
 * @param description    what was not modelled
 * @param firstSeenNanos simulated time of the first occurrence
 * @param count          how many times it occurred
 */
public record UnsupportedUse(int packetId, String description, long firstSeenNanos, int count) {}
