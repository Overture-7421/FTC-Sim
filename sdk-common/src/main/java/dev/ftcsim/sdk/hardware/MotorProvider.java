package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.lynx.LynxDcMotorController;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorImplEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import java.util.Set;
import org.firstinspires.ftc.robotcore.external.navigation.Rotation;

/** The SDK's own {@link DcMotorImplEx} on a port of a simulated hub's motor controller. */
final class MotorProvider implements DeviceProvider {
    private static final Set<Class<?>> TYPES = Set.of(DcMotor.class, DcMotorEx.class, DcMotorImplEx.class);

    /** What the SDK assumes of a motor's top speed when the configuration does not say otherwise. */
    private static final double ACHIEVABLE_FRACTION_OF_FREE_SPEED = 0.85;

    @Override
    public String family() {
        return "DC motor (DcMotor, DcMotorEx)";
    }

    @Override
    public boolean provides(Class<?> requested) {
        return TYPES.contains(requested);
    }

    @Override
    public HardwareDevice create(SimHubs hubs, Class<?> requested, String name) {
        HardwareDirectory.MotorWiring wiring = hubs.directory().motor(name);
        hubs.claim("motor port " + wiring.port() + " of hub " + wiring.hubAddress(), name);
        LynxDcMotorController controller = hubs.motorController(wiring.hubAddress());
        return new DcMotorImplEx(controller, wiring.port(), DcMotorSimple.Direction.FORWARD, motorType(wiring));
    }

    private static MotorConfigurationType motorType(HardwareDirectory.MotorWiring wiring) {
        MotorConfigurationType type = new MotorConfigurationType();
        type.setTicksPerRev(wiring.ticksPerRev());
        type.setMaxRPM(wiring.maxRpm());
        type.setGearing(1.0);
        type.setAchieveableMaxRPMFraction(ACHIEVABLE_FRACTION_OF_FREE_SPEED);
        // The SDK inverts a motor whose encoder counts up counter-clockwise so that FORWARD always turns the shaft
        // clockwise. A plant whose counts rise with its voltage needs no inversion, and the shaft turns the same way.
        type.setOrientation(Rotation.CW);
        return type;
    }
}
