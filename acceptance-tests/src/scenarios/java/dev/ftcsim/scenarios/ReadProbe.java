package dev.ftcsim.scenarios;

import dev.ftcsim.scenarios.opmodes.Probe;
import java.util.Map;
import java.util.concurrent.Callable;

/** Reads how often the fixture OpModes ran each callback, from inside the robot world where that is recorded. */
public final class ReadProbe implements Callable<Map<String, Integer>> {
    @Override
    public Map<String, Integer> call() {
        return Probe.counts();
    }
}
