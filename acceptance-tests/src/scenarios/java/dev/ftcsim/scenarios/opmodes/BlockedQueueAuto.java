package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** A linear OpMode blocked in a JDK primitive that never gets what it waits for and shrugs off interrupts. */
@Autonomous(name = "Blocked Queue", group = "Runaway")
public final class BlockedQueueAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        BlockingQueue<Integer> neverFilled = new LinkedBlockingQueue<>();
        waitForStart();
        try {
            while (true) {
                try {
                    neverFilled.take();
                } catch (InterruptedException ignored) {
                    Probe.count("interrupt swallowed");
                }
            }
        } finally {
            Probe.count("runaway finally");
        }
    }
}
