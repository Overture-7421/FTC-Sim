package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AndroidEnvironmentTest {

    @Test
    void theSimulatedControllerIsARobotControllerWhoseTimeLibraryFindsItsTimeZoneDatabaseInTheAssets() throws Exception {
        try (RobotWorld world = RobotWorld.open(new DeclineNativesHooks())) {
            List<String> identity = world.run("dev.ftcsim.scenarios.DeviceIdentityScenario");

            assertEquals(
                    List.of("robotController=true", "driverStation=false", "newYorkInWinter=-05:00", "newYorkInSummer=-04:00"),
                    identity);
        }
    }
}
