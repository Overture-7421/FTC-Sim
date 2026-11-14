package dev.ftcsim.hardware.lynx;

import java.util.ArrayList;
import java.util.List;

/**
 * The eight digital pins of a hub. Pins power up as inputs; an input that nothing is connected to reads high, as
 * the hub's pull-ups make it, and an output pin reads back the level it drives.
 */
final class DigitalBank {

    static final int COUNT = 8;

    private final boolean[] isOutput = new boolean[COUNT];
    private final boolean[] driven = new boolean[COUNT];
    private final List<Port<DigitalInput>> ports = new ArrayList<>();
    private final Diagnostics diagnostics;

    DigitalBank(int hubAddress, Diagnostics diagnostics) {
        this.diagnostics = diagnostics;
        for (int pin = 0; pin < COUNT; pin++) {
            ports.add(new Port<>("hub " + hubAddress + " digital port " + pin));
        }
    }

    Port<DigitalInput> port(int pin) {
        return ports.get(pin);
    }

    boolean isOutput(int pin) {
        return isOutput[pin];
    }

    /** The level an output pin drives. */
    boolean driven(int pin) {
        return driven[pin];
    }

    int drivenHighCount() {
        int count = 0;
        for (int pin = 0; pin < COUNT; pin++) {
            if (isOutput[pin] && driven[pin]) {
                count++;
            }
        }
        return count;
    }

    /** Turning a pin into an output starts it low. */
    void setDirection(int pin, boolean toOutput) {
        if (toOutput && !isOutput[pin]) {
            driven[pin] = false;
        }
        isOutput[pin] = toOutput;
    }

    void setOutput(int pin, boolean high) {
        if (!isOutput[pin]) {
            throw new CommandRejected(NackReason.gpioOut(pin));
        }
        driven[pin] = high;
    }

    /** Sets every output pin from its bit; the bits of input pins are ignored. */
    void setOutputs(int bits) {
        boolean anyOutput = false;
        for (int pin = 0; pin < COUNT; pin++) {
            if (isOutput[pin]) {
                driven[pin] = (bits & (1 << pin)) != 0;
                anyOutput = true;
            }
        }
        if (!anyOutput) {
            throw new CommandRejected(NackReason.GPIO_NO_OUTPUT);
        }
    }

    /** Reads one input pin on behalf of robot code, noting when nothing is connected to it. */
    boolean readInput(int pin, long nowNanos) {
        if (isOutput[pin]) {
            throw new CommandRejected(NackReason.gpioIn(pin));
        }
        if (!ports.get(pin).isConnected()) {
            diagnostics.digitalReadWhileUnbound(pin);
        }
        return level(pin, nowNanos);
    }

    /** Reads all input pins at once; it is refused when every pin is an output. */
    int readInputs(long nowNanos) {
        boolean anyInput = false;
        for (boolean output : isOutput) {
            anyInput |= !output;
        }
        if (!anyInput) {
            throw new CommandRejected(NackReason.GPIO_NO_INPUT);
        }
        return levels(nowNanos);
    }

    /** The level of every pin as a bit mask, the way the bulk read reports them. */
    int levels(long nowNanos) {
        int bits = 0;
        for (int pin = 0; pin < COUNT; pin++) {
            if (level(pin, nowNanos)) {
                bits |= 1 << pin;
            }
        }
        return bits;
    }

    private boolean level(int pin, long nowNanos) {
        if (isOutput[pin]) {
            return driven[pin];
        }
        DigitalInput source = ports.get(pin).device();
        return source == null || source.level(nowNanos);
    }
}
