package dev.ftcsim.hardware.lynx;

import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;
import java.util.ArrayList;
import java.util.List;

/** A register-file device that notes every access with its simulated time. */
final class MemoryDevice extends RegisterFilePeripheral {

    final List<String> log = new ArrayList<>();

    MemoryDevice(int address) {
        super(address);
    }

    MemoryDevice with(int register, int... values) {
        for (int i = 0; i < values.length; i++) {
            poke(register + i, values[i]);
        }
        return this;
    }

    @Override
    protected void beforeRead(int firstRegister, int count, long nowNanos) {
        log.add("read " + count + " from " + firstRegister + " at " + nowNanos);
    }

    @Override
    protected void writeRegister(int register, int value, long nowNanos) {
        log.add("write " + value + " to " + register + " at " + nowNanos);
        super.writeRegister(register, value, nowNanos);
    }
}
