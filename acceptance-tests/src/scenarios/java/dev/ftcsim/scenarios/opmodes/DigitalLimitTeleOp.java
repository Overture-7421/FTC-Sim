package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DigitalChannel;

/** Three pins read as {@code DigitalChannel}s, set to input first as the SDK's own sample does. */
@TeleOp(name = "Digital Limit", group = "Pins")
public final class DigitalLimitTeleOp extends SlideProbe {
    private DigitalChannel limit;
    private DigitalChannel beam;
    private DigitalChannel idle;

    @Override
    void find() {
        limit = hardwareMap.get(DigitalChannel.class, "slideLimit");
        beam = hardwareMap.get(DigitalChannel.class, "beam");
        idle = hardwareMap.get(DigitalChannel.class, "idleSwitch");
        for (DigitalChannel pin : new DigitalChannel[] {limit, beam, idle}) {
            pin.setMode(DigitalChannel.Mode.INPUT);
        }
    }

    @Override
    void show() {
        telemetry.addData("limit state", limit.getState());
        telemetry.addData("beam state", beam.getState());
        telemetry.addData("idle state", idle.getState());
        telemetry.addData("limit mode", limit.getMode());
    }
}
