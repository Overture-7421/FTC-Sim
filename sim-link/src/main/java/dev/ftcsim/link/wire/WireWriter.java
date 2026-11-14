package dev.ftcsim.link.wire;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds the payload of one frame: big-endian integers, floating point numbers as their IEEE bits (so that every
 * value, NaN payloads and the sign of zero included, comes back as it left), and text as a length and UTF-8 bytes.
 */
final class WireWriter {

    private static final byte[] ELLIPSIS = {(byte) 0xE2, (byte) 0x80, (byte) 0xA6};

    private final ByteArrayOutputStream bytes;
    private final DataOutputStream out;

    WireWriter(int expectedSize) {
        bytes = new ByteArrayOutputStream(expectedSize);
        out = new DataOutputStream(bytes);
    }

    void unsignedByte(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("not a byte: " + value);
        }
        try {
            out.writeByte(value);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    void bool(boolean value) {
        unsignedByte(value ? 1 : 0);
    }

    void unsignedShort(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("not an unsigned short: " + value);
        }
        try {
            out.writeShort(value);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    void integer(int value) {
        try {
            out.writeInt(value);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    void longInteger(long value) {
        try {
            out.writeLong(value);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    void floatBits(float value) {
        integer(Float.floatToRawIntBits(value));
    }

    void doubleBits(double value) {
        longInteger(Double.doubleToRawLongBits(value));
    }

    /** Text longer than {@link Protocol#MAX_STRING_BYTES} is cut at a character boundary and ends in an ellipsis. */
    void string(String value) {
        if (value == null) {
            throw new IllegalArgumentException("a text of the message is null");
        }
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        if (text.length > Protocol.MAX_STRING_BYTES) {
            text = cut(text);
        }
        integer(text.length);
        try {
            out.write(text);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** The length of a list, which must be within the bound the reader enforces. */
    void count(int size, String what) {
        if (size < 0 || size > Protocol.MAX_LIST) {
            throw new IllegalArgumentException(what + " has " + size + " entries but a message carries at most " + Protocol.MAX_LIST);
        }
        integer(size);
    }

    /** The payload so far. */
    byte[] finish() {
        if (bytes.size() > Protocol.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("the message is " + bytes.size() + " bytes but a frame carries at most " + Protocol.MAX_FRAME_BYTES);
        }
        return bytes.toByteArray();
    }

    private static byte[] cut(byte[] text) {
        int keep = Protocol.MAX_STRING_BYTES - ELLIPSIS.length;
        while (keep > 0 && (text[keep] & 0xC0) == 0x80) {
            keep--;
        }
        byte[] cut = new byte[keep + ELLIPSIS.length];
        System.arraycopy(text, 0, cut, 0, keep);
        System.arraycopy(ELLIPSIS, 0, cut, keep, ELLIPSIS.length);
        return cut;
    }
}
