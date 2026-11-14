package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HubWire;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * A host that talks RHSP to a bus the way the SDK does, built from the wire layouts only: it frames and checksums
 * by hand, so that it checks the production codec instead of sharing its bugs.
 */
final class RhspClient {

    /** A reply datagram, decoded by hand. */
    record Reply(int source, int messageNumber, int referenceNumber, int packetId, byte[] payload, long latencyNanos) {

        boolean isAck() {
            return packetId == 0x7F01;
        }

        boolean isNack() {
            return packetId == 0x7F02;
        }

        int nackReason() {
            assertTrue(isNack(), "expected a NACK but got packet 0x" + Integer.toHexString(packetId));
            return payload[0] & 0xFF;
        }

        boolean attentionRequired() {
            assertTrue(isAck(), "expected an ACK but got packet 0x" + Integer.toHexString(packetId));
            return payload[0] != 0;
        }

        ByteBuffer data() {
            return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private final HubWire wire;
    private final int destination;
    private int nextMessageNumber = 1;
    private LongConsumer afterExchange = latency -> {};
    private boolean unscheduled;

    RhspClient(HubWire wire, int destination) {
        this.wire = wire;
        this.destination = destination;
    }

    /** Lets simulated time pass during each exchange, as the SDK adapter does, so that polling makes progress. */
    RhspClient advancingTimeWith(LongConsumer consumer) {
        afterExchange = consumer;
        return this;
    }

    /** Sends everything as a thread the simulation does not schedule would. */
    RhspClient unscheduled() {
        unscheduled = true;
        return this;
    }

    /** Sends one datagram and returns its single reply. */
    Reply send(int packetId, byte[] payload) {
        byte[] frame = frame(destination, nextMessageNumber(), 0, packetId, payload);
        HubWire.Exchange exchange = unscheduled ? wire.transmitUnscheduled(frame) : wire.transmit(frame);
        afterExchange.accept(exchange.latencyNanos());
        assertEquals(1, exchange.replies().size(), "replies to packet 0x" + Integer.toHexString(packetId));
        return decode(exchange.replies().get(0), exchange.latencyNanos());
    }

    Reply deka(DekaCommand command, byte[] payload) {
        return send(0x1000 + command.ordinal(), payload);
    }

    Reply standard(StandardCommand command, byte[] payload) {
        return send(command.number(), payload);
    }

    /** Sends a DEKA command that must be acknowledged. */
    Reply ack(DekaCommand command, byte[] payload) {
        Reply reply = deka(command, payload);
        assertTrue(reply.isAck(), command + " was not acknowledged: " + describe(reply));
        return reply;
    }

    /** Sends a DEKA command that must be answered with its response packet and returns the response payload. */
    ByteBuffer response(DekaCommand command, byte[] payload) {
        Reply reply = deka(command, payload);
        assertEquals(0x9000 + command.ordinal(), reply.packetId(), command + " was not answered: " + describe(reply));
        return reply.data();
    }

    /** Sends a DEKA command that must be refused and returns the NACK reason. */
    int nack(DekaCommand command, byte[] payload) {
        Reply reply = deka(command, payload);
        assertTrue(reply.isNack(), command + " was not refused: " + describe(reply));
        return reply.nackReason();
    }

    static byte[] frame(int destination, int messageNumber, int referenceNumber, int packetId, byte[] payload) {
        int length = 11 + payload.length;
        byte[] frame = new byte[length];
        frame[0] = 0x44;
        frame[1] = 0x4B;
        frame[2] = (byte) length;
        frame[3] = (byte) (length >> 8);
        frame[4] = (byte) destination;
        frame[5] = 0; // the SDK never sets a source address
        frame[6] = (byte) messageNumber;
        frame[7] = (byte) referenceNumber;
        frame[8] = (byte) packetId;
        frame[9] = (byte) (packetId >> 8);
        System.arraycopy(payload, 0, frame, 10, payload.length);
        int sum = 0;
        for (int i = 0; i < length - 1; i++) {
            sum += frame[i];
        }
        frame[length - 1] = (byte) sum;
        return frame;
    }

    static Reply decode(byte[] frame, long latencyNanos) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x44, buffer.get() & 0xFF);
        assertEquals(0x4B, buffer.get() & 0xFF);
        assertEquals(frame.length, buffer.getShort() & 0xFFFF, "length field");
        assertEquals(0, buffer.get() & 0xFF, "replies are addressed to the host");
        int source = buffer.get() & 0xFF;
        int messageNumber = buffer.get() & 0xFF;
        int reference = buffer.get() & 0xFF;
        int packetId = buffer.getShort() & 0xFFFF;
        byte[] payload = new byte[frame.length - 11];
        buffer.get(payload);
        int sum = 0;
        for (int i = 0; i < frame.length - 1; i++) {
            sum += frame[i];
        }
        assertEquals((byte) sum, buffer.get(), "checksum");
        return new Reply(source, messageNumber, reference, packetId, payload, latencyNanos);
    }

    /** The frames a hub sends back for one request, decoded. */
    static List<Reply> decodeAll(HubWire.Exchange exchange) {
        return exchange.replies().stream()
                .map(frame -> decode(frame, exchange.latencyNanos()))
                .toList();
    }

    private int nextMessageNumber() {
        int number = nextMessageNumber;
        nextMessageNumber = number == 255 ? 1 : number + 1;
        return number;
    }

    private static String describe(Reply reply) {
        return reply.isNack() ? "NACK " + reply.nackReason() : "packet 0x" + Integer.toHexString(reply.packetId());
    }
}
