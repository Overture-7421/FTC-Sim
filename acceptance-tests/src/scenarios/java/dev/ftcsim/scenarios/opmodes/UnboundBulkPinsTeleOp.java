package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Pins the profile does not mention, read from the hub's bulk data. */
@TeleOp(name = "Unbound Bulk Pins", group = "Pins")
public final class UnboundBulkPinsTeleOp extends UnboundPins {
    @Override
    boolean bulkCaching() {
        return true;
    }
}
