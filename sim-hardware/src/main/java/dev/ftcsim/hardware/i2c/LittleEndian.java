package dev.ftcsim.hardware.i2c;

/** The little-endian integers and floats that register maps of I2C devices are made of. */
public final class LittleEndian {

    private LittleEndian() {}

    public static byte[] bytes(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    public static byte[] bytes(float value) {
        return bytes(Float.floatToIntBits(value));
    }

    /** The low two bytes of {@code value}. */
    public static byte[] shortBytes(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8)};
    }

    public static int intAt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    public static float floatAt(byte[] data, int offset) {
        return Float.intBitsToFloat(intAt(data, offset));
    }

    public static int unsignedShortAt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }
}
