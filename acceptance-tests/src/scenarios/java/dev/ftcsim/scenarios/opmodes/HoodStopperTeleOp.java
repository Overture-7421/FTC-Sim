package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.Servo;

/**
 * A hood and a stopper moved by positional servos, written without any knowledge of the simulator: the left stick's X is
 * the hood's position (the stick fully left is 0, fully right 1), B opens the stopper, the right trigger spins the
 * flywheels and A runs the conveyor that feeds them.
 */
@TeleOp(name = "Hood Stopper", group = "Mechanisms")
public final class HoodStopperTeleOp extends OpMode {
    private Servo hood;
    private Servo stopper;
    private DcMotor up;
    private DcMotor shooterLeft;
    private DcMotor shooterRight;

    @Override
    public void init() {
        hood = hardwareMap.get(Servo.class, "hoodServo");
        stopper = hardwareMap.get(Servo.class, "stopperServo");
        up = hardwareMap.get(DcMotor.class, "up");
        shooterLeft = hardwareMap.get(DcMotor.class, "shooterLeft");
        shooterRight = hardwareMap.get(DcMotor.class, "shooterRight");
        for (DcMotor motor : new DcMotor[] {up, shooterLeft, shooterRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
    }

    @Override
    public void loop() {
        hood.setPosition(0.5 + 0.5 * gamepad1.left_stick_x);
        stopper.setPosition(gamepad1.b ? 0.5 : 0.0);
        double flywheels = gamepad1.right_trigger > 0.3 ? 0.3 : 0.0;
        shooterLeft.setPower(flywheels);
        shooterRight.setPower(flywheels);
        up.setPower(gamepad1.a ? 0.7 : 0.0);
    }

    @Override
    public void stop() {
        up.setPower(0);
        shooterLeft.setPower(0);
        shooterRight.setPower(0);
    }
}
