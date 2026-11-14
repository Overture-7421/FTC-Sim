package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

/** The IMU-showing mecanum teleop of a robot whose hub lies as the SDK assumes, logo up and USB ports forward. */
@TeleOp(name = "IMU Drive", group = "Devices")
public final class ImuDriveTeleOp extends ImuDrive {
    @Override
    ImuOrientationOnRobot mounting() {
        return new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD);
    }
}
