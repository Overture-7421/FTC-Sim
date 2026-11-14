package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

/**
 * The textbook mecanum teleop: left stick drives and strafes, right stick turns, left side motors reversed. It is
 * written the way a team writes it and knows nothing about the simulator.
 */
@TeleOp(name = "Mecanum Drive", group = "Drive")
public final class MecanumDriveTeleOp extends OpMode {
    private DcMotor frontLeft;
    private DcMotor backLeft;
    private DcMotor frontRight;
    private DcMotor backRight;

    @Override
    public void init() {
        frontLeft = hardwareMap.get(DcMotor.class, "frontLeft");
        backLeft = hardwareMap.get(DcMotor.class, "backLeft");
        frontRight = hardwareMap.get(DcMotor.class, "frontRight");
        backRight = hardwareMap.get(DcMotor.class, "backRight");
        frontLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotor motor : new DcMotor[] {frontLeft, backLeft, frontRight, backRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
    }

    @Override
    public void loop() {
        double drive = -gamepad1.left_stick_y;
        double strafe = gamepad1.left_stick_x;
        double turn = gamepad1.right_stick_x;
        double scale = Math.max(1.0, Math.abs(drive) + Math.abs(strafe) + Math.abs(turn));
        frontLeft.setPower((drive + strafe + turn) / scale);
        backLeft.setPower((drive - strafe + turn) / scale);
        frontRight.setPower((drive - strafe - turn) / scale);
        backRight.setPower((drive + strafe - turn) / scale);
    }

    @Override
    public void stop() {
        frontLeft.setPower(0);
        backLeft.setPower(0);
        frontRight.setPower(0);
        backRight.setPower(0);
    }
}
