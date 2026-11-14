package dev.ftcsim.hardware.lynx;

import dev.ftcsim.hardware.i2c.I2cPeripheral;
import java.util.TreeMap;

/**
 * One I2C master of a hub. A transaction is accepted at once and finishes later in simulated time: it takes the
 * time its bytes need on the wire at the configured clock plus a fixed allowance for the firmware, and while it
 * runs the bus refuses new transactions and answers status queries with "in progress". The peripheral is accessed
 * when the transaction finishes, so a model is asked for its registers at the time the data is really transferred.
 */
final class I2cBus {

    /** The status byte of a transaction in which every byte was acknowledged. */
    static final int STATUS_OK = 0;

    /** The status byte of a transaction whose address nobody acknowledged (the MADDR bit). */
    static final int STATUS_ADDRESS_NOT_ACKNOWLEDGED = 1;

    /** An estimate of the firmware's time to start a transaction and unpack its result. */
    private static final long FIRMWARE_OVERHEAD_NANOS = 150_000L;

    private static final long[] CLOCK_HZ = {100_000L, 400_000L};

    /** Eight data bits and the acknowledge bit. */
    private static final int CLOCKS_PER_BYTE = 9;

    /** The start condition and the stop (or repeated start) condition of a phase. */
    private static final int CLOCKS_PER_PHASE = 2;

    private static final byte[] NO_BYTES = new byte[0];

    private enum State {
        IDLE,
        BUSY,
        WRITE_DONE,
        READ_DONE
    }

    private final int index;
    private final Diagnostics diagnostics;
    private final TreeMap<Integer, I2cPeripheral> peripherals = new TreeMap<>();
    private int internalDevices;

    private int speedCode;
    private State state = State.IDLE;
    private I2cOperation operation;
    private long completeAtNanos;
    private int status;
    private int bytesWritten;
    private byte[] bytesRead = NO_BYTES;

    I2cBus(int index, Diagnostics diagnostics) {
        this.index = index;
        this.diagnostics = diagnostics;
    }

    /** Puts a device on the bus; {@code internal} ones are inside the hub, so no port supplies them. */
    void attach(I2cPeripheral peripheral, boolean internal) {
        int address = peripheral.address();
        if (address < 0 || address > 127) {
            throw new IllegalArgumentException("not a 7-bit I2C address: " + address);
        }
        if (peripherals.putIfAbsent(address, peripheral) != null) {
            throw new IllegalStateException(
                    "I2C bus " + index + " already has a device at address 0x" + Integer.toHexString(address));
        }
        if (internal) {
            internalDevices++;
        }
    }

    boolean answersAt(int address) {
        return peripherals.containsKey(address);
    }

    /** How many devices a port supplies: those on the bus except the ones inside the hub. */
    int deviceCount() {
        return peripherals.size() - internalDevices;
    }

    int speedCode() {
        return speedCode;
    }

    static boolean supportsSpeedCode(int code) {
        return code >= 0 && code < CLOCK_HZ.length;
    }

    void setSpeedCode(int code) {
        speedCode = code;
    }

    /** Starts a transaction, unless the previous one is still running. */
    void start(I2cOperation next, long nowNanos) {
        settle(nowNanos);
        if (state == State.BUSY) {
            throw new CommandRejected(NackReason.I2C_MASTER_BUSY);
        }
        boolean present = peripherals.containsKey(next.address());
        if (!present) {
            diagnostics.i2cAddressUnanswered(index, next.address());
        }
        operation = next;
        completeAtNanos = nowNanos + durationNanos(next, present);
        state = State.BUSY;
    }

    /** Finishes the running transaction if its time has come, talking to the peripheral as it does. */
    void settle(long nowNanos) {
        if (state != State.BUSY || nowNanos < completeAtNanos) {
            return;
        }
        I2cPeripheral device = peripherals.get(operation.address());
        if (device == null) {
            status = STATUS_ADDRESS_NOT_ACKNOWLEDGED;
            bytesWritten = 0;
            bytesRead = NO_BYTES;
        } else {
            status = STATUS_OK;
            if (operation.writeBytes().length > 0) {
                device.write(operation.writeBytes(), completeAtNanos);
            }
            bytesWritten = operation.writeBytes().length;
            bytesRead = operation.isRead() ? device.read(operation.readCount(), completeAtNanos) : NO_BYTES;
        }
        state = operation.isRead() ? State.READ_DONE : State.WRITE_DONE;
    }

    /** The response payload of a read status query: status, byte count and the bytes. */
    PayloadWriter readResult(long nowNanos) {
        settle(nowNanos);
        requireResult(State.READ_DONE);
        state = State.IDLE;
        return new PayloadWriter().u8(status).u8(bytesRead.length).bytes(bytesRead);
    }

    /** The response payload of a write status query: status and the number of bytes written. */
    PayloadWriter writeResult(long nowNanos) {
        settle(nowNanos);
        requireResult(State.WRITE_DONE);
        state = State.IDLE;
        return new PayloadWriter().u8(status).u8(bytesWritten);
    }

    private void requireResult(State expected) {
        if (state == State.BUSY) {
            throw new CommandRejected(NackReason.I2C_OPERATION_IN_PROGRESS);
        }
        if (state == State.IDLE) {
            throw new CommandRejected(NackReason.I2C_NO_RESULTS_PENDING);
        }
        if (state != expected) {
            throw new CommandRejected(NackReason.I2C_QUERY_MISMATCH);
        }
    }

    /** A missing device ends the transaction after the address byte, which nobody acknowledges. */
    private long durationNanos(I2cOperation next, boolean present) {
        int clocks = phaseClocks(0);
        if (present) {
            clocks = 0;
            if (next.writeBytes().length > 0) {
                clocks += phaseClocks(next.writeBytes().length);
            }
            if (next.readCount() > 0) {
                clocks += phaseClocks(next.readCount());
            }
        }
        return clocks * 1_000_000_000L / CLOCK_HZ[speedCode] + FIRMWARE_OVERHEAD_NANOS;
    }

    private static int phaseClocks(int dataBytes) {
        return CLOCKS_PER_BYTE * (1 + dataBytes) + CLOCKS_PER_PHASE;
    }
}
