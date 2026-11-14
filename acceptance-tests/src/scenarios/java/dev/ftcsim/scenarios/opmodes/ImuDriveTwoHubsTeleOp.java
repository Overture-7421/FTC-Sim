package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

/** The IMU-showing teleop of a robot with two hubs that each have an IMU, "imu" and "imu 1" as a configuration names them. */
@TeleOp(name = "IMU Drive Two Hubs", group = "Devices")
public final class ImuDriveTwoHubsTeleOp extends ImuDrive {
    @Override
    String secondImuName() {
        return "imu 1";
    }

    @Override
    ImuOrientationOnRobot mounting() {
        return new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD);
    }
}
