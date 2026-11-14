package dev.ftcsim.hardware.i2c;

/**
 * A device answering at one 7-bit address on an I2C bus. Every access carries the simulated time at which the bus
 * transaction finishes, so a model can compute what it returns lazily from the world instead of ticking on its own.
 */
public interface I2cPeripheral {

    /** The 7-bit address the device acknowledges, in 0..127. */
    int address();

    /** Receives the bytes a master wrote to the device in one write phase. */
    void write(byte[] bytes, long nowNanos);

    /** Returns the {@code count} bytes the device sends during one read phase. */
    byte[] read(int count, long nowNanos);

    /** A write phase followed by a read phase after a repeated start, as a register read is done on the wire. */
    default byte[] writeRead(byte[] bytes, int count, long nowNanos) {
        write(bytes, nowNanos);
        return read(count, nowNanos);
    }
}
