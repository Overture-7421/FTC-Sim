package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/** Blocks in a very long {@code sleep()}, which only a stop request can end. */
@Autonomous(name = "Sleeping Auto", group = "Ending")
public final class SleepingAuto extends LinearOpMode {
    private static final long A_LONG_TIME_MS = 60_000;

    @Override
    public void runOpMode() {
        Probe.transmitTelemetryImmediately(telemetry);
        waitForStart();
        telemetry.addData("status", "sleeping");
        telemetry.update();

        sleep(A_LONG_TIME_MS);

        Probe.count(isStopRequested() ? "woke-by-stop" : "woke-by-timeout");
    }
}
