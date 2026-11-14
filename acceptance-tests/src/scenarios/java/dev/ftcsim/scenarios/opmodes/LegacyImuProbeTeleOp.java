package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.bosch.BNO055IMU;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import org.firstinspires.ftc.robotcore.external.navigation.Acceleration;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;

/**
 * An IMU through the legacy {@code BNO055IMU} interface, the way the SDK's own sample sets it up: the parameters
 * object with an angle unit, {@code initialize}, and then orientation, gravity, angular velocity and linear
 * acceleration in every loop.
 */
@TeleOp(name = "Legacy IMU Probe", group = "Devices")
public final class LegacyImuProbeTeleOp extends OpMode {
    private BNO055IMU imu;

    @Override
    public void init() {
        imu = hardwareMap.get(BNO055IMU.class, ImuSettings.name);
        BNO055IMU.Parameters parameters = new BNO055IMU.Parameters();
        parameters.angleUnit = ImuSettings.radians ? BNO055IMU.AngleUnit.RADIANS : BNO055IMU.AngleUnit.DEGREES;
        Probe.record("initialized", imu.initialize(parameters) ? 1 : 0);
        Probe.record("systemStatus", imu.getSystemStatus().bVal);
        Probe.record("initDone", System.nanoTime());
    }

    @Override
    public void loop() {
        Orientation orientation = imu.getAngularOrientation();
        Acceleration gravity = imu.getGravity();
        Acceleration linear = imu.getLinearAcceleration();
        AngularVelocity rate = imu.getAngularVelocity();
        Probe.record("t", System.nanoTime());
        Probe.record("firstAngle", orientation.firstAngle);
        Probe.record("secondAngle", orientation.secondAngle);
        Probe.record("thirdAngle", orientation.thirdAngle);
        Probe.record("gravityX", gravity.xAccel);
        Probe.record("gravityY", gravity.yAccel);
        Probe.record("gravityZ", gravity.zAccel);
        Probe.record("linearX", linear.xAccel);
        Probe.record("linearY", linear.yAccel);
        Probe.record("linearZ", linear.zAccel);
        Probe.record("rateX", rate.xRotationRate);
        Probe.record("rateY", rate.yRotationRate);
        Probe.record("rateZ", rate.zRotationRate);
        Probe.record("temperature", imu.getTemperature().temperature);
        Probe.record("calibration", imu.getCalibrationStatus().calibrationStatus & 0xFF);
    }
}
