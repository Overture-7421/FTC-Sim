package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.Servo;

/** Moves a servo to a quarter of its range when the OpMode starts. */
@TeleOp(name = "Servo Move", group = "Hardware")
public final class ServoTeleOp extends OpMode {
    private Servo hood;

    @Override
    public void init() {
        hood = hardwareMap.get(Servo.class, "hood");
    }

    @Override
    public void start() {
        hood.setPosition(0.25);
    }

    @Override
    public void loop() {
        Probe.record("position", hood.getPosition());
    }
}
