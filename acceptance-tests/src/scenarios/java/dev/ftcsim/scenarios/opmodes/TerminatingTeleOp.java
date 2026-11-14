package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Ends itself with {@code terminateOpModeNow()}, after which no further user code may run. */
@TeleOp(name = "Terminator", group = "Ending")
public final class TerminatingTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
    }

    @Override
    public void loop() {
        Probe.count("terminator-loop");
        terminateOpModeNow();
        Probe.count("terminator-ran-past-terminate");
    }
}
