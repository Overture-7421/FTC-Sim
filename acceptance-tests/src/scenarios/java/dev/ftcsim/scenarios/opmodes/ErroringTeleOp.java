package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Fails with an error rather than an exception, such as a failed static initialiser or a missing native method. */
@TeleOp(name = "Erroring", group = "Ending")
public final class ErroringTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
    }

    @Override
    public void loop() {
        throw new UnsatisfiedLinkError("no native method for the robot program");
    }
}
