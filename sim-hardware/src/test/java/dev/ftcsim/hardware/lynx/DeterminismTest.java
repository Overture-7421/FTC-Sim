package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeterminismTest {

    @Test
    void aScriptedScenarioGivesByteIdenticalRepliesAndPlantHistoriesEveryTime() {
        ScriptedRun.Transcript first = new ScriptedRun().run();
        ScriptedRun.Transcript second = new ScriptedRun().run();

        assertEquals(first.exchanges(), second.exchanges());
        assertEquals(first.plantCalls(), second.plantCalls());
        assertEquals(first.finalState(), second.finalState());
    }

    @Test
    void theScenarioIsRichEnoughToMeanSomething() {
        ScriptedRun.Transcript run = new ScriptedRun().run();

        assertTrue(run.exchanges().size() > 800, "exchanges: " + run.exchanges().size());
        assertTrue(run.plantCalls().stream().anyMatch(call -> call.startsWith("volts")));
        assertTrue(run.plantCalls().stream().anyMatch(call -> call.equals("float")));
        assertTrue(run.exchanges().stream().anyMatch(line -> line.contains(" 7f02 ")), "no NACK in the scenario");
        assertTrue(run.finalState().stream().anyMatch(line -> line.startsWith("unsupported")));
    }
}
