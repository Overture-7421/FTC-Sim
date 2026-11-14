package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

/**
 * The same teleop for a program that sets the IMU up and then never reads it again, so that its loop takes as long as
 * the loop of one that does not use the IMU at all: the two drive the robot alike, and any difference in the world
 * would come from the chip.
 */
@TeleOp(name = "IMU Set Up Only", group = "Devices")
public final class ImuDriveSetUpOnlyTeleOp extends ImuDrive {
    @Override
    boolean readsImuInLoop() {
        return false;
    }

    @Override
    ImuOrientationOnRobot mounting() {
        return new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD);
    }
}
