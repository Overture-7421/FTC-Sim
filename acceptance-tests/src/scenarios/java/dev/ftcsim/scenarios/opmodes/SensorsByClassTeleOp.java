package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.digitalchickenlabs.OctoQuad;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.rev.Rev9AxisImu;
import com.qualcomm.hardware.rev.RevColorSensorV3;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ColorRangeSensor;
import com.qualcomm.robotcore.hardware.ColorSensor;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.NormalizedColorSensor;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;

/**
 * Asks for the sensors the robot has, under names of the program's own choice and through every class that finds them,
 * and then for devices the simulator cannot provide: a second Pinpoint, an OctoQuad, an external IMU and a webcam. What
 * each lookup said is shown as telemetry.
 */
@TeleOp(name = "Sensors By Class", group = "Devices")
public final class SensorsByClassTeleOp extends OpMode {

    private void ask(String caption, Class<? extends HardwareDevice> type, String name) {
        try {
            HardwareDevice found = hardwareMap.get(type, name);
            telemetry.addData(caption, "found " + found.getClass().getSimpleName());
        } catch (RuntimeException refused) {
            telemetry.addData(caption, refused.getMessage());
        }
    }

    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(0);
        ask("pinpoint", GoBildaPinpointDriver.class, "my odometry computer");
        ask("same pinpoint", GoBildaPinpointDriver.class, "my odometry computer");
        ask("second pinpoint", GoBildaPinpointDriver.class, "another one");
        ask("colour range", ColorRangeSensor.class, "colour");
        ask("colour normalized", NormalizedColorSensor.class, "colour");
        ask("colour legacy", ColorSensor.class, "colour");
        ask("colour v3", RevColorSensorV3.class, "colour");
        ask("second colour", ColorRangeSensor.class, "other colour");
        ask("octoquad", OctoQuad.class, "octo");
        ask("external imu", Rev9AxisImu.class, "extImu");
        ask("webcam", WebcamName.class, "Webcam 1");
        telemetry.update();
    }

    @Override
    public void loop() {}
}
