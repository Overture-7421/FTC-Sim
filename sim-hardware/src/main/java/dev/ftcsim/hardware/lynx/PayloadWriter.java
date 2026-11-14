package dev.ftcsim.hardware.lynx;

import java.io.ByteArrayOutputStream;

/** Builds the little-endian payload of a response. */
final class PayloadWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    PayloadWriter u8(int value) {
        out.write(value);
        return this;
    }

    PayloadWriter flag(boolean value) {
        return u8(value ? 1 : 0);
    }

    PayloadWriter u16(int value) {
        return u8(value).u8(value >> 8);
    }

    PayloadWriter i32(int value) {
        return u16(value).u16(value >> 16);
    }

    PayloadWriter bytes(byte[] value) {
        out.writeBytes(value);
        return this;
    }

    byte[] toBytes() {
        return out.toByteArray();
    }
}
