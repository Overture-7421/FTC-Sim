package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** The second of two OpModes that run back to back; it reports what it can see of the first one's leftovers. */
@TeleOp(name = "Session Reader", group = "Session")
public final class SessionReaderTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
    }

    @Override
    public void loop() {
        telemetry.addData("session", Probe.session);
        telemetry.addLine(Probe.describe(1, gamepad1));
    }
}
