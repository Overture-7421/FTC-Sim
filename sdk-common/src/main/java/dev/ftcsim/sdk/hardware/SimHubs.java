package dev.ftcsim.sdk.hardware;

import android.content.Context;
import com.qualcomm.hardware.lynx.LynxAnalogInputController;
import com.qualcomm.hardware.lynx.LynxDcMotorController;
import com.qualcomm.hardware.lynx.LynxDigitalChannelController;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.lynx.LynxServoController;
import com.qualcomm.hardware.lynx.LynxVoltageSensor;
import com.qualcomm.robotcore.exception.RobotCoreException;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.LynxModuleDescription;
import com.qualcomm.robotcore.util.SerialNumber;
import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.HubWire;
import java.util.HashMap;
import java.util.Map;

/**
 * The simulated robot controller's hubs as the SDK sees them: one USB device carrying the SDK's own
 * {@link LynxModule} for each hub the profile declares, and the SDK's controllers on top of those, created the
 * first time a device needs them. Also remembers which port each name claimed, so that two names can never
 * silently share one physical port, and holds the link to the robot's smart cameras.
 */
final class SimHubs {

    /** How the SDK constructs one of its Lynx controllers. */
    @FunctionalInterface
    private interface ControllerFactory<T> {
        T create(Context context, LynxModule module) throws RobotCoreException, InterruptedException;
    }

    private record ControllerKey(int hubAddress, Class<?> type) {}

    private final Context context;
    private final HardwareDirectory directory;
    private final CameraWire cameras;
    private final SimLynxUsbDevice usbDevice;
    private final Map<Integer, LynxModule> modules = new HashMap<>();
    private final Map<ControllerKey, Object> controllers = new HashMap<>();
    private final Map<String, String> portClaims = new HashMap<>();

    SimHubs(Context context, HubWire wire, HardwareDirectory directory, CameraWire cameras) {
        this.context = context;
        this.directory = directory;
        this.cameras = cameras;
        this.usbDevice = new SimLynxUsbDevice(SerialNumber.createEmbedded(), wire);
    }

    HardwareDirectory directory() {
        return directory;
    }

    CameraWire cameras() {
        return cameras;
    }

    Context context() {
        return context;
    }

    /** Connects to every hub of the topology and registers them, with their voltage sensors, in the hardware map. */
    void populate(HardwareMap map) {
        try {
            for (HardwareDirectory.Hub hub : directory.hubs()) {
                LynxModuleDescription.Builder description = new LynxModuleDescription.Builder(hub.address(), hub.parent());
                description.setUserModule();
                LynxModule module = usbDevice.getOrAddModule(description.build());
                modules.put(hub.address(), module);
                map.put(module.getModuleSerialNumber(), hub.name(), module);
                map.voltageSensor.put(hub.name(), new LynxVoltageSensor(context, module));
            }
        } catch (RobotCoreException | RuntimeException failure) {
            throw new IllegalStateException("The simulated hubs did not answer while the robot controller started", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while the robot controller started", interrupted);
        }
        map.put(usbDevice.getSerialNumber(), "Control Hub Portal", usbDevice);
    }

    LynxModule module(int hubAddress) {
        LynxModule module = modules.get(hubAddress);
        if (module == null) {
            throw new IllegalArgumentException("The robot profile wires a device to hub " + hubAddress
                    + ", which is not one of the hubs it declares: " + modules.keySet());
        }
        return module;
    }

    LynxDcMotorController motorController(int hubAddress) {
        return controller(hubAddress, LynxDcMotorController.class, LynxDcMotorController::new);
    }

    LynxServoController servoController(int hubAddress) {
        return controller(hubAddress, LynxServoController.class, LynxServoController::new);
    }

    LynxDigitalChannelController digitalController(int hubAddress) {
        return controller(hubAddress, LynxDigitalChannelController.class, LynxDigitalChannelController::new);
    }

    LynxAnalogInputController analogController(int hubAddress) {
        return controller(hubAddress, LynxAnalogInputController.class, LynxAnalogInputController::new);
    }

    private <T> T controller(int hubAddress, Class<T> type, ControllerFactory<T> factory) {
        Object existing = controllers.get(new ControllerKey(hubAddress, type));
        if (existing != null) {
            return type.cast(existing);
        }
        try {
            T created = factory.create(context, module(hubAddress));
            controllers.put(new ControllerKey(hubAddress, type), created);
            return created;
        } catch (RobotCoreException failure) {
            throw new IllegalStateException("Could not set up the " + type.getSimpleName() + " of hub " + hubAddress, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while setting up the " + type.getSimpleName() + " of hub " + hubAddress, interrupted);
        }
    }

    /** Records that {@code name} uses the given port, failing if another name already does. */
    void claim(String portDescription, String name) {
        String previous = portClaims.putIfAbsent(portDescription, name);
        if (previous != null && !previous.equals(name)) {
            throw new IllegalStateException("\"" + name + "\" and \"" + previous + "\" are both wired to " + portDescription);
        }
    }
}
