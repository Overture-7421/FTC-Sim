package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

/** Annotated like any OpMode but switched off, so a driver must never be offered it. */
@Disabled
@TeleOp(name = "Disabled TeleOp")
public final class DisabledTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.count("disabled-ran");
    }

    @Override
    public void loop() {}
}
