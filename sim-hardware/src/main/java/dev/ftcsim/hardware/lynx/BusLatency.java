package dev.ftcsim.hardware.lynx;

/**
 * How long a datagram exchange takes on the serial bus, in simulated time. These are estimates built from the link
 * speed, not measurements of a real robot controller.
 *
 * @param parentBaseNanos    fixed cost of an exchange with the hub on the USB (or embedded) link
 * @param perByteNanos       time one byte of request or reply occupies the link
 * @param childExtraNanos    extra cost of reaching a child hub over the RS485 bus behind the parent
 * @param discoveryScanNanos how long the parent takes to scan the RS485 bus for children during discovery
 */
public record BusLatency(long parentBaseNanos, long perByteNanos, long childExtraNanos, long discoveryScanNanos) {

    private static final long MAX_CHILD_ADDRESSES = 254;

    public BusLatency {
        if (parentBaseNanos < 0 || perByteNanos < 0 || childExtraNanos < 0 || discoveryScanNanos < 0) {
            throw new IllegalArgumentException("latencies cannot be negative");
        }
    }

    /**
     * 1.0 ms of host and firmware turnaround per exchange; 20 us per byte, about 460.8 kbaud with start and stop
     * bits; 0.8 ms more for the hop to a child hub; and a 2 ms wait per possible child address during discovery,
     * which the SDK documents as about half a second in all.
     */
    public static BusLatency estimate() {
        return new BusLatency(1_000_000L, 20_000L, 800_000L, MAX_CHILD_ADDRESSES * 2_000_000L);
    }

    long exchange(boolean child, int requestBytes, int replyBytes) {
        return parentBaseNanos + (child ? childExtraNanos : 0) + perByteNanos * (requestBytes + replyBytes);
    }

    /** A datagram nobody answers still takes the time to send. */
    long unanswered(int requestBytes) {
        return perByteNanos * requestBytes;
    }

    long discovery(int requestBytes, int replyBytes) {
        return parentBaseNanos + discoveryScanNanos + perByteNanos * (requestBytes + replyBytes);
    }
}
