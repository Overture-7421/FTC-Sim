package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;

/**
 * A turret turned by a continuous-rotation servo, written without any knowledge of the simulator: the left stick is the
 * servo's power, the right trigger spins the flywheels and A runs the conveyor, which feeds them. With no encoder on the
 * servo the team aims by eye, so nothing here holds an angle.
 */
@TeleOp(name = "Turret Servo", group = "Mechanisms")
public final class TurretServoTeleOp extends OpMode {
    private CRServo turret;
    private DcMotor up;
    private DcMotor shooterLeft;
    private DcMotor shooterRight;

    @Override
    public void init() {
        turret = hardwareMap.get(CRServo.class, "turretServo");
        up = hardwareMap.get(DcMotor.class, "up");
        shooterLeft = hardwareMap.get(DcMotor.class, "shooterLeft");
        shooterRight = hardwareMap.get(DcMotor.class, "shooterRight");
        for (DcMotor motor : new DcMotor[] {up, shooterLeft, shooterRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
    }

    @Override
    public void loop() {
        turret.setPower(gamepad1.left_stick_x);
        double flywheels = gamepad1.right_trigger > 0.3 ? 0.3 : 0.0;
        shooterLeft.setPower(flywheels);
        shooterRight.setPower(flywheels);
        up.setPower(gamepad1.a ? 0.7 : 0.0);
    }

    @Override
    public void stop() {
        turret.setPower(0);
        up.setPower(0);
        shooterLeft.setPower(0);
        shooterRight.setPower(0);
    }
}
