package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

/**
 * {@link IntakeShooterTeleOp} written for the wiring of Hornet's profile, whose conveyor motor is called {@code upMotor}
 * and turns backwards to feed: the right trigger takes pieces in, and A spins the flywheels while the conveyor shoots.
 */
@TeleOp(name = "Hornet Intake Shooter", group = "Mechanisms")
public final class HornetIntakeShooterTeleOp extends OpMode {
    private DcMotor intake;
    private DcMotor up;
    private DcMotor shooterLeft;
    private DcMotor shooterRight;

    @Override
    public void init() {
        intake = hardwareMap.get(DcMotor.class, "intake");
        up = hardwareMap.get(DcMotor.class, "upMotor");
        shooterLeft = hardwareMap.get(DcMotor.class, "shooterLeft");
        shooterRight = hardwareMap.get(DcMotor.class, "shooterRight");
        for (DcMotor motor : new DcMotor[] {intake, up, shooterLeft, shooterRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            motor.setDirection(DcMotorSimple.Direction.FORWARD);
        }
        up.setDirection(DcMotorSimple.Direction.REVERSE);
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
