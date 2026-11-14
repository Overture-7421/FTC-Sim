package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;
import com.qualcomm.robotcore.hardware.LynxModuleImuType;
import java.util.Map;
import java.util.TreeMap;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

/**
 * A mecanum teleop that also shows what the Control Hub's IMU says, which is all a program does with it that needs
 * the whole robot: the sticks turn the wheels, the chassis moves, and yaw, pitch, roll and turn rate come from the
 * IMU as the SDK's driver reads them from the chip. The IMU is asked for as most teams do, by the {@code IMU}
 * interface and the name "imu", and told how the hub is mounted; a subclass can leave the IMU out, to compare the
 * world with and without a program that reads it, or ask for a second one, an Expansion Hub's.
 */
abstract class ImuDrive extends OpMode {
    private DcMotor frontLeft;
    private DcMotor backLeft;
    private DcMotor frontRight;
    private DcMotor backRight;
    private IMU imu;
    private IMU secondImu;
    private String report = "";

    /** What the code tells the SDK about how the hub is mounted. */
    abstract ImuOrientationOnRobot mounting();

    /** Whether the program asks for the IMU at all. */
    boolean usesImu() {
        return true;
    }

    /** Whether the program reads the IMU in its loop, which takes it time, or only sets it up. */
    boolean readsImuInLoop() {
        return true;
    }

    /** The name of a second IMU the program uses too, mounted as the first is, or null. */
    String secondImuName() {
        return null;
    }

    @Override
    public void init() {
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
        if (usesImu()) {
            imu = hardwareMap.get(IMU.class, "imu");
            imu.initialize(new IMU.Parameters(mounting()));
            // The driver zeroes the yaw when it is created, before the code has said how the hub is mounted.
            imu.resetYaw();
        }
        if (secondImuName() != null) {
            secondImu = hardwareMap.get(IMU.class, secondImuName());
            secondImu.initialize(new IMU.Parameters(mounting()));
            secondImu.resetYaw();
            Map<Integer, LynxModuleImuType> types = new TreeMap<>();
            for (LynxModule hub : hardwareMap.getAll(LynxModule.class)) {
                types.put(hub.getModuleAddress(), hub.getImuType());
            }
            StringBuilder hubs = new StringBuilder();
            types.forEach((address, type) -> hubs.append(" hub ").append(address).append(" ").append(type));
            report = imu.getClass().getSimpleName() + " and " + secondImu.getClass().getSimpleName() + ";" + hubs;
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

        if (imu != null && readsImuInLoop()) {
            YawPitchRollAngles angles = imu.getRobotYawPitchRollAngles();
            telemetry.addData("imu yaw deg", angles.getYaw(AngleUnit.DEGREES));
            telemetry.addData("imu pitch deg", angles.getPitch(AngleUnit.DEGREES));
            telemetry.addData("imu roll deg", angles.getRoll(AngleUnit.DEGREES));
            telemetry.addData("imu rate deg/s", imu.getRobotAngularVelocity(AngleUnit.DEGREES).zRotationRate);
        }
        if (secondImu != null) {
            telemetry.addData("imu 1 yaw deg", secondImu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES));
            telemetry.addData("imus", report);
        }
    }

    @Override
    public void stop() {
        frontLeft.setPower(0);
        backLeft.setPower(0);
        frontRight.setPower(0);
        backRight.setPower(0);
    }
}
