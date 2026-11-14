package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

/**
 * A team's intake and shooter written without any knowledge of the simulator: the right trigger runs the intake
 * and stages pieces slowly, and A spins the flywheels while the conveyor feeds them quickly.
 */
@TeleOp(name = "Intake Shooter", group = "Mechanisms")
public final class IntakeShooterTeleOp extends OpMode {
    private DcMotor intake;
    private DcMotor up;
    private DcMotor shooterLeft;
    private DcMotor shooterRight;

    @Override
    public void init() {
        intake = hardwareMap.get(DcMotor.class, "intake");
        up = hardwareMap.get(DcMotor.class, "up");
        shooterLeft = hardwareMap.get(DcMotor.class, "shooterLeft");
        shooterRight = hardwareMap.get(DcMotor.class, "shooterRight");
        for (DcMotor motor : new DcMotor[] {intake, up, shooterLeft, shooterRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            motor.setDirection(DcMotorSimple.Direction.FORWARD);
        }
    }

    @Override
    public void loop() {
        if (gamepad1.a) {
            shooterLeft.setPower(0.3);
            shooterRight.setPower(0.3);
            up.setPower(0.7);
            intake.setPower(1.0);
        } else if (gamepad1.right_trigger > 0.3) {
            shooterLeft.setPower(0);
            shooterRight.setPower(0);
            up.setPower(0.25);
            intake.setPower(1.0);
        } else {
            shooterLeft.setPower(0);
            shooterRight.setPower(0);
            up.setPower(0);
            intake.setPower(0);
        }
    }
}
