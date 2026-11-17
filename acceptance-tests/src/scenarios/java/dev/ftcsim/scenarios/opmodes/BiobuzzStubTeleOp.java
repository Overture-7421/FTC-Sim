package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import java.util.Locale;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;

/**
 * A stand-in for the BIOBUZZ robot's program, which does not exist yet: it checks that the robot drives, that the
 * Pinpoint ("odopods") reports where it went and that the Limelight sees the HIVE tags. Everything it learns is on
 * telemetry. It is written as a team writes it and knows nothing about the simulator, so the same file runs on the
 * real robot once its names and offsets are checked: copy it into {@code TeamCode} and change the package line.
 *
 * <p>Left stick drives and strafes, right stick turns. The hardware names are the ones the robot's configuration
 * uses; the pod offsets are placeholders until they are measured on the CAD.
 */
@TeleOp(name = "BIOBUZZ Stub", group = "Stub")
public final class BiobuzzStubTeleOp extends OpMode {
    /** Where the forward pod is along the robot's Y and the strafe pod along its X, in inches. Placeholders. */
    private static final double FORWARD_POD_Y_INCHES = -4.467131;
    private static final double STRAFE_POD_X_INCHES = -0.160124;
    private static final int TELEMETRY_INTERVAL_MS = 50;

    private DcMotor frontLeft;
    private DcMotor backLeft;
    private DcMotor frontRight;
    private DcMotor backRight;
    private GoBildaPinpointDriver odo;
    private Limelight3A limelight;

    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(TELEMETRY_INTERVAL_MS);
        frontLeft = hardwareMap.get(DcMotor.class, "frontLeft");
        backLeft = hardwareMap.get(DcMotor.class, "backLeft");
        frontRight = hardwareMap.get(DcMotor.class, "frontRight");
        backRight = hardwareMap.get(DcMotor.class, "backRight");
        frontLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotor motor : new DcMotor[] {frontLeft, backLeft, frontRight, backRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }

        odo = hardwareMap.get(GoBildaPinpointDriver.class, "odopods");
        odo.setOffsets(FORWARD_POD_Y_INCHES, STRAFE_POD_X_INCHES, DistanceUnit.INCH);
        odo.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        odo.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.REVERSED, GoBildaPinpointDriver.EncoderDirection.REVERSED);
        odo.resetPosAndIMU();
        odo.setPosition(new Pose2D(DistanceUnit.MM, 0, 0, AngleUnit.RADIANS, 0));

        limelight = hardwareMap.get(Limelight3A.class, "limelight");
        limelight.setPollRateHz(100);
        limelight.pipelineSwitch(0);
        limelight.start();
    }

    @Override
    public void init_loop() {
        odo.update();
        telemetry.addData("Pinpoint", odo.getDeviceStatus());
        telemetry.update();
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

        odo.update();
        Pose2D pose = odo.getPosition();
        telemetry.addData("Pinpoint", odo.getDeviceStatus());
        telemetry.addData("Pose", String.format(Locale.ROOT, "x=%.1f y=%.1f h=%.1f",
                pose.getX(DistanceUnit.MM), pose.getY(DistanceUnit.MM), pose.getHeading(AngleUnit.DEGREES)));
        telemetry.addData("Velocity", String.format(Locale.ROOT, "x=%.1f y=%.1f",
                odo.getVelX(DistanceUnit.MM), odo.getVelY(DistanceUnit.MM)));
        telemetry.addData("Encoders", String.format(Locale.ROOT, "x=%d y=%d", odo.getEncoderX(), odo.getEncoderY()));

        LLResult result = limelight.getLatestResult();
        if (result != null && result.isValid()) {
            StringBuilder ids = new StringBuilder();
            for (FiducialResult tag : result.getFiducialResults()) {
                ids.append(ids.length() == 0 ? "" : ",").append(tag.getFiducialId());
            }
            telemetry.addData("Tags", ids.toString());
            telemetry.addData("Target", String.format(Locale.ROOT, "tx=%.2f ty=%.2f ta=%.4f",
                    result.getTx(), result.getTy(), result.getTa()));
            telemetry.addData("Botpose tags", result.getBotposeTagCount());
        } else {
            telemetry.addData("Tags", "none");
        }
        telemetry.update();
    }

    @Override
    public void stop() {
        frontLeft.setPower(0);
        backLeft.setPower(0);
        frontRight.setPower(0);
        backRight.setPower(0);
        limelight.stop();
    }
}
