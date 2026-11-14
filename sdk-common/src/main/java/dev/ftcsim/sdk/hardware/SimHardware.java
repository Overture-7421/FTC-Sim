package dev.ftcsim.sdk.hardware;

import android.content.Context;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.hardware.HardwareMap;
import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.HubWire;
import java.util.List;

/** Entry point for the SDK-side simulated hardware: a hardware map whose devices appear when robot code asks. */
public final class SimHardware {
    private SimHardware() {
    }

    /** A robot controller whose host has no cameras: see {@link #createHardwareMap(Context, OpModeManagerNotifier, HubWire, HardwareDirectory, CameraWire)}. */
    public static HardwareMap createHardwareMap(
            Context context, OpModeManagerNotifier notifier, HubWire wire, HardwareDirectory directory) {
        return createHardwareMap(context, notifier, wire, directory, CameraWire.NONE);
    }

    /**
     * Creates the hardware map robot code sees. The hubs of the topology are connected and registered at once,
     * as on a real robot controller; every other device is created on first lookup, wired where
     * {@code directory} says, and a camera is whatever {@code cameras} answers for its name.
     */
    public static HardwareMap createHardwareMap(
            Context context, OpModeManagerNotifier notifier, HubWire wire, HardwareDirectory directory, CameraWire cameras) {
        ConfigurationTypes.registerBuiltIns();
        SimHubs hubs = new SimHubs(context, wire, directory, cameras);
        SimHardwareMap map = new SimHardwareMap(
                context, notifier, hubs,
                List.of(
                        new MotorProvider(), new ServoProvider(), new DigitalInputProvider(), new AnalogInputProvider(),
                        new I2cDriverProvider(), new LimelightProvider()));
        hubs.populate(map);
        return map;
    }
}
