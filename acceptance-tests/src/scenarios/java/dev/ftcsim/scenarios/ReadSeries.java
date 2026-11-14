package dev.ftcsim.scenarios;

import dev.ftcsim.scenarios.opmodes.Probe;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Reads the series of values the fixture OpModes recorded, from inside the robot world where they are kept. */
public final class ReadSeries implements Callable<Map<String, List<Double>>> {
    @Override
    public Map<String, List<Double>> call() {
        return Probe.series();
    }
}
