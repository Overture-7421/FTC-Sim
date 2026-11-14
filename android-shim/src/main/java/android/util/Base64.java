package android.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Base64 as Android does it: the standard or the URL-safe alphabet, optional padding, and, unless
 * {@link #NO_WRAP} is given, output in lines of 76 characters that each end in a line terminator, the last one
 * included. Decoding ignores spaces, tabs and line breaks and rejects everything else.
 */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    /** Only meaningful for the stream wrappers, which the shim does not provide. */
    public static final int NO_CLOSE = 16;

    private static final int LINE_LENGTH = 76;

    private Base64() {}

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] input, int offset, int length, int flags) {
        return new String(encode(input, offset, length, flags), StandardCharsets.US_ASCII);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encode(input, 0, input.length, flags);
    }

    public static byte[] encode(byte[] input, int offset, int length, int flags) {
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) == 0 ? java.util.Base64.getEncoder() : java.util.Base64.getUrlEncoder();
        if ((flags & NO_PADDING) != 0) {
            encoder = encoder.withoutPadding();
        }
        byte[] encoded = encoder.encode(Arrays.copyOfRange(input, offset, offset + length));
        return (flags & NO_WRAP) != 0 ? encoded : wrapped(encoded, (flags & CRLF) != 0 ? "\r\n" : "\n");
    }

    public static byte[] decode(String input, int flags) {
        return decode(input.getBytes(StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decode(input, 0, input.length, flags);
    }

    public static byte[] decode(byte[] input, int offset, int length, int flags) {
        ByteArrayOutputStream significant = new ByteArrayOutputStream(length);
        for (int i = offset; i < offset + length; i++) {
            if (input[i] != ' ' && input[i] != '\t' && input[i] != '\n' && input[i] != '\r') {
                significant.write(input[i]);
            }
        }
        java.util.Base64.Decoder decoder = (flags & URL_SAFE) == 0 ? java.util.Base64.getDecoder() : java.util.Base64.getUrlDecoder();
        try {
            return decoder.decode(significant.toByteArray());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base-64", e);
        }
    }

    private static byte[] wrapped(byte[] encoded, String terminator) {
        byte[] end = terminator.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream lines = new ByteArrayOutputStream(encoded.length + (encoded.length / LINE_LENGTH + 1) * end.length);
        for (int start = 0; start < encoded.length; start += LINE_LENGTH) {
            lines.write(encoded, start, Math.min(LINE_LENGTH, encoded.length - start));
            lines.write(end, 0, end.length);
        }
        return lines.toByteArray();
    }
}
