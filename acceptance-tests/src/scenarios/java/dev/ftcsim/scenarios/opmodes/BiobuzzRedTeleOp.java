package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** The BIOBUZZ driver program for the red alliance: it tips the red HIVE. See {@link BiobuzzAllianceTeleOp}. */
@TeleOp(name = "BIOBUZZ Red", group = "BIOBUZZ")
public final class BiobuzzRedTeleOp extends BiobuzzAllianceTeleOp {
    public BiobuzzRedTeleOp() {
        super(BiobuzzHive.Alliance.RED);
    }
}
