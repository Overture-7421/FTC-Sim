package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Pins the profile does not mention, read one at a time. */
@TeleOp(name = "Unbound Pins", group = "Pins")
public final class UnboundPinsTeleOp extends UnboundPins {
    @Override
    boolean bulkCaching() {
        return false;
    }
}
