package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

/** The same teleop for a robot whose hub stands on its edge: the logo faces left and the USB ports face up. */
@TeleOp(name = "IMU Drive Hub On Edge", group = "Devices")
public final class ImuDriveOnEdgeTeleOp extends ImuDrive {
    @Override
    ImuOrientationOnRobot mounting() {
        return new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.LEFT, RevHubOrientationOnRobot.UsbFacingDirection.UP);
    }
}
