package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/** A linear OpMode that waits for Start, works through three steps and ends by itself. */
@Autonomous(name = "Linear Auto", group = "Lifecycle")
public final class LinearAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        Probe.transmitTelemetryImmediately(telemetry);
        telemetry.addData("status", "waiting for start");
        updateTelemetry(telemetry);
        waitForStart();
        Probe.count("started");

        for (int step = 1; step <= 3 && opModeIsActive(); step++) {
            Probe.count("step");
            telemetry.addData("status", "running");
            telemetry.addData("step", step);
            telemetry.update();
            sleep(20);
            idle();
        }

        telemetry.addData("status", "done");
        telemetry.update();
        Probe.count("completed");
    }
}
