package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/**
 * A linear OpMode that waits for a button in a loop that reads no clock, sleeps no time and touches no hardware:
 * the way a student writes {@code while (!gamepad1.a) {}}.
 */
@Autonomous(name = "Busy Wait", group = "Time")
public final class BusyWaitAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        waitForStart();
        while (!isStopRequested() && !gamepad1.a) {
            // Nothing here yields; only the loop's own jump lets simulated time pass.
        }
        Probe.count("busy-wait-ended");
    }
}
