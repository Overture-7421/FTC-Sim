package dev.ftcsim.acceptance;

import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The BIOBUZZ robot the programs of the BIOBUZZ stub and the alliance TeleOps run on, and a reader for their telemetry. */
final class BiobuzzTestRobot {
    private static final Pattern NUMBER = Pattern.compile("(\\w+)=(-?\\d+(?:\\.\\d+)?)");

    private BiobuzzTestRobot() {}

    /** The BIOBUZZ robot of the simulator: its built-in profile. */
    static RobotSpec spec() {
        return RobotDesign.biobuzz().spec();
    }

    /** The number after {@code key=} in a telemetry line. */
    static double value(String line, String key) {
        Matcher matcher = NUMBER.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return Double.parseDouble(matcher.group(2));
            }
        }
        throw new AssertionError("no " + key + " in " + line);
    }
}
