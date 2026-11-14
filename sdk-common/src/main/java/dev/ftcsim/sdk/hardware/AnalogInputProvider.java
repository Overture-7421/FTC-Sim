package dev.ftcsim.sdk.hardware;

import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import dev.ftcsim.contracts.hardware.HardwareDirectory;

/**
 * The SDK's own {@link AnalogInput} on a channel of a simulated hub's analog input controller, built as the SDK builds
 * a configured "Analog Input". What a channel reads is up to what the robot profile connected to it; a channel that
 * nothing is connected to reads 0 V.
 */
final class AnalogInputProvider implements DeviceProvider {

    @Override
    public String family() {
        return "analog input (AnalogInput)";
    }

    @Override
    public boolean provides(Class<?> requested) {
        return requested == AnalogInput.class;
    }

    @Override
    public HardwareDevice create(SimHubs hubs, Class<?> requested, String name) {
        HardwareDirectory.AnalogWiring wiring = hubs.directory().analog(name);
        hubs.claim("analog channel " + wiring.channel() + " of hub " + wiring.hubAddress(), name);
        return new AnalogInput(hubs.analogController(wiring.hubAddress()), wiring.channel());
    }
}
