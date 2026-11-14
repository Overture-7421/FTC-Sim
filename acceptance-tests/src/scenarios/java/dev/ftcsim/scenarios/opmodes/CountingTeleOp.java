package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** An iterative OpMode that counts every callback and shows which one ran last. */
@TeleOp(name = "Counting", group = "Lifecycle")
public final class CountingTeleOp extends OpMode {
    private int loops;

    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
        Probe.count("init");
        telemetry.addData("callback", "init");
    }

    @Override
    public void init_loop() {
        Probe.count("init_loop");
        telemetry.addData("callback", "init_loop");
    }

    @Override
    public void start() {
        Probe.count("start");
    }

    @Override
    public void loop() {
        Probe.count("loop");
        telemetry.addData("callback", "loop");
        telemetry.addData("loops", ++loops);
    }

    @Override
    public void stop() {
        Probe.count("stop");
    }
}
