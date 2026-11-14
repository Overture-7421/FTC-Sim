package dev.ftcsim.hardware.lynx;

import java.util.Objects;

/**
 * What a hub is. The firmware options default to the behaviour of a real hub; a host that cannot run the SDK's
 * keep-alive timers switches the supervision off and nothing but its own commands changes the hub's state.
 *
 * @param address             the module address, 1..254
 * @param parent              whether the hub is the one directly connected to the robot controller; the others
 *                            sit behind it on the RS485 bus
 * @param serial              an identifier for the UI; it is not part of the protocol
 * @param hardwareRevision    the number a hub reports in the "HW" field of its version string
 * @param keepAliveSupervision whether the hub fails safe when the host has been silent for 2.5 s; any datagram
 *                            addressed to the hub counts as a keep-alive, and the clock starts at the first one
 * @param signalAttention     whether acknowledgements flag a pending module status, which makes the SDK fetch it
 *                            on a thread of its own
 */
public record HubConfig(
        int address,
        boolean parent,
        String serial,
        int hardwareRevision,
        boolean keepAliveSupervision,
        boolean signalAttention) {

    /** The address of the module embedded in a Control Hub. */
    public static final int EMBEDDED_ADDRESS = 173;

    public HubConfig {
        if (address < 1 || address > 254) {
            throw new IllegalArgumentException("module address must be 1..254: " + address);
        }
        if (hardwareRevision < 0) {
            throw new IllegalArgumentException("hardware revision cannot be negative: " + hardwareRevision);
        }
        Objects.requireNonNull(serial, "serial");
    }

    /** The module embedded in a Control Hub: the parent, at the fixed embedded address. */
    public static HubConfig controlHub() {
        return new HubConfig(EMBEDDED_ADDRESS, true, "(embedded)", 20, true, true);
    }

    /** An Expansion Hub, which is a parent when it is the one connected to the robot controller by USB. */
    public static HubConfig expansionHub(int address, boolean parent, String serial) {
        return new HubConfig(address, parent, serial, 20, true, true);
    }

    public HubConfig withKeepAliveSupervision(boolean enabled) {
        return new HubConfig(address, parent, serial, hardwareRevision, enabled, signalAttention);
    }

    public HubConfig withAttentionSignalling(boolean enabled) {
        return new HubConfig(address, parent, serial, hardwareRevision, keepAliveSupervision, enabled);
    }
}
