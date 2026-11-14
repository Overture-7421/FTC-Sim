package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.util.ElapsedTime;

/**
 * A linear OpMode that needs half a second after Stop to tidy up: slow, but well within the SDK's limit. Stop interrupts
 * the OpMode's thread, which would cut a sleep short, so it waits on the clock instead, as code that waits for a
 * mechanism to settle does.
 */
@Autonomous(name = "Slow Stop", group = "Runaway")
public final class SlowStopAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        waitForStart();
        while (opModeIsActive()) {
            Probe.count("slow stop running");
        }
        ElapsedTime tidyingUp = new ElapsedTime();
        while (tidyingUp.milliseconds() < 500) {
            idle();
        }
        Probe.count("slow stop finished");
    }
}
