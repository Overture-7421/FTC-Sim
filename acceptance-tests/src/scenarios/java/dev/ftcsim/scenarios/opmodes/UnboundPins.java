package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.TouchSensor;

/** Reads pins under names a robot profile does not mention, as a program does whose profile has not been written yet. */
abstract class UnboundPins extends OpMode {
    private TouchSensor touch;
    private DigitalChannel digital;
    private AnalogInput analog;

    /** Whether the program turns bulk caching on, as teams who want fast loops do. */
    abstract boolean bulkCaching();

    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(0);
        if (bulkCaching()) {
            for (LynxModule module : hardwareMap.getAll(LynxModule.class)) {
                module.setBulkCachingMode(LynxModule.BulkCachingMode.AUTO);
            }
        }
        touch = hardwareMap.get(TouchSensor.class, "missingTouch");
        digital = hardwareMap.get(DigitalChannel.class, "missingDigital");
        analog = hardwareMap.get(AnalogInput.class, "missingAnalog");
    }

    @Override
    public void loop() {
        telemetry.addData("touch pressed", touch.isPressed());
        telemetry.addData("digital state", digital.getState());
        telemetry.addData("analog volts", analog.getVoltage());
    }
}
