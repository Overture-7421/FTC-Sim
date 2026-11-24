package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** The blue driver program with the shot of the team's own tables ({@link BiobuzzTeamTables}) in place of the solver's. */
@TeleOp(name = "BIOBUZZ Team Tables Blue", group = "BIOBUZZ")
public final class BiobuzzTeamTablesBlueTeleOp extends BiobuzzAllianceTeleOp {
    public BiobuzzTeamTablesBlueTeleOp() {
        super(BiobuzzHive.Alliance.BLUE, BiobuzzTeamTables.PLAN);
    }
}
