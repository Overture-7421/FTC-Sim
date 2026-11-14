package dev.ftcsim.scenarios;

import dev.ftcsim.scenarios.opmodes.ImuSettings;
import java.util.List;
import java.util.concurrent.Callable;

/** Reads what the IMU scenario OpModes caught, from inside the robot world where it is kept. */
public final class ReadImuMessages implements Callable<List<String>> {
    @Override
    public List<String> call() {
        synchronized (ImuSettings.messages) {
            return List.copyOf(ImuSettings.messages);
        }
    }
}
