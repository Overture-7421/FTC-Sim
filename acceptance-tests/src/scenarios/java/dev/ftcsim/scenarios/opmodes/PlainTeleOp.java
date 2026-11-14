package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/**
 * Annotated with nothing but {@code @TeleOp}, so its name is its class name and it has no group. Its static
 * initialiser and constructor are tripwires: merely finding and listing OpModes must run neither.
 */
@TeleOp
public final class PlainTeleOp extends OpMode {
    static {
        Probe.count("plain-class-initialised");
    }

    public PlainTeleOp() {
        Probe.count("plain-constructed");
    }

    @Override
    public void init() {}

    @Override
    public void loop() {}
}
