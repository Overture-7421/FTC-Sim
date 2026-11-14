package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.rev.RevColorSensorV3;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.ColorRangeSensor;
import com.qualcomm.robotcore.hardware.NormalizedRGBA;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/** Reads a REV Color Sensor V3 as Hornet's intake does, by the ColorRangeSensor interface, and its colours too. */
@TeleOp(name = "Color Range Sensor", group = "Devices")
public final class ColorSensorTeleOp extends OpMode {
    private ColorRangeSensor sensor;

    @Override
    public void init() {
        sensor = hardwareMap.get(ColorRangeSensor.class, "upSensor");
        Probe.record("isRevColorSensorV3", sensor instanceof RevColorSensorV3 ? 1 : 0);
    }

    @Override
    public void loop() {
        Probe.record("t", System.nanoTime());
        Probe.record("distanceCm", sensor.getDistance(DistanceUnit.CM));
        Probe.record("rawOptical", ((RevColorSensorV3) sensor).rawOptical());
        NormalizedRGBA colors = sensor.getNormalizedColors();
        Probe.record("normalizedRed", colors.red);
        Probe.record("normalizedGreen", colors.green);
        Probe.record("normalizedBlue", colors.blue);
        Probe.record("normalizedAlpha", colors.alpha);
        Probe.record("red", sensor.red());
        Probe.record("green", sensor.green());
        Probe.record("blue", sensor.blue());
        Probe.record("lightDetected", sensor.getLightDetected());
    }
}
