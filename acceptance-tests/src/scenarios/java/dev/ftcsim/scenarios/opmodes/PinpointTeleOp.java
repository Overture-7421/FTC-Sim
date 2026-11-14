package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** A Pinpoint told where its pods are, with Hornet's offsets. */
@TeleOp(name = "Pinpoint Odometry", group = "Devices")
public final class PinpointTeleOp extends PinpointProbe {
    public PinpointTeleOp() {
        super(-4.467131, -0.160124);
    }
}
