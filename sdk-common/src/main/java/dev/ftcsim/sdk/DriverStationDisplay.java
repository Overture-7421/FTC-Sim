package dev.ftcsim.sdk;

import com.qualcomm.robotcore.robocol.TelemetryMessage;
import com.qualcomm.robotcore.util.RobotLog;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the Driver Station screen would show for the robot: the telemetry lines of the latest message the SDK
 * transmitted, the SDK's global warning and error text, and the stack trace the Driver Station pops up when user
 * code crashes. Written by the OpMode thread and the simulator's thread, read by the simulator's thread.
 */
final class DriverStationDisplay {
    /** Telemetry keys that start with NUL are not shown by the Driver Station; only their values are. */
    private static final char HIDDEN_KEY_MARK = '\0';
    private static final String CLEAR_KEY = "\0";

    private volatile List<String> lines = List.of();
    private volatile String crash = "";

    /** Takes one telemetry message, as the Driver Station would on receiving it: it replaces what was shown before. */
    void show(TelemetryMessage message) {
        Map<String, String> strings = message.getDataStrings();
        if (strings.size() == 1 && "".equals(strings.get(CLEAR_KEY))) {
            lines = List.of();
            crash = "";
            return;
        }
        List<String> shown = new ArrayList<>();
        strings.forEach((key, value) -> shown.add(isHidden(key) ? value : key + ": " + value));
        message.getDataNumbers().forEach((key, value) -> shown.add(key + ": " + value));
        lines = List.copyOf(shown);
    }

    /** The screen as it is when nothing has been said: no telemetry, no warning, no error. */
    void clear() {
        lines = List.of();
        crash = "";
        RobotLog.clearGlobalErrorMsg();
        RobotLog.clearGlobalWarningMsg();
    }

    void showCrash(String stackTrace) {
        crash = stackTrace;
    }

    TelemetrySnapshot snapshot() {
        String globalError = RobotLog.getGlobalErrorMsg();
        return new TelemetrySnapshot(lines, RobotLog.getGlobalWarningMessage().message, globalError.isEmpty() ? crash : globalError);
    }

    private static boolean isHidden(String key) {
        return !key.isEmpty() && key.charAt(0) == HIDDEN_KEY_MARK;
    }
}
