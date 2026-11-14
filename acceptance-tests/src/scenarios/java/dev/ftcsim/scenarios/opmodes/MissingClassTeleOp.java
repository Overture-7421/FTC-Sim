package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Needs a class the robot world does not have, which is what an Android API the simulator lacks looks like to user code. */
@TeleOp(name = "Missing Class", group = "Ending")
public final class MissingClassTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
    }

    @Override
    public void loop() {
        throw new NoClassDefFoundError("android/view/NoSuchView");
    }
}
