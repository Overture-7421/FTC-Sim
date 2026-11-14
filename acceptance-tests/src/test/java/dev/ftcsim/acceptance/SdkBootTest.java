package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SdkBootTest {

    @Test
    void theRealSdkSingletonsComeUpOnADesktopJvm() throws Exception {
        DeclineNativesHooks hooks = new DeclineNativesHooks();
        try (RobotWorld world = RobotWorld.open(hooks)) {
            List<String> log = world.run("dev.ftcsim.scenarios.SdkBootScenario");

            assertTrue(log.stream().anyMatch(line -> line.contains("hello 7")), log.toString());
            assertTrue(log.contains("pool=42"), log.toString());
            assertTrue(log.contains("first=true"), log.toString());
            assertTrue(log.contains("hardwareMap=true"), log.toString());
        }
        assertEquals(List.of("RobotCore"), hooks.declinedLibraries.stream().distinct().toList());
    }
}
