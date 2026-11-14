package dev.ftcsim.hardware.lynx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Builds little-endian payloads for tests, independently of the production writer. */
final class Bytes {

    private final ByteBuffer buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);

    static Bytes of() {
        return new Bytes();
    }

    static byte[] none() {
        return new byte[0];
    }

    Bytes u8(int value) {
        buffer.put((byte) value);
        return this;
    }

    Bytes u16(int value) {
        buffer.putShort((short) value);
        return this;
    }

    Bytes i32(int value) {
        buffer.putInt(value);
        return this;
    }

    Bytes raw(byte[] value) {
        buffer.put(value);
        return this;
    }

    byte[] build() {
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
}
