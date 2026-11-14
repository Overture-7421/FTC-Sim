package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ColorRangeSensor;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;

/**
 * A mecanum teleop that also reads a Pinpoint and a colour-and-range sensor, set up the way Hornet's Pedro Pathing
 * constants and intake do it, and reports what they say through telemetry.
 */
@TeleOp(name = "Drive And Sense", group = "Devices")
public final class DriveAndSenseTeleOp extends OpMode {
    private DcMotor frontLeft;
    private DcMotor backLeft;
    private DcMotor frontRight;
    private DcMotor backRight;
    private GoBildaPinpointDriver odometry;
    private ColorRangeSensor sensor;

    @Override
    public void init() {
        // The driver station is sent telemetry at most every 250 ms unless the program asks for more.
        telemetry.setMsTransmissionInterval(0);
        frontLeft = hardwareMap.get(DcMotor.class, "frontLeft");
        backLeft = hardwareMap.get(DcMotor.class, "backLeft");
        frontRight = hardwareMap.get(DcMotor.class, "frontRight");
        backRight = hardwareMap.get(DcMotor.class, "backRight");
        frontLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotor motor : new DcMotor[] {frontLeft, backLeft, frontRight, backRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }

        odometry = hardwareMap.get(GoBildaPinpointDriver.class, "odopods");
        odometry.setOffsets(-4.467131, -0.160124, DistanceUnit.INCH);
        odometry.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        odometry.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.REVERSED, GoBildaPinpointDriver.EncoderDirection.REVERSED);
        odometry.resetPosAndIMU();
        odometry.setPosition(new Pose2D(DistanceUnit.MM, 0, 0, AngleUnit.RADIANS, 0));

        sensor = hardwareMap.get(ColorRangeSensor.class, "upSensor");
    }

    @Override
    public void init_loop() {
        odometry.update();
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

        odometry.update();
        Pose2D pose = odometry.getPosition();
        telemetry.addData("pinpoint x mm", pose.getX(DistanceUnit.MM));
        telemetry.addData("pinpoint y mm", pose.getY(DistanceUnit.MM));
        telemetry.addData("pinpoint heading rad", pose.getHeading(AngleUnit.RADIANS));
        telemetry.addData("up cm", sensor.getDistance(DistanceUnit.CM));
    }

    @Override
    public void stop() {
        frontLeft.setPower(0);
        backLeft.setPower(0);
        frontRight.setPower(0);
        backRight.setPower(0);
    }
}
