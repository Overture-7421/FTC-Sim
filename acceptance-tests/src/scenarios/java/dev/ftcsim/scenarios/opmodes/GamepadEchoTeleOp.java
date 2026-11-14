package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Shows both gamepads, as the OpMode sees them, in every callback from Init on. */
@TeleOp(name = "Gamepad Echo", group = "Input")
public final class GamepadEchoTeleOp extends OpMode {
    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
        show();
    }

    @Override
    public void init_loop() {
        show();
    }

    @Override
    public void loop() {
        show();
    }

    private void show() {
        telemetry.addLine(gamepad1 == null || gamepad2 == null ? "a gamepad is missing" : "both gamepads exist");
        if (gamepad1 != null && gamepad2 != null) {
            telemetry.addLine(Probe.describe(1, gamepad1));
            telemetry.addLine(Probe.describe(2, gamepad2));
        }
    }
}
