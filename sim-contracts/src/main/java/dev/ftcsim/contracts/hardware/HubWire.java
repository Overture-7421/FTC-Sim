package dev.ftcsim.contracts.hardware;

import java.util.List;

/**
 * The serial bus between a robot controller and its REV hubs, at the level of whole datagrams of the REV Hub
 * Serial Protocol (RHSP). The simulated hubs sit behind this interface and the SDK adapter sits in front of it,
 * so neither knows the other's types and the bus carries exactly the bytes a real one would.
 */
public interface HubWire {

    /**
     * What the bus did with one datagram.
     *
     * @param replies      the datagrams sent back, in order; usually one acknowledgement, negative acknowledgement
     *                     or response, and none for a command that expects neither
     * @param latencyNanos simulated time the robot spends waiting for the exchange to complete
     */
    record Exchange(List<byte[]> replies, long latencyNanos) {
        public Exchange {
            replies = List.copyOf(replies);
            if (latencyNanos < 0) {
                throw new IllegalArgumentException("latency cannot be negative: " + latencyNanos);
            }
        }
    }

    /**
     * Delivers one datagram to the hub it is addressed to, from a thread the simulation schedules: the one that
     * advances the simulation or the robot thread that runs between its moves.
     *
     * @param datagram a complete datagram, from the two frame bytes through the checksum
     */
    Exchange transmit(byte[] datagram);

    /**
     * A datagram from a thread the simulation does not schedule, such as a library's own timer. Safe from any thread
     * at any time. It never moves simulated time: the hubs answer as they stand at the time the scheduled side last
     * reached. The exchange's latency is zero.
     *
     * @param datagram a complete datagram, from the two frame bytes through the checksum
     */
    Exchange transmitUnscheduled(byte[] datagram);
}
