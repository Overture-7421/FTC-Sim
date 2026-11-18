package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** The BIOBUZZ driver program for the blue alliance: it tips the blue HIVE. See {@link BiobuzzAllianceTeleOp}. */
@TeleOp(name = "BIOBUZZ Blue", group = "BIOBUZZ")
public final class BiobuzzBlueTeleOp extends BiobuzzAllianceTeleOp {
    public BiobuzzBlueTeleOp() {
        super(BiobuzzHive.Alliance.BLUE);
    }
}
