package dev.ftcsim.hardware.lynx;

/**
 * One RHSP datagram. On the wire it is little endian: the frame bytes 0x44 0x4B, a 16-bit packet length that counts
 * the frame bytes and the checksum, destination and source module addresses, the sender's message number, the
 * message number being answered, a 16-bit packet id (bit 15 marks a response), the payload and an 8-bit additive
 * checksum over everything before it.
 */
public record Datagram(
        int destination, int source, int messageNumber, int referenceNumber, int packetId, byte[] payload) {

    /** The module address that every module accepts discovery on. */
    public static final int BROADCAST = 0xFF;

    static final int RESPONSE_BIT = 0x8000;

    private static final int FIXED_LENGTH = 11;
    private static final int FRAME_BYTE_0 = 0x44;
    private static final int FRAME_BYTE_1 = 0x4B;

    public Datagram {
        requireByte(destination, "destination");
        requireByte(source, "source");
        requireByte(messageNumber, "messageNumber");
        requireByte(referenceNumber, "referenceNumber");
        if (packetId < 0 || packetId > 0xFFFF) {
            throw new IllegalArgumentException("packetId out of range: " + packetId);
        }
        if (FIXED_LENGTH + payload.length > 0xFFFF) {
            throw new IllegalArgumentException("payload too long: " + payload.length);
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Whether the response bit of the packet id is set. */
    public boolean isResponse() {
        return (packetId & RESPONSE_BIT) != 0;
    }

    /** The packet id without the response bit. */
    public int commandNumber() {
        return packetId & ~RESPONSE_BIT;
    }

    public byte[] encode() {
        int length = FIXED_LENGTH + payload.length;
        byte[] frame = new byte[length];
        frame[0] = FRAME_BYTE_0;
        frame[1] = FRAME_BYTE_1;
        putShort(frame, 2, length);
        frame[4] = (byte) destination;
        frame[5] = (byte) source;
        frame[6] = (byte) messageNumber;
        frame[7] = (byte) referenceNumber;
        putShort(frame, 8, packetId);
        System.arraycopy(payload, 0, frame, 10, payload.length);
        frame[length - 1] = checksum(frame, length - 1);
        return frame;
    }

    /** Parses one complete datagram, rejecting it the way a hub that sees these bytes would. */
    public static Datagram parse(byte[] frame) throws MalformedFrameException {
        if (frame.length < FIXED_LENGTH) {
            throw new MalformedFrameException(FrameFault.TOO_SHORT);
        }
        if ((frame[0] & 0xFF) != FRAME_BYTE_0 || (frame[1] & 0xFF) != FRAME_BYTE_1) {
            throw new MalformedFrameException(FrameFault.BAD_FRAME_BYTES);
        }
        if (getShort(frame, 2) != frame.length) {
            throw new MalformedFrameException(FrameFault.LENGTH_MISMATCH);
        }
        if (checksum(frame, frame.length - 1) != frame[frame.length - 1]) {
            throw new MalformedFrameException(FrameFault.BAD_CHECKSUM);
        }
        byte[] payload = new byte[frame.length - FIXED_LENGTH];
        System.arraycopy(frame, 10, payload, 0, payload.length);
        return new Datagram(
                frame[4] & 0xFF, frame[5] & 0xFF, frame[6] & 0xFF, frame[7] & 0xFF, getShort(frame, 8), payload);
    }

    private static byte checksum(byte[] frame, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += frame[i];
        }
        return (byte) sum;
    }

    private static void putShort(byte[] frame, int offset, int value) {
        frame[offset] = (byte) value;
        frame[offset + 1] = (byte) (value >> 8);
    }

    private static int getShort(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | ((frame[offset + 1] & 0xFF) << 8);
    }

    private static void requireByte(int value, String name) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(name + " out of range: " + value);
        }
    }
}
