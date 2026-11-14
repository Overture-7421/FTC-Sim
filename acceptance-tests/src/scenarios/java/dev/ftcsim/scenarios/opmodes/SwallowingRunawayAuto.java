package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/** A linear OpMode that catches Throwable around its runaway loop and goes straight back into it. */
@Autonomous(name = "Swallowing Runaway", group = "Runaway")
public final class SwallowingRunawayAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        waitForStart();
        long turns = 0;
        while (true) {
            try {
                while (true) {
                    turns++;
                }
            } catch (Throwable swallowed) {
                Probe.count("error swallowed");
            }
        }
    }
}
