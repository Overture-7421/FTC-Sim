package dev.ftcsim.link.wire;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed frames: a big-endian 32-bit length of 1 to {@link Protocol#MAX_FRAME_BYTES}, then that many
 * payload bytes. The first payload byte says what the message is.
 */
public final class Frames {

    private Frames() {}

    /** Writes one frame. The caller flushes, and serialises its writers: a frame is written in two calls. */
    public static void write(OutputStream out, byte[] payload) throws IOException {
        int length = payload.length;
        if (length < 1 || length > Protocol.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("a frame carries 1 to " + Protocol.MAX_FRAME_BYTES + " bytes, not " + length);
        }
        out.write(new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
        out.write(payload);
    }

    /**
     * Reads the payload of the next frame, blocking until it is complete.
     *
     * @return the payload, or null when the stream ended cleanly between two frames
     * @throws WireException if the stream ends inside a frame or announces a length outside the bounds
     */
    public static byte[] read(InputStream in) throws IOException {
        byte[] header = new byte[4];
        int got = in.readNBytes(header, 0, header.length);
        if (got == 0) {
            return null;
        }
        if (got < header.length) {
            throw new WireException("the stream ends inside a frame header, after " + got + " of 4 bytes");
        }
        int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length < 1 || length > Protocol.MAX_FRAME_BYTES) {
            throw new WireException("a frame announces " + length + " bytes, but a frame carries 1 to " + Protocol.MAX_FRAME_BYTES);
        }
        // readNBytes grows its buffer as bytes arrive, so a length that the stream cannot back costs nothing.
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new WireException("the stream ends inside a frame, after " + payload.length + " of " + length + " bytes");
        }
        return payload;
    }
}
