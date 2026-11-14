package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DigitalChannel;

/**
 * Changes the direction of a pin and what it drives as the gamepad's buttons are pressed: A makes it an output, B drives
 * it high, Y drives it low, X makes it an input again. The telemetry shows the mode and state the SDK reports.
 */
@TeleOp(name = "Digital Modes", group = "Pins")
public final class DigitalModesTeleOp extends OpMode {
    private DigitalChannel beam;
    private boolean wasA;
    private boolean wasB;
    private boolean wasX;
    private boolean wasY;

    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(0);
        beam = hardwareMap.get(DigitalChannel.class, "beam");
    }

    @Override
    public void loop() {
        if (gamepad1.a && !wasA) {
            beam.setMode(DigitalChannel.Mode.OUTPUT);
        }
        if (gamepad1.b && !wasB) {
            beam.setState(true);
        }
        if (gamepad1.y && !wasY) {
            beam.setState(false);
        }
        if (gamepad1.x && !wasX) {
            beam.setMode(DigitalChannel.Mode.INPUT);
        }
        wasA = gamepad1.a;
        wasB = gamepad1.b;
        wasX = gamepad1.x;
        wasY = gamepad1.y;
        telemetry.addData("mode", beam.getMode());
        telemetry.addData("state", beam.getState());
    }
}
