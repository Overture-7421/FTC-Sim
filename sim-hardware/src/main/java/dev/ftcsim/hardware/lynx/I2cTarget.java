package dev.ftcsim.hardware.lynx;

/** A device address on one I2C bus of a hub. */
public record I2cTarget(int bus, int address) implements Comparable<I2cTarget> {

    @Override
    public int compareTo(I2cTarget other) {
        int byBus = Integer.compare(bus, other.bus);
        return byBus != 0 ? byBus : Integer.compare(address, other.address);
    }
}
