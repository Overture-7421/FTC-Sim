package dev.ftcsim.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Pieces with rolling resistance must behave bit for bit alike in every JIT mode and with the debug build of the
 * natives, as the golden scenario does for the rest of the world.
 */
class RollingDeterminismTest {

    private static final long TIMEOUT_SECONDS = 300;

    private static List<String> runHere() {
        try (PhysicsWorld world = TestNatives.newWorld()) {
            return GoldenHashes.lines(RollingScenario.run(world));
        }
    }

    private static GoldenChildJvm.Variant variant(String name, boolean debugNatives, String... jvmArguments) {
        return new GoldenChildJvm.Variant(name, List.of(jvmArguments), debugNatives, 0);
    }

    private static String firstDifference(List<String> expected, List<String> actual) {
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "first differs at step " + expected.get(i).split(" ")[0];
            }
        }
        return expected.size() == actual.size() ? null : "expected " + expected.size() + " hashes but got " + actual.size();
    }

    @Test
    void aSecondWorldInTheSameJvmReproducesTheFirst() {
        assertNull(firstDifference(runHere(), runHere()));
    }

    @TestFactory
    Stream<DynamicTest> freshJvmsReproduceThisJvmsHashes() {
        List<String> here = runHere();
        return Stream.of(
                        variant("default JIT", false),
                        variant("-Xint", false, "-Xint"),
                        variant("-XX:TieredStopAtLevel=1", false, "-XX:TieredStopAtLevel=1"),
                        variant("-XX:-TieredCompilation", false, "-XX:-TieredCompilation"),
                        variant("DebugSp natives", true))
                .map(variant -> DynamicTest.dynamicTest(variant.name(), () ->
                        assertNull(
                                firstDifference(here, GoldenChildJvm.run(RollingScenario.class, variant, TIMEOUT_SECONDS)),
                                variant.name())));
    }

    @Test
    void theScenarioKeepsItsPiecesInsideAndPutsEveryPieceWithRollingResistanceToSleep() {
        try (PhysicsWorld world = TestNatives.newWorld()) {
            RollingScenario scenario = new RollingScenario(world);
            for (int step = 0; step < RollingScenario.STEPS; step++) {
                scenario.step();
            }
            System.out.println("ROLLING SCENARIO " + scenario.piecesAtRest(true) + " of " + RollingScenario.piecesWithRollingResistance()
                    + " pieces with rolling resistance at rest, " + scenario.piecesAtRest(false) + " of the others");
            assertTrue(scenario.allPiecesInside());
            assertEquals(RollingScenario.piecesWithRollingResistance(), scenario.piecesAtRest(true));
        }
    }
}
