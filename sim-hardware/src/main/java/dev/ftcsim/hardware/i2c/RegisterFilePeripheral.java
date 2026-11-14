package dev.ftcsim.hardware.i2c;

/**
 * The register-pointer protocol most I2C devices share: the first byte of a write selects a register, any further
 * bytes are stored at consecutive registers, and a read returns consecutive registers starting at the pointer,
 * which advances with every byte transferred and wraps after register 255.
 *
 * <p>Registers are backed by plain memory. A model that derives a register from the world overrides
 * {@link #readRegister}, and {@link #beforeRead} lets it take one snapshot per read so that multi-byte values stay
 * coherent.
 */
public abstract class RegisterFilePeripheral implements I2cPeripheral {

    private static final int REGISTER_COUNT = 256;

    private final int address;
    private final byte[] memory = new byte[REGISTER_COUNT];
    private int pointer;

    protected RegisterFilePeripheral(int address) {
        if (address < 0 || address > 127) {
            throw new IllegalArgumentException("not a 7-bit I2C address: " + address);
        }
        this.address = address;
    }

    @Override
    public final int address() {
        return address;
    }

    @Override
    public final void write(byte[] bytes, long nowNanos) {
        if (bytes.length == 0) {
            return;
        }
        int first = bytes[0] & 0xFF;
        pointer = first;
        for (int i = 1; i < bytes.length; i++) {
            writeRegister(pointer, bytes[i] & 0xFF, nowNanos);
            pointer = (pointer + 1) % REGISTER_COUNT;
        }
        if (bytes.length > 1) {
            afterWrite(first, bytes.length - 1, nowNanos);
        }
    }

    @Override
    public final byte[] read(int count, long nowNanos) {
        beforeRead(pointer, count, nowNanos);
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = (byte) readRegister(pointer, nowNanos);
            pointer = (pointer + 1) % REGISTER_COUNT;
        }
        return bytes;
    }

    /** Called once per read phase, before its first byte is fetched. */
    protected void beforeRead(int firstRegister, int count, long nowNanos) {}

    /** The byte a read returns from {@code register}, in 0..255. */
    protected int readRegister(int register, long nowNanos) {
        return peek(register);
    }

    /** Stores a byte a master wrote to {@code register}. */
    protected void writeRegister(int register, int value, long nowNanos) {
        poke(register, value);
    }

    /**
     * Called once at the end of a write phase that carried data, with the first register written and how many
     * were written. Devices whose registers act together, such as a command and its operands that must arrive in
     * one transaction, act here instead of byte by byte.
     */
    protected void afterWrite(int firstRegister, int count, long nowNanos) {}

    /** Reads the backing memory, bypassing the hooks. */
    protected final int peek(int register) {
        return memory[register] & 0xFF;
    }

    /** Writes the backing memory, bypassing the hooks; for initial register contents. */
    protected final void poke(int register, int value) {
        memory[register] = (byte) value;
    }
}
