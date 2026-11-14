package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

/** The same teleop for a program that never asks for the IMU, as most programs of a robot do not. */
@TeleOp(name = "Drive Without IMU", group = "Devices")
public final class ImuDriveBlindTeleOp extends ImuDrive {
    @Override
    boolean usesImu() {
        return false;
    }

    @Override
    ImuOrientationOnRobot mounting() {
        throw new IllegalStateException("a program that does not use the IMU says nothing about its mounting");
    }
}
