package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;

/**
 * What {@link HornetCrossJvmTest} starts in a JVM of its own: Hornet's OpMode on the simulated robot and the DECODE field,
 * for a number of simulated seconds after Start, and then one line with everything that could differ between two runs of
 * the same program, in exact figures. Arguments: the OpMode's name, the seconds after Start.
 */
public final class HornetChildRun {
    /** The prefix of the line the test reads; whatever else the JVM prints is of no interest. */
    static final String RESULT = "CHILD-STATE> ";

    private static final long SECOND = 1_000_000_000L;

    private HornetChildRun() {}

    public static void main(String[] arguments) throws Exception {
        int status = 1;
        try {
            run(arguments[0], Long.parseLong(arguments[1]));
            status = 0;
        } finally {
            System.out.flush();
            // A robot world leaves threads of its own behind, which are not daemons, so the JVM would never end by itself.
            System.exit(status);
        }
    }

    private static void run(String opMode, long seconds) throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotProfile profile = RobotProfileLoader.loadBundled("hornet-2026.json");
        DecodeField field = new DecodeField();
        StartPose start = field.description().startPoses().stream()
                .filter(pose -> pose.name().equals("BLUE GOAL SIDE"))
                .findFirst()
                .orElseThrow();
        try (TestSessions.Handle handle = TestSessions.open(
                profile, program, field, new FieldSetup(1, Alliance.BLUE), start.x(), start.y(), start.headingRadians())) {
            SimulationSession session = handle.session;
            session.init(opMode);
            session.runFor(2 * SECOND);
            session.start();
            session.runFor(seconds * SECOND);
            BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
            System.out.println(RESULT + "now=" + session.now() + " world=" + session.world().stateHash()
                    + " x=" + Long.toHexString(Double.doubleToLongBits(pose.x()))
                    + " y=" + Long.toHexString(Double.doubleToLongBits(pose.y()))
                    + " yaw=" + Long.toHexString(Double.doubleToLongBits(pose.yaw()))
                    + " telemetry=" + session.telemetry().lines().hashCode()
                    + " handoffs=" + session.diagnostics().handoffs()
                    + " problems=" + session.problems());
            System.out.println("CHILD-DETAIL> pose (" + pose.x() + ", " + pose.y() + ", yaw " + pose.yaw() + ") phase " + session.phase());
        }
    }
}
