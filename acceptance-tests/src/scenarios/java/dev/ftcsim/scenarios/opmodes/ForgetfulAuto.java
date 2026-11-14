package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/** A linear OpMode that never calls {@code waitForStart()}, which the SDK reports with a global warning. */
@Autonomous(name = "Forgetful Auto", group = "Ending")
public final class ForgetfulAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        Probe.transmitTelemetryImmediately(telemetry);
        telemetry.addData("status", "finished without waiting for start");
        telemetry.update();
    }
}
