package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.util.Range;

/**
 * A team's turret and shooter written without any knowledge of the simulator: the left stick sets the turret's angle
 * (a full push is 90 degrees) which a position loop holds from the turret motor's encoder, the right trigger spins the
 * flywheels and A runs the conveyor, which feeds the flywheels.
 */
@TeleOp(name = "Turret Aim", group = "Mechanisms")
public final class TurretAimTeleOp extends OpMode {
    /** The goBILDA Yellow Jacket's encoder, on the motor's own shaft. */
    private static final double TICKS_PER_MOTOR_REVOLUTION = 28.0;
    /** Motor turns for one turn of the turret, which the robot mapping of the test says as well. */
    private static final double TURRET_RATIO = 3.0;
    private static final double GAIN = 0.8;

    private DcMotor turret;
    private DcMotor up;
    private DcMotor shooterLeft;
    private DcMotor shooterRight;

    @Override
    public void init() {
        turret = hardwareMap.get(DcMotor.class, "turret");
        up = hardwareMap.get(DcMotor.class, "up");
        shooterLeft = hardwareMap.get(DcMotor.class, "shooterLeft");
        shooterRight = hardwareMap.get(DcMotor.class, "shooterRight");
        turret.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        for (DcMotor motor : new DcMotor[] {turret, up, shooterLeft, shooterRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
    }

    @Override
    public void loop() {
        double target = Math.toRadians(90.0 * gamepad1.left_stick_x);
        double angle = 2.0 * Math.PI * turret.getCurrentPosition() / TICKS_PER_MOTOR_REVOLUTION / TURRET_RATIO;
        turret.setPower(Range.clip(GAIN * (target - angle), -1.0, 1.0));
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
