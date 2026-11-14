package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** An iterative OpMode whose loop() never returns, so the SDK cannot even look at whether it was stopped. */
@TeleOp(name = "Runaway Iterative", group = "Runaway")
public final class RunawayIterativeTeleOp extends OpMode {
    @Override
    public void init() {}

    @Override
    public void loop() {
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
