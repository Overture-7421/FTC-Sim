package android.hardware.usb;

import java.util.HashMap;

/** Android's USB service. A simulated robot controller has no USB bus, so it never reports a device. */
public class UsbManager {
    public HashMap<String, UsbDevice> getDeviceList() {
        return new HashMap<>();
    }
}
