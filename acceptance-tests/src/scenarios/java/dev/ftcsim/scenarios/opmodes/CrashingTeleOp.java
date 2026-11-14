package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Throws from user code once it has started, the way a buggy robot program does. */
@TeleOp(name = "Crasher", group = "Ending")
public final class CrashingTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
    }

    @Override
    public void loop() {
        throw new IllegalStateException("boom from user code");
    }
}
