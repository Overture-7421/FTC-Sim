package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Asks the SDK to stop it from within user code, as a robot program does when its job is done. */
@TeleOp(name = "Self Stopper", group = "Ending")
public final class SelfStoppingTeleOp extends OpMode {
    private static final int LOOPS_BEFORE_STOPPING = 5;

    private int loops;

    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
    }

    @Override
    public void loop() {
        Probe.count("self-stopper-loop");
        if (++loops == LOOPS_BEFORE_STOPPING) {
            requestOpModeStop();
        }
    }

    @Override
    public void stop() {
        Probe.count("self-stopper-stop");
    }
}
