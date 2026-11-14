package dev.ftcsim.physics;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;

/** SHA-256 over fixed-width big-endian fields, so equal state always yields equal bytes. */
final class StateDigest {

    private final MessageDigest digest = Sha256.newDigest();
    private final ByteBuffer scratch = ByteBuffer.allocate(Long.BYTES);

    StateDigest putInt(int value) {
        scratch.clear();
        scratch.putInt(value);
        digest.update(scratch.array(), 0, Integer.BYTES);
        return this;
    }

    StateDigest putLong(long value) {
        scratch.clear();
        scratch.putLong(value);
        digest.update(scratch.array(), 0, Long.BYTES);
        return this;
    }

    StateDigest putFloat(float value) {
        return putInt(Float.floatToRawIntBits(value));
    }

    StateDigest putDouble(double value) {
        return putLong(Double.doubleToRawLongBits(value));
    }

    StateDigest putBytes(byte[] bytes) {
        digest.update(bytes);
        return this;
    }

    String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
