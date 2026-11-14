package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.runtime.fixture.RandomProbe;
import dev.ftcsim.runtime.fixture.TimeProbe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A program that asks the JDK what time it is, or for a number nobody can predict, gets the host's answer and repeats itself. */
class TimeAndRandomRewriteTest {
    /** 2026-01-01T00:00:00.123Z. */
    private static final long INSTANT = 1_767_225_600_123L;

    @AfterEach
    void restoreHooks() {
        Hooks.installDefault();
    }

    /** The probe run on a thread of its own, because {@code ThreadLocalRandom} keeps its state in the thread. */
    private static List<String> run(String probe, SeededHooks hooks) throws Exception {
        Hooks.install(hooks);
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try (RobotClassLoader loader = FixtureWorld.loader()) {
                outcome.set(FixtureWorld.run(loader, probe));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        thread.start();
        thread.join();
        if (outcome.get() instanceof Throwable failure) {
            throw new AssertionError(probe + " failed: " + failure, failure);
        }
        @SuppressWarnings("unchecked")
        List<String> seen = (List<String>) outcome.get();
        return seen;
    }

    @Test
    void noClockOrRandomnessCallSurvivesInTheProgramsThatUseThem() throws IOException {
        List<RewriteRule> rules = new ArrayList<>(TimeRules.all());
        rules.addAll(RandomRules.all());
        for (Class<?> probe : List.of(TimeProbe.class, RandomProbe.class)) {
            List<String> calls = FixtureWorld.calls(FixtureWorld.rewritten(probe));

            for (RewriteRule rule : rules) {
                String member = rule.owner() + '.' + rule.name() + rule.descriptor();
                assertFalse(calls.contains(member), member + " is still called by " + probe.getSimpleName());
            }
        }
    }

    @Test
    void aSubclassesSuperCallGetsTheConstructorThatTakesTheHostsValue() throws IOException {
        List<String> date = FixtureWorld.calls(FixtureWorld.rewritten(TimeProbe.StampedDate.class));
        List<String> random = FixtureWorld.calls(FixtureWorld.rewritten(RandomProbe.Dice.class));

        assertTrue(date.contains("java/util/Date.<init>(J)V"), date.toString());
        assertTrue(date.contains("dev/ftcsim/contracts/hooks/Hooks.currentTimeMillis()J"), date.toString());
        assertTrue(random.contains("java/util/Random.<init>(J)V"), random.toString());
        assertTrue(random.contains("dev/ftcsim/contracts/hooks/Hooks.randomSeed()J"), random.toString());
    }

    @Test
    void everyWayOfAskingForTheTimeSeesTheHostsClock() throws Exception {
        List<String> seen = run("TimeProbe", new SeededHooks(INSTANT, 1));

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

    @Test
    void theSameSeedGivesTheSameNumbersAndAnotherSeedOtherNumbers() throws Exception {
        List<String> first = run("RandomProbe", new SeededHooks(INSTANT, 11));
        List<String> again = run("RandomProbe", new SeededHooks(INSTANT, 11));
        List<String> other = run("RandomProbe", new SeededHooks(INSTANT, 12));

        assertEquals(first, again, "the same seed gives the same numbers");
        for (int line = 0; line < first.size(); line++) {
            String name = first.get(line).substring(0, first.get(line).lastIndexOf(' '));
            if (name.startsWith("own ")) {
                assertEquals(first.get(line), other.get(line), "a program's own seed is left alone: " + name);
            } else {
                assertNotEquals(first.get(line), other.get(line), "another seed gives other numbers: " + name);
            }
        }
    }

    @Test
    void aProgramsOwnSeedStillGivesWhatItGivesAnywhere() throws Exception {
        List<String> seen = run("RandomProbe", new SeededHooks(INSTANT, 5));

        List<Integer> shuffled = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        Collections.shuffle(shuffled, new Random(7));
        assertTrue(seen.contains("own Random " + new Random(42).nextLong()), seen.toString());
        assertTrue(seen.contains("own shuffle " + shuffled), seen.toString());
    }

    @Test
    void theHostIsAskedForEverySeed() throws Exception {
        SeededHooks hooks = new SeededHooks(INSTANT, 3);

        run("RandomProbe", hooks);

        // Random, Dice, Random::new, SplittableRandom, UUID, shuffle, ThreadLocalRandom and five ways to a SecureRandom;
        // Math.random has a stream of its own and the program's own two seeds are not asked for.
        assertEquals(12, hooks.seedsHandedOut());
    }
}
