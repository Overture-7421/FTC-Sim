package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/** A linear OpMode that runs away inside a synchronized block, where the kernel must not hand the baton back. */
@Autonomous(name = "Runaway In Lock", group = "Runaway")
public final class LockedRunawayAuto extends LinearOpMode {
    private final Object lock = new Object();

    @Override
    public void runOpMode() {
        waitForStart();
        synchronized (lock) {
            @SuppressWarnings("unused")
            long turns = 0;
            while (true) {
                turns++;
            }
        }
    }
}
