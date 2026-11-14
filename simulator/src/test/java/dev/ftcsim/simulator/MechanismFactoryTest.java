package dev.ftcsim.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.mechanisms.Mechanisms;
import dev.ftcsim.mechanisms.PlantView;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.simulator.profile.ProfileException;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MechanismFactoryTest {
    private static final Map<Integer, PieceSpec> PIECES = Map.of(100, new PieceSpec(0.0635f, 0.07f, 0.5f, 0.2f, 0.02f));

    /** Every motor of the profile exists as a shaft; nothing turns. */
    private static PlantView motorsOf(RobotProfile profile) {
        Set<String> names = profile.motors().keySet();
        return new PlantView() {
            @Override
            public boolean hasShaft(String motor) {
                return names.contains(motor);
            }

            @Override
            public double shaftSpeedRadPerSec(String motor) {
                return 0.0;
            }

            @Override
            public void extractEnergy(String motor, double joules) {}
        };
    }

    private static String problemWith(String mechanisms) {
        String json = "{\"schema\":1,\"name\":\"x\",\"motors\":{\"roller\":{\"port\":0,\"shaft\":{\"loadInertiaKgM2\":1e-4}},"
                + "\"wheelA\":{\"port\":1,\"shaft\":{\"loadInertiaKgM2\":1e-4}}},\"mechanisms\":" + mechanisms + "}";
        RobotProfile profile = RobotProfileLoader.parse(json, "test.json");
        return assertThrows(ProfileException.class, () -> MechanismFactory.create(profile, motorsOf(profile), PIECES)).getMessage();
    }

    @Test
    void theBundledHornetProfileDescribesMechanismsThatAreBuiltFromItsOwnMotors() throws IOException {
        RobotProfile profile = RobotProfileLoader.loadBundled("hornet-2026.json");

        Mechanisms mechanisms = MechanismFactory.create(profile, motorsOf(profile), PIECES);

        assertEquals(java.util.List.of(), mechanisms.contents("magazine"));
        assertTrue(mechanisms.canLoad("magazine", 100));
    }

    @Test
    void anIntakeThatNamesAStorageThatIsNotThereIsRefusedWithTheProfilePlace() {
        String message = problemWith("{\"intakes\":{\"front\":{\"storage\":\"hopper\",\"center\":[0,0,0],\"half\":[1,1,1]}}}");

        assertTrue(message.contains("mechanisms.intakes.front"), message);
        assertTrue(message.contains("hopper"), message);
    }

    @Test
    void anIntakeWhosePieceSizesRunTheWrongWayIsRefusedWithTheProfilePlace() {
        String message = problemWith(
                "{\"storages\":{\"s\":{\"capacity\":1,\"path\":[[0,0,0],[1,0,0]]}},"
                        + "\"intakes\":{\"front\":{\"storage\":\"s\",\"center\":[0,0,0],\"half\":[1,1,1],"
                        + "\"minPieceDiameterM\":0.15,\"maxPieceDiameterM\":0.10}}}");

        assertTrue(message.contains("mechanisms.intakes.front"), message);
        assertTrue(message.contains("diameters"), message);
    }

    @Test
    void aPointThatIsNotThreeNumbersAndAStorageWithoutRoomAreRefused() {
        assertTrue(problemWith("{\"storages\":{\"s\":{\"capacity\":1,\"path\":[[0,0],[1,0,0]]}}}").contains("mechanisms.storages.s.path[0]"));
        assertTrue(problemWith("{\"storages\":{\"s\":{\"capacity\":0,\"path\":[[0,0,0],[1,0,0]]}}}").contains("capacity must be at least 1"));
    }

    @Test
    void aDriveOrConditionThatFollowsAMotorTheRobotDoesNotHaveIsRefused() {
        String message = problemWith(
                "{\"storages\":{\"s\":{\"capacity\":1,\"path\":[[0,0,0],[1,0,0]],"
                        + "\"drives\":[{\"motor\":\"ghost\",\"radiusM\":0.02,\"sign\":1}]}}}");

        assertTrue(message.contains("ghost"), message);
    }

    @Test
    void aLauncherWheelMustBeAShaftOfTheProfile() {
        String message = problemWith(
                "{\"storages\":{\"s\":{\"capacity\":1,\"path\":[[0,0,0],[1,0,0]]}},"
                        + "\"launchers\":{\"l\":{\"storage\":\"s\",\"direction\":[1,0,0],\"efficiency\":0.5,"
                        + "\"wheels\":[{\"motor\":\"nothing\",\"radiusM\":0.05,\"sign\":1}]}}}");

        assertTrue(message.contains("mechanisms.launchers.l"), message);
        assertTrue(message.contains("nothing"), message);
    }
}
