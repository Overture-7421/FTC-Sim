package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

/** The IMU of the Control Hub, asked for by the {@code IMU} interface under whatever name the host chose. */
@TeleOp(name = "IMU Probe", group = "Devices")
public final class ImuProbeTeleOp extends ImuProbe {
    @Override
    IMU find() {
        return hardwareMap.get(IMU.class, ImuSettings.name);
    }

    @Override
    ImuOrientationOnRobot mounting() {
        return new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.valueOf(ImuSettings.logo),
                RevHubOrientationOnRobot.UsbFacingDirection.valueOf(ImuSettings.usb));
    }
}
