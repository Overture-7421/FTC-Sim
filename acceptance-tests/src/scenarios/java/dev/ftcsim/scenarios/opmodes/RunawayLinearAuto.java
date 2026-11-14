package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/** A linear OpMode that loops without ever asking whether it was stopped, the commonest way to ignore Stop. */
@Autonomous(name = "Runaway Linear", group = "Runaway")
public final class RunawayLinearAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        waitForStart();
        try {
            long turns = 0;
            while (true) {
                turns++;
                if (turns == Long.MIN_VALUE) {
                    return;
                }
            }
        } finally {
            Probe.count("runaway finally");
        }
    }
}
