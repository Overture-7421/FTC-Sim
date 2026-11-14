package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/** A linear OpMode that reads a motor in a loop that never checks for Stop, so the hub's bus lock can end it. */
@Autonomous(name = "Runaway On Hardware", group = "Runaway")
public final class HardwareRunawayAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        DcMotorEx flywheel = hardwareMap.get(DcMotorEx.class, "flywheel");
        waitForStart();
        while (true) {
            flywheel.getCurrentPosition();
        }
    }
}
