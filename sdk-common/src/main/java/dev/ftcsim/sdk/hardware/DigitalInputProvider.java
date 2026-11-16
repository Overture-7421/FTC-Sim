package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.lynx.LynxDigitalChannelController;
import com.qualcomm.hardware.rev.RevTouchSensor;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.DigitalChannelImpl;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.TouchSensor;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import java.util.Set;

/**
 * The SDK's own {@link DigitalChannelImpl} and {@link RevTouchSensor} on a pin of a simulated hub's digital channel
 * controller, built as the SDK builds a configured "Digital Device" and "REV Touch Sensor". What a pin reads is up to
 * what the robot has connected to it (nothing, in the fixed robots); a pin that nothing is connected to reads high through the hub's pull-up.
 */
final class DigitalInputProvider implements DeviceProvider {
    private static final Set<Class<?>> CHANNELS = Set.of(DigitalChannel.class, DigitalChannelImpl.class);
    private static final Set<Class<?>> TOUCH_SENSORS = Set.of(TouchSensor.class, RevTouchSensor.class);

    @Override
    public String family() {
        return "digital input (DigitalChannel; TouchSensor as the REV Touch Sensor)";
    }

    @Override
    public boolean provides(Class<?> requested) {
        return CHANNELS.contains(requested) || TOUCH_SENSORS.contains(requested);
    }

    @Override
    public HardwareDevice create(SimHubs hubs, Class<?> requested, String name) {
        HardwareDirectory.DigitalWiring wiring = hubs.directory().digital(name);
        hubs.claim("digital channel " + wiring.channel() + " of hub " + wiring.hubAddress(), name);
        LynxDigitalChannelController controller = hubs.digitalController(wiring.hubAddress());
        return TOUCH_SENSORS.contains(requested)
                ? new RevTouchSensor(controller, wiring.channel())
                : new DigitalChannelImpl(controller, wiring.channel());
    }
}
