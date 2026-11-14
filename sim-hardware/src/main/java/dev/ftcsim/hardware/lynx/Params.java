package dev.ftcsim.hardware.lynx;

/**
 * Reads the parameters of a command from its payload. Parameters are counted as they are read, so a value that is
 * missing or out of range is rejected with the NACK code that names that parameter, as the firmware does.
 */
final class Params {

    private final byte[] data;
    private int offset;
    private int index;

    Params(byte[] data) {
        this.data = data;
    }

    int u8() {
        return read(1);
    }

    int u8(int min, int max) {
        return checked(read(1), min, max);
    }

    boolean flag() {
        return read(1) != 0;
    }

    int i16(int min, int max) {
        return checked((short) read(2), min, max);
    }

    int u16(int min, int max) {
        return checked(read(2), min, max);
    }

    int i32() {
        return read(4);
    }

    /** The next {@code count} bytes as one parameter. */
    byte[] bytes(int count) {
        if (offset + count > data.length) {
            throw new CommandRejected(NackReason.param(index));
        }
        byte[] bytes = new byte[count];
        System.arraycopy(data, offset, bytes, 0, count);
        offset += count;
        index++;
        return bytes;
    }

    /** Everything left, as one parameter. */
    byte[] rest() {
        return bytes(data.length - offset);
    }

    /** Rejects a payload that carries more than the command defines. */
    void end() {
        if (offset != data.length) {
            throw new CommandRejected(NackReason.param(index));
        }
    }

    private int checked(int value, int min, int max) {
        if (value < min || value > max) {
            throw new CommandRejected(NackReason.param(index - 1));
        }
        return value;
    }

    private int read(int size) {
        if (offset + size > data.length) {
            throw new CommandRejected(NackReason.param(index));
        }
        int value = 0;
        for (int i = 0; i < size; i++) {
            value |= (data[offset + i] & 0xFF) << (8 * i);
        }
        offset += size;
        index++;
        return value;
    }
}
