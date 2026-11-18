package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.digitalchickenlabs.OctoQuad;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;

/**
 * The BIOBUZZ robot's hardware as the robot's configuration names it, set up as the programs of this robot need it, with the few
 * things every program does with it: the mecanum drive, the turret's angle from its two absolute encoders and the stop. It is
 * written as a team writes it and knows nothing about the simulator, so the same file runs on the real robot once its names and
 * offsets are checked: copy it into {@code TeamCode} with the OpModes and change the package line.
 */
public final class BiobuzzHardware {
    /**
     * The odometry pods in the robot's CAD, from the centre of its four wheels: the forward-measuring pod sits 70.4 mm to the
     * right of it (so -2.772 in to the left) and the strafe-measuring pod 58.9 mm ahead of it (2.319 in). The Pinpoint takes
     * them as the left offset of the forward pod and the forward offset of the strafe pod.
     */
    private static final double FORWARD_POD_LEFT_INCHES = -2.772;
    private static final double STRAFE_POD_FORWARD_INCHES = 2.319;
    /** The pulse a REV Through Bore Encoder (version 2) sends over a turn, in microseconds. */
    private static final int PULSE_MIN_US = 4;
    private static final int PULSE_MAX_US = 998;
    /** Turns of each encoder to one of the turret: the 32-tooth gear's, then the 28-tooth gear's. */
    private static final double TURNS_A = 5.5;
    private static final double TURNS_B = 44.0 / 7.0;

    /** Intake roller power, the indexer's power while it carries balls up to the shut stopper, and its power to feed a shot. */
    public static final double INTAKE_POWER = 1.0;
    public static final double STAGING_POWER = 0.6;
    public static final double FEED_POWER = BiobuzzHoldShooter.FEED_POWER;
    /** Servo positions: the stopper shut and open (past the 60 degrees at which it lets a ball by, no further, so it is quick), the colibri arms down and up. */
    public static final double STOPPER_SHUT = 0.0;
    public static final double STOPPER_OPEN = 0.3;
    public static final double COLIBRI_DOWN = 0.0;
    public static final double COLIBRI_UP = 0.4;

    public final DcMotor frontLeft;
    public final DcMotor backLeft;
    public final DcMotor frontRight;
    public final DcMotor backRight;
    public final DcMotor intake;
    public final DcMotor indexer;
    public final DcMotorEx shooterLeft;
    public final DcMotorEx shooterRight;
    public final Servo hood;
    public final Servo stopper;
    public final Servo colibriLeft;
    public final Servo colibriRight;
    public final GoBildaPinpointDriver odo;
    public final Limelight3A limelight;
    public final CRServo turretServo;
    public final OctoQuad octoquad;

    /** Looks everything up and sets it up: the Pinpoint at the origin, the Limelight running, the stopper shut and the hood at 0. */
    public BiobuzzHardware(HardwareMap hardwareMap) {
        frontLeft = hardwareMap.get(DcMotor.class, "frontLeft");
        backLeft = hardwareMap.get(DcMotor.class, "backLeft");
        frontRight = hardwareMap.get(DcMotor.class, "frontRight");
        backRight = hardwareMap.get(DcMotor.class, "backRight");
        frontLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotor motor : new DcMotor[] {frontLeft, backLeft, frontRight, backRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            // The robot stops when the stick is let go instead of coasting on.
            motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
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
        shooterLeft = hardwareMap.get(DcMotorEx.class, "shooterLeft");
        shooterRight = hardwareMap.get(DcMotorEx.class, "shooterRight");
        for (DcMotor motor : new DcMotor[] {intake, indexer, shooterLeft, shooterRight}) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
        // The roller and the indexer stop at once when the power goes (brake), instead of running on for a second; the flywheels coast.
        intake.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        indexer.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        hood = hardwareMap.get(Servo.class, "hood");
        stopper = hardwareMap.get(Servo.class, "stopper");
        colibriLeft = hardwareMap.get(Servo.class, "colibriLeft");
        colibriRight = hardwareMap.get(Servo.class, "colibriRight");
        stopper.setPosition(STOPPER_SHUT);
        colibriLeft.setPosition(COLIBRI_DOWN);
        colibriRight.setPosition(COLIBRI_DOWN);
        hood.setPosition(0.0);
    }

    /** Mecanum drive on the robot's own axes: forward, strafe to the right, turn to the right (clockwise), each from -1 to 1. */
    public void drive(double forward, double strafe, double turn) {
        double scale = Math.max(1.0, Math.abs(forward) + Math.abs(strafe) + Math.abs(turn));
        frontLeft.setPower((forward + strafe + turn) / scale);
        backLeft.setPower((forward - strafe + turn) / scale);
        frontRight.setPower((forward - strafe - turn) / scale);
        backRight.setPower((forward + strafe - turn) / scale);
    }

    /** The two absolute encoders' pulse widths, in microseconds. */
    public int[] readPulses() {
        return octoquad.readAllEncoderData().positions;
    }

    /** Where the turret points, in degrees left of the robot's front, from the two absolute encoders. */
    public static double turretDegrees(int[] pulses) {
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
        // Not wrapped to +-180: the turret can be past a half turn to the left, which is how far it has to go to reach a HIVE behind it.
        return turns * 360.0;
    }

    /** The flywheels' speed, ticks a second: the two motors' average. */
    public double flywheelTicks() {
        return 0.5 * (shooterLeft.getVelocity() + shooterRight.getVelocity());
    }

    public void setFlywheels(double power) {
        shooterLeft.setPower(power);
        shooterRight.setPower(power);
    }

    /** Everything off. */
    public void stop() {
        drive(0, 0, 0);
        turretServo.setPower(0);
        intake.setPower(0);
        indexer.setPower(0);
        setFlywheels(0);
        limelight.stop();
    }
}
