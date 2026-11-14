package dev.ftcsim.hardware.lynx;

import java.util.Objects;

/** A connector on a hub that a simulated device is plugged into. */
public final class Port<T> {

    private final String name;
    private T device;

    Port(String name) {
        this.name = name;
    }

    /** Plugs the device in; a port holds one device for the life of the hub. */
    public void connect(T device) {
        Objects.requireNonNull(device, "device");
        if (this.device != null) {
            throw new IllegalStateException(name + " already has a device connected");
        }
        this.device = device;
    }

    public boolean isConnected() {
        return device != null;
    }

    /** The connected device, or null when the port is open. */
    T device() {
        return device;
    }
}
