package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.runtime.fixture.TimeProbe;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A program that asks the JDK what time it is gets the host's clock. */
class TimeRewriteTest {
    /** 2026-01-01T00:00:00.123Z. */
    private static final long INSTANT = 1_767_225_600_123L;

    @AfterEach
    void restoreHooks() {
        Hooks.installDefault();
    }

    @Test
    void noClockCallSurvivesInTheProgramsThatUseThem() throws IOException {
        List<String> calls = FixtureWorld.calls(FixtureWorld.rewritten(TimeProbe.class));

        for (RewriteRule rule : TimeRules.all()) {
            String member = rule.owner() + '.' + rule.name() + rule.descriptor();
            assertFalse(calls.contains(member), member + " is still called by TimeProbe");
        }
    }

    @Test
    void aSubclassesSuperCallGetsTheConstructorThatTakesTheHostsValue() throws IOException {
        List<String> date = FixtureWorld.calls(FixtureWorld.rewritten(TimeProbe.StampedDate.class));

        assertTrue(date.contains("java/util/Date.<init>(J)V"), date.toString());
        assertTrue(date.contains("dev/ftcsim/contracts/hooks/Hooks.currentTimeMillis()J"), date.toString());
    }

    @Test
    void everyWayOfAskingForTheTimeSeesTheHostsClock() throws Exception {
        Hooks.install(new FixedClockHooks(INSTANT));
        Object seen;
        try (RobotClassLoader loader = FixtureWorld.loader()) {
            seen = FixtureWorld.run(loader, "TimeProbe");
        }

        assertEquals(
                List.of(
                        "System.currentTimeMillis " + INSTANT,
                        "Date " + INSTANT,
                        "Date subclass " + INSTANT,
                        "Date::new " + INSTANT,
                        "Instant " + INSTANT,
                        "Instant lambda " + INSTANT,
                        "LocalDate 2026-01-01",
                        "LocalDateTime 2026-01-01T00:00:00.123",
                        "ZonedDateTime " + INSTANT,
                        "OffsetDateTime " + INSTANT,
                        "Year 2026",
                        "Clock.systemUTC " + INSTANT,
                        "Clock.system " + INSTANT,
                        "Clock.tickSeconds " + (INSTANT - 123),
                        "Calendar " + INSTANT,
                        "GregorianCalendar " + INSTANT,
                        "GregorianCalendar.getInstance " + INSTANT),
                seen);
    }
}
