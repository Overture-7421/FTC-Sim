package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** A Pinpoint that robot code told the pods are on the tracking point, which they are not. */
@TeleOp(name = "Pinpoint Odometry Without Offsets", group = "Devices")
public final class PinpointWithoutOffsetsTeleOp extends PinpointProbe {
    public PinpointWithoutOffsetsTeleOp() {
        super(0, 0);
    }
}
