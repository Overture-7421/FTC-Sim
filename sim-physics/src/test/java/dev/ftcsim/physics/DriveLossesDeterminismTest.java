package dev.ftcsim.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * A chassis with the drivetrain losses, the stick-slip of its wheel friction among them, must behave bit for bit
 * alike in every JIT mode and with the debug build of the natives, as the golden scenario does for the rest of the
 * world.
 */
class DriveLossesDeterminismTest {

    private static final long TIMEOUT_SECONDS = 300;

    private static List<String> runHere() {
        try (PhysicsWorld world = TestNatives.newWorld()) {
            return GoldenHashes.lines(DriveLossesScenario.run(world));
        }
    }

    private static GoldenChildJvm.Variant variant(String name, boolean debugNatives, String... jvmArguments) {
        return new GoldenChildJvm.Variant(name, List.of(jvmArguments), debugNatives, 0);
    }

    @Test
    void aSecondWorldInTheSameJvmReproducesTheFirst() {
        assertEquals(runHere(), runHere());
    }

    @TestFactory
    Stream<DynamicTest> freshJvmsReproduceThisJvmsHashes() {
        List<String> here = runHere();
        return Stream.of(
                        variant("default JIT", false),
                        variant("-Xint", false, "-Xint"),
                        variant("-XX:TieredStopAtLevel=1", false, "-XX:TieredStopAtLevel=1"),
                        variant("DebugSp natives", true))
                .map(variant -> DynamicTest.dynamicTest(variant.name(), () ->
                        assertEquals(here, GoldenChildJvm.run(DriveLossesScenario.class, variant, TIMEOUT_SECONDS), variant.name())));
    }

    @Test
    void theScenarioLocksTheWheelsOfTheFloatingRobotAndDrivesOnAfterwards() {
        try (PhysicsWorld world = TestNatives.newWorld()) {
            DriveLossesScenario scenario = new DriveLossesScenario(world);
            for (int step = 0; step < DriveLossesScenario.AT_REST_BY; step++) {
                scenario.step();
            }
            BodyPose resting = world.bodyPose(DriveLossesScenario.ROBOT);
            assertTrue(resting.x() > -2.5, "the robot has not driven: x = " + resting.x());
            assertEquals(0.0, Math.hypot(resting.vx(), resting.vy()), 0.01);
            for (WheelPosition wheel : WheelPosition.values()) {
                assertEquals(0.0, scenario.robot().wheelSpeedRadPerSec(wheel), 1.0e-9, wheel.name());
            }

            for (int step = DriveLossesScenario.AT_REST_BY; step < DriveLossesScenario.STEPS; step++) {
                scenario.step();
            }
            BodyPose end = world.bodyPose(DriveLossesScenario.ROBOT);
            assertTrue(Math.hypot(end.x() - resting.x(), end.y() - resting.y()) > 1.0, "the robot ended at " + end);
        }
    }
}
