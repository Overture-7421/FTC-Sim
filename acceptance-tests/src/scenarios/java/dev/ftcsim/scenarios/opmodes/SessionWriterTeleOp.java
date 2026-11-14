package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** The first of two OpModes that run back to back; it leaves its mark in static state and in telemetry. */
@TeleOp(name = "Session Writer", group = "Session")
public final class SessionWriterTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
        Probe.session = "written by the Session Writer";
    }

    @Override
    public void loop() {
        telemetry.addData("only in the writer", "yes");
        telemetry.addLine(Probe.describe(1, gamepad1));
    }
}
