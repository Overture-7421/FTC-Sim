package dev.ftcsim.hardware.lynx;

/**
 * One I2C bus transaction as the firmware runs it: an optional write phase followed by an optional read phase,
 * with a repeated start between them.
 */
record I2cOperation(int address, byte[] writeBytes, int readCount) {

    static I2cOperation write(int address, byte[] bytes) {
        return new I2cOperation(address, bytes, 0);
    }

    static I2cOperation read(int address, int count) {
        return new I2cOperation(address, new byte[0], count);
    }

    /** Writes the register pointer, then reads {@code count} bytes from it. */
    static I2cOperation writeRead(int address, int register, int count) {
        return new I2cOperation(address, new byte[] {(byte) register}, count);
    }

    /** Whether the host collects the result with a read status query rather than a write status query. */
    boolean isRead() {
        return readCount > 0;
    }
}
