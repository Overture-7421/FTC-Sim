package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.TouchSensor;

/** A limit switch read as a {@code TouchSensor}, as the SDK's own sample does, and a potentiometer read as an {@code AnalogInput}. */
@TeleOp(name = "Touch Limit", group = "Pins")
public final class TouchLimitTeleOp extends SlideProbe {
    private TouchSensor limit;
    private AnalogInput pot;
    private long firstPressedAt = -1;

    @Override
    void find() {
        limit = hardwareMap.get(TouchSensor.class, "slideLimit");
        pot = hardwareMap.get(AnalogInput.class, "slidePot");
    }

    @Override
    void show() {
        boolean pressed = limit.isPressed();
        if (pressed && firstPressedAt < 0) {
            firstPressedAt = System.nanoTime();
        }
        telemetry.addData("touch pressed", pressed);
        telemetry.addData("touch value", limit.getValue());
        telemetry.addData("first pressed ns", firstPressedAt);
        telemetry.addData("pot volts", pot.getVoltage());
        telemetry.addData("pot max volts", pot.getMaxVoltage());
    }
}
