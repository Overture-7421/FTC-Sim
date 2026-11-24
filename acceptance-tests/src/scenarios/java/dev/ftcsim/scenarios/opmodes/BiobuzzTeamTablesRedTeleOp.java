package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** The red driver program with the shot of the team's own tables ({@link BiobuzzTeamTables}) in place of the solver's. */
@TeleOp(name = "BIOBUZZ Team Tables Red", group = "BIOBUZZ")
public final class BiobuzzTeamTablesRedTeleOp extends BiobuzzAllianceTeleOp {
    public BiobuzzTeamTablesRedTeleOp() {
        super(BiobuzzHive.Alliance.RED, BiobuzzTeamTables.PLAN);
    }
}
