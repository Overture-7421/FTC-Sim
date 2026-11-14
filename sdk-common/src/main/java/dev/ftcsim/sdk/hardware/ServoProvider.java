package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.lynx.LynxServoController;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.CRServoImplEx;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.PwmControl;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.ServoImplEx;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.ServoConfigurationType;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import java.util.Set;

/** The SDK's own {@link ServoImplEx} and {@link CRServoImplEx} on a port of a simulated hub's servo controller. */
final class ServoProvider implements DeviceProvider {
    private static final Set<Class<?>> POSITIONAL = Set.of(Servo.class, ServoImplEx.class, PwmControl.class);
    private static final Set<Class<?>> CONTINUOUS = Set.of(CRServo.class, CRServoImplEx.class);

    @Override
    public String family() {
        return "servo (Servo, CRServo)";
    }

    @Override
    public boolean provides(Class<?> requested) {
        return POSITIONAL.contains(requested) || CONTINUOUS.contains(requested);
    }

    @Override
    public HardwareDevice create(SimHubs hubs, Class<?> requested, String name) {
        HardwareDirectory.ServoWiring wiring = hubs.directory().servo(name);
        hubs.claim("servo port " + wiring.port() + " of hub " + wiring.hubAddress(), name);
        LynxServoController controller = hubs.servoController(wiring.hubAddress());
        ServoConfigurationType type = ServoConfigurationType.getStandardServoType();
        return CONTINUOUS.contains(requested)
                ? new CRServoImplEx(controller, wiring.port(), type)
                : new ServoImplEx(controller, wiring.port(), type);
    }
}
