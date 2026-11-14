package dev.ftcsim.link.wire;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads the payload of one frame, which is already in memory. Every length is checked against its bound and against
 * the bytes that are left before anything is allocated for it, so a corrupt payload cannot make the reader allocate
 * more than the payload itself is long.
 */
final class WireReader {

    private final ByteArrayInputStream source;
    private final DataInputStream in;

    WireReader(byte[] payload) {
        source = new ByteArrayInputStream(payload);
        in = new DataInputStream(source);
    }

    int remaining() {
        return source.available();
    }

    int unsignedByte() throws WireException {
        try {
            return in.readUnsignedByte();
        } catch (IOException eof) {
            throw truncated(eof);
        }
    }

    boolean bool() throws WireException {
        int value = unsignedByte();
        if (value > 1) {
            throw new WireException("a flag is " + value + ", which is neither 0 nor 1");
        }
        return value == 1;
    }

    int unsignedShort() throws WireException {
        try {
            return in.readUnsignedShort();
        } catch (IOException eof) {
            throw truncated(eof);
        }
    }

    int integer() throws WireException {
        try {
            return in.readInt();
        } catch (IOException eof) {
            throw truncated(eof);
        }
    }

    long longInteger() throws WireException {
        try {
            return in.readLong();
        } catch (IOException eof) {
            throw truncated(eof);
        }
    }

    float floatBits() throws WireException {
        return Float.intBitsToFloat(integer());
    }

    double doubleBits() throws WireException {
        return Double.longBitsToDouble(longInteger());
    }

    /** @param what names the text in error messages */
    String string(String what) throws WireException {
        return string(what, Protocol.MAX_STRING_BYTES);
    }

    /** @param what names the text in error messages */
    String string(String what, int maxBytes) throws WireException {
        int length = integer();
        if (length < 0 || length > maxBytes) {
            throw new WireException(what + " is " + length + " bytes of text, but the limit is " + maxBytes);
        }
        if (length > remaining()) {
            throw new WireException(what + " is " + length + " bytes of text, but only " + remaining() + " bytes are left in the message");
        }
        byte[] text = new byte[length];
        try {
            in.readFully(text);
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(text))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new WireException(what + " is not valid UTF-8", malformed);
        } catch (IOException eof) {
            throw truncated(eof);
        }
    }

    /**
     * The length of a list of entries that are at least {@code minimumEntryBytes} long each.
     *
     * @param what names the list in error messages
     */
    int count(int minimumEntryBytes, String what) throws WireException {
        int count = integer();
        if (count < 0 || count > Protocol.MAX_LIST) {
            throw new WireException(what + " has " + count + " entries, but the limit is " + Protocol.MAX_LIST);
        }
        if ((long) count * minimumEntryBytes > remaining()) {
            throw new WireException(
                    what + " has " + count + " entries, which cannot fit in the " + remaining() + " bytes left in the message");
        }
        return count;
    }

    /** Fails if anything is left: a message that is longer than its kind says is corrupt, not extended. */
    void end() throws WireException {
        if (remaining() != 0) {
            throw new WireException(remaining() + " unexpected bytes follow the end of the message");
        }
    }

    private static WireException truncated(IOException cause) {
        return new WireException("the message ends before it is complete", cause);
    }
}
