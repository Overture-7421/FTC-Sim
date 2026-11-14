package dev.ftcsim.ui.pacing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.platform.FrameLimiter;
import org.junit.jupiter.api.Test;

class PacingTest {
    private static void assertRate(double refresh, double expected) {
        assertEquals(expected, Pacing.targetHz(refresh, Pacing.DEFAULT_CAP_HZ), 1e-9, "target for " + refresh + " Hz");
    }

    @Test
    void displaysAtOrBelowTheCapAreFollowed() {
        assertRate(60, 60);
        assertRate(59.94, 59.94);
        assertRate(120, 120);
        assertRate(144, 144);
        assertRate(143.98, 143.98);
    }

    @Test
    void unknownRefreshFallsBackToSixtyHertz() {
        assertRate(0, 60);
        assertRate(-1, 60);
    }

    @Test
    void fastDisplaysUseAnIntegerDivisorWhenItStaysNearTheCap() {
        assertRate(240, 120);
        assertRate(280, 140);
        assertRate(288, 144);
        assertRate(360, 120);
    }

    @Test
    void fastDisplaysWithoutAGoodDivisorAreHeldAtTheCap() {
        assertRate(165, 144);
        assertRate(175, 144);
    }

    @Test
    void theCreatedPacerRunsAtTheTargetRate() {
        try (Pacing pacing = Pacing.create(280.0, Pacing.DEFAULT_CAP_HZ)) {
            assertEquals(140.0, pacing.targetHz(), 1e-9);
            assertTrue(pacing.pacer() instanceof FrameLimiter);
            assertTrue(pacing.description().contains("140.0 Hz"), pacing.description());
        }
    }
}
