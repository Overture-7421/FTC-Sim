package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A team's intake and shooter driving simulated mechanisms: the SDK, the hubs, the shaft plants and the zones that
 * take pieces in, carry them and launch them, with nothing in the robot program that knows about any of it.
 */
@Timeout(180)
class MechanismsSessionTest {
    private static final long SECOND = 1_000_000_000L;

    private static final String PROFILE = """
            {
              "schema": 1, "name": "mechanism test rig",
              "motors": {
                "intake": { "port": 0, "motor": "goBILDA-5203-435", "shaft": { "loadInertiaKgM2": 1.0e-4, "frictionNmsPerRad": 1.0e-3 } },
                "up": { "port": 1, "motor": "goBILDA-5203-435", "shaft": { "loadInertiaKgM2": 1.0e-4, "frictionNmsPerRad": 1.0e-3 } },
                "shooterLeft": { "port": 2, "motor": "goBILDA-bare-6000", "shaft": { "loadInertiaKgM2": 2.5e-4, "frictionNmsPerRad": 1.0e-5 } },
                "shooterRight": { "port": 3, "motor": "goBILDA-bare-6000", "shaft": { "loadInertiaKgM2": 2.5e-4, "frictionNmsPerRad": 1.0e-5 } }
              },
              "mechanisms": {
                "storages": {
                  "magazine": {
                    "capacity": 3,
                    "path": [[0.30, 0.0, 0.0635], [-0.05, 0.0, 0.30]],
                    "drives": [
                      { "motor": "intake", "radiusM": 0.025, "sign": 1, "from": 0.0, "to": 0.25 },
                      { "motor": "up", "radiusM": 0.02, "sign": 1, "from": 0.1 }
                    ]
                  }
                },
                "intakes": {
                  "front": {
                    "storage": "magazine", "center": [0.30, 0.0, 0.0635], "half": [0.05, 0.2, 0.1],
                    "when": [ { "motor": "intake", "minRadPerSec": 10.0 } ]
                  }
                },
                "launchers": {
                  "shooter": {
                    "storage": "magazine", "direction": [-0.6, 0.0, 0.8], "efficiency": 0.8,
                    "wheels": [
                      { "motor": "shooterLeft", "radiusM": 0.05, "sign": 1 },
                      { "motor": "shooterRight", "radiusM": 0.05, "sign": 1 }
                    ],
                    "when": [ { "motor": "up", "minRadPerSec": 20.0 } ]
                  }
                }
              }
            }
            """;

    private static GamepadState pad(float rightTrigger, boolean a) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, rightTrigger, false, false, false, false, a, false, false, false, false, false, false, false,
                false, false, false);
    }

    private static boolean inWorld(SimulationSession session, int id) {
        return session.world().hasPiece(id);
    }

    @Test
    void thePiecesInFrontOfTheIntakeAreTakenInThenCarriedThenShotWhenTheFlywheelsAreUpToSpeed() throws IOException {
        RobotProfile profile = RobotProfileLoader.parse(PROFILE, "test profile");
        try (TestSessions.Handle handle = TestSessions.open(profile, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Intake Shooter");
            session.start();

            session.setGamepad(1, pad(1f, false));
            session.runFor(4 * SECOND);

            for (int id : TestField.ON_FLOOR) {
                assertFalse(inWorld(session, id), "piece " + id + " should be inside the robot");
            }
            List<BodyState> carried = session.snapshot().bodies().stream().filter(b -> TestField.ON_FLOOR.contains(b.entityId())).toList();
            assertEquals(3, carried.size(), "carried pieces are part of what a viewer sees");
            assertEquals(List.of(), session.problems(), handle.log.toString());

            session.setGamepad(1, pad(0f, true));
            session.runFor(3 * SECOND);

            for (int id : TestField.ON_FLOOR) {
                assertTrue(inWorld(session, id), "piece " + id + " should have been shot out");
                BodyPose pose = session.world().bodyPose(id);
                assertTrue(pose.position().minus(new Vec3(0.30, 0, 0)).length() > 0.3, "piece " + id + " should have left: " + pose);
            }
            assertEquals(List.of(), session.problems());
        }
    }

    @Test
    void aSensorOnTheRobotSeesAPieceTheRobotCarriesThoughItIsNotInThePhysicsWorld() throws IOException {
        RobotProfile profile = RobotProfileLoader.parse(PROFILE, "test profile");
        try (TestSessions.Handle handle = TestSessions.open(profile, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Intake Shooter");
            session.start();
            session.setGamepad(1, pad(1f, false));
            session.runFor(4 * SECOND);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND / 2);

            // Three pieces wait along the path, one diameter apart, the first at the end of it. A ray that starts
            // beyond the third, looking back along the path towards the end, meets the third first.
            Vec3 stage = new Vec3(-0.05, 0, 0.30);
            Vec3 along = new Vec3(0.30, 0, 0.0635).minus(stage);
            Vec3 towardsEntrance = along.times(1.0 / along.length());
            double start = 0.40;
            var hit = session.rays().cast(stage.plus(towardsEntrance.times(start)), towardsEntrance.times(-1.0), 1.0);

            assertTrue(hit != null, "the carried piece should be visible to a ray");
            assertEquals(start - 2 * 2 * TestField.RADIUS - TestField.RADIUS, hit.distanceMeters(), 0.01);
            assertEquals(TestField.ON_FLOOR.get(2), hit.entityId(), "the piece that was taken in last is the third in line");
            assertEquals(TestField.PIECE_KIND, hit.kind());
            assertEquals(0x7e57c2, hit.rgb());
        }
    }

    @Test
    void thePiecesTheFieldHoldsInReserveCanBeLoadedIntoTheRobotAsAPreload() throws IOException {
        RobotProfile profile = RobotProfileLoader.parse(PROFILE, "test profile");
        try (TestSessions.Handle handle = TestSessions.open(profile, new TestField(), 0, 0, 0)) {
            assertEquals(2, handle.session.preload("magazine", TestField.PIECE_KIND, 5), "the reserve only holds two");

            handle.session.runFor(SECOND / 100);
            assertEquals(2, handle.session.snapshot().bodies().stream().filter(b -> TestField.IN_RESERVE.contains(b.entityId())).count());
        }
    }
}
