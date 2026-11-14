package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.rev.Rev9AxisImuOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

/**
 * An IMU on an I2C bus, asked for by its driver class ({@link ImuSettings#externalDriver}: the REV 9-axis IMU unless
 * told otherwise), as a configuration with that device gives it.
 */
@TeleOp(name = "External IMU Probe", group = "Devices")
public final class ExternalImuProbeTeleOp extends ImuProbe {
    @Override
    @SuppressWarnings("unchecked")
    IMU find() {
        try {
            return (IMU) hardwareMap.get((Class<? extends HardwareDevice>) Class.forName(ImuSettings.externalDriver), ImuSettings.externalName);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(missing);
        }
    }

    @Override
    ImuOrientationOnRobot mounting() {
        return new Rev9AxisImuOrientationOnRobot(
                Rev9AxisImuOrientationOnRobot.LogoFacingDirection.valueOf(ImuSettings.logo),
                Rev9AxisImuOrientationOnRobot.I2cPortFacingDirection.valueOf(ImuSettings.usb));
    }
}
