package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.digitalchickenlabs.OctoQuad;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;
import java.util.Locale;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;

/**
 * A stand-in for the BIOBUZZ robot's program, which does not exist yet: it checks that the robot drives, that the
 * Pinpoint ("odopods") reports where it went, that the Limelight (which rides on the turret) sees the HIVE tags and
 * that the turret turns and is read back. Everything it learns is on telemetry. It is written as a team writes it and
 * knows nothing about the simulator, so the same file runs on the real robot once its names and offsets are checked:
 * copy it into {@code TeamCode} and change the package line.
 *
 * <p>Left stick drives and strafes, right stick turns the robot. The bumpers turn the turret (left bumper to its left)
 * and A, held, turns it to centre the tag the Limelight sees. The turret's angle comes from its two REV Through Bore
 * Encoders on the OctoQuad, in absolute mode: one turns 5.5 times to the turret's one, the other 44/7 times, and the
 * difference of their turns tells which turn of the first the turret is on. The left trigger runs the intake, the right
 * trigger the flywheels and B the indexer; X opens the stopper, Y raises the colibri arms and the D-pad's up and down
 * raise and lower the hood. The hardware names are the ones the robot's configuration uses; the pod offsets are the
 * CAD's, and the Limelight's mount is an estimate, as the CAD has no Limelight.
 */
@TeleOp(name = "BIOBUZZ Stub", group = "Stub")
public final class BiobuzzStubTeleOp extends OpMode {
    /**
     * The odometry pods in the robot's CAD, from the centre of its four wheels: the forward-measuring pod sits 70.4 mm to the
     * right of it (so -2.772 in to the left) and the strafe-measuring pod 58.9 mm ahead of it (2.319 in). The Pinpoint takes
     * them as the left offset of the forward pod and the forward offset of the strafe pod.
     */
    private static final double FORWARD_POD_LEFT_INCHES = -2.772;
    private static final double STRAFE_POD_FORWARD_INCHES = 2.319;
    private static final int TELEMETRY_INTERVAL_MS = 50;
    /** The pulse a REV Through Bore Encoder (version 2) sends over a turn, in microseconds. */
    private static final int PULSE_MIN_US = 4;
    private static final int PULSE_MAX_US = 998;
    /** Turns of each encoder to one of the turret: the 32-tooth gear's, then the 28-tooth gear's. */
    private static final double TURNS_A = 5.5;
    private static final double TURNS_B = 44.0 / 7.0;
    private static final double TURRET_POWER = 0.3;
    /** Servo power per degree of Limelight bearing while A is held. */
    private static final double AIM_GAIN = 0.02;
    private static final double AIM_LIMIT = 0.4;
    /** Servo positions: the stopper shut and open, the colibri arms down and up, how fast the hood moves per second. */
    private static final double STOPPER_SHUT = 0.0;
    private static final double STOPPER_OPEN = 0.5;
    private static final double COLIBRI_DOWN = 0.0;
    private static final double COLIBRI_UP = 0.4;
    private static final double HOOD_PER_SECOND = 0.5;

    private DcMotor frontLeft;
    private DcMotor backLeft;
    private DcMotor frontRight;
    private DcMotor backRight;
    private DcMotor intake;
    private DcMotor indexer;
    private DcMotor shooterLeft;
    private DcMotor shooterRight;
    private Servo hood;
    private Servo stopper;
    private Servo colibriLeft;
    private Servo colibriRight;
    private double hoodPosition;
    private long lastLoopNanos;
    private GoBildaPinpointDriver odo;
    private Limelight3A limelight;
    private CRServo turretServo;
    private OctoQuad octoquad;

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
        odo.setOffsets(FORWARD_POD_LEFT_INCHES, STRAFE_POD_FORWARD_INCHES, DistanceUnit.INCH);
        odo.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        odo.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.REVERSED, GoBildaPinpointDriver.EncoderDirection.REVERSED);
        odo.resetPosAndIMU();
        odo.setPosition(new Pose2D(DistanceUnit.MM, 0, 0, AngleUnit.RADIANS, 0));

        limelight = hardwareMap.get(Limelight3A.class, "limelight");
        limelight.setPollRateHz(100);
        limelight.pipelineSwitch(0);
        limelight.start();

        turretServo = hardwareMap.get(CRServo.class, "turretServo");
        octoquad = hardwareMap.get(OctoQuad.class, "octoquad");
        octoquad.setChannelBankConfig(OctoQuad.ChannelBankConfig.ALL_PULSE_WIDTH);
        octoquad.setSingleChannelPulseWidthParams(0, PULSE_MIN_US, PULSE_MAX_US);
        octoquad.setSingleChannelPulseWidthParams(1, PULSE_MIN_US, PULSE_MAX_US);

        intake = hardwareMap.get(DcMotor.class, "intake");
        indexer = hardwareMap.get(DcMotor.class, "up");
        shooterLeft = hardwareMap.get(DcMotor.class, "shooterLeft");
        shooterRight = hardwareMap.get(DcMotor.class, "shooterRight");
        for (DcMotor motor : new DcMotor[] {intake, indexer, shooterLeft, shooterRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
        hood = hardwareMap.get(Servo.class, "hood");
        stopper = hardwareMap.get(Servo.class, "stopper");
        colibriLeft = hardwareMap.get(Servo.class, "colibriLeft");
        colibriRight = hardwareMap.get(Servo.class, "colibriRight");
        stopper.setPosition(STOPPER_SHUT);
        colibriLeft.setPosition(COLIBRI_DOWN);
        colibriRight.setPosition(COLIBRI_DOWN);
        hood.setPosition(hoodPosition);
    }

    /** Where the turret points, in degrees left of the robot's front, from the two absolute encoders. */
    private double turretDegrees(int[] pulses) {
        double a = (pulses[0] - PULSE_MIN_US) / (double) (PULSE_MAX_US - PULSE_MIN_US);
        double b = (pulses[1] - PULSE_MIN_US) / (double) (PULSE_MAX_US - PULSE_MIN_US);
        double difference = b - a - Math.floor(b - a);
        // The difference of their turns repeats every 1/(B - A) turret turns, a little more than one: take the window -0.5 to +0.77.
        double period = 1.0 / (TURNS_B - TURNS_A);
        double coarse = difference * period;
        if (coarse >= period - 0.5) {
            coarse -= period;
        }
        double turns = (Math.round(TURNS_A * coarse - a) + a) / TURNS_A;
        return Math.IEEEremainder(turns, 1.0) * 360.0;
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
        boolean seen = result != null && result.isValid();

        double turretPower = (gamepad1.left_bumper ? TURRET_POWER : 0.0) - (gamepad1.right_bumper ? TURRET_POWER : 0.0);
        if (gamepad1.a && seen) {
            // A positive bearing is a tag to the right, so the turret turns right (negative power) to centre it.
            turretPower = Math.max(-AIM_LIMIT, Math.min(AIM_LIMIT, -AIM_GAIN * result.getTx()));
        }
        turretServo.setPower(turretPower);
        int[] pulses = octoquad.readAllEncoderData().positions;
        telemetry.addData("Turret", String.format(Locale.ROOT, "deg=%.1f p0=%d p1=%d", turretDegrees(pulses), pulses[0], pulses[1]));

        long now = System.nanoTime();
        double seconds = lastLoopNanos == 0 ? 0.0 : (now - lastLoopNanos) / 1e9;
        lastLoopNanos = now;
        hoodPosition = Math.max(0.0, Math.min(1.0,
                hoodPosition + HOOD_PER_SECOND * seconds * ((gamepad1.dpad_up ? 1 : 0) - (gamepad1.dpad_down ? 1 : 0))));
        hood.setPosition(hoodPosition);
        stopper.setPosition(gamepad1.x ? STOPPER_OPEN : STOPPER_SHUT);
        colibriLeft.setPosition(gamepad1.y ? COLIBRI_UP : COLIBRI_DOWN);
        colibriRight.setPosition(gamepad1.y ? COLIBRI_UP : COLIBRI_DOWN);
        intake.setPower(gamepad1.left_trigger > 0.3 ? 0.8 : 0.0);
        double flywheels = gamepad1.right_trigger > 0.3 ? 0.6 : 0.0;
        shooterLeft.setPower(flywheels);
        shooterRight.setPower(flywheels);
        indexer.setPower(gamepad1.b ? 0.7 : 0.0);
        telemetry.addData("Mechanisms", String.format(Locale.ROOT, "hood=%.2f stopper=%s colibri=%s intake=%s flywheels=%s indexer=%s",
                hoodPosition, gamepad1.x ? "open" : "shut", gamepad1.y ? "up" : "down",
                gamepad1.left_trigger > 0.3 ? "on" : "off", flywheels > 0 ? "on" : "off", gamepad1.b ? "on" : "off"));

        if (seen) {
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
        turretServo.setPower(0);
        intake.setPower(0);
        indexer.setPower(0);
        shooterLeft.setPower(0);
        shooterRight.setPower(0);
        limelight.stop();
    }
}
