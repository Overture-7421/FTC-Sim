package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/** Spins a motor open loop at half power and records what its encoder says on every pass of the loop. */
@TeleOp(name = "Motor Drive", group = "Hardware")
public final class MotorDriveTeleOp extends OpMode {
    private DcMotorEx flywheel;

    @Override
    public void init() {
        flywheel = hardwareMap.get(DcMotorEx.class, "flywheel");
        flywheel.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
    }

    @Override
    public void start() {
        flywheel.setPower(0.5);
    }

    @Override
    public void loop() {
        Probe.record("position", flywheel.getCurrentPosition());
        Probe.record("velocity", flywheel.getVelocity());
        Probe.record("power", flywheel.getPower());
    }

    @Override
    public void stop() {
        flywheel.setPower(0);
    }
}
