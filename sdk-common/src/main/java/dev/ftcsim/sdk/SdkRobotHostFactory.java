package dev.ftcsim.sdk;

import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.robot.RobotHost;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The way into the robot world. The simulator lives in another class loader than the SDK, so it reaches this
 * class by name and calls {@code create} reflectively; every parameter and the result are JDK or contract types.
 */
public final class SdkRobotHostFactory {
    private static boolean created;

    private SdkRobotHostFactory() {}

    /**
     * Boots the SDK on a simulated robot controller and registers the OpModes of the robot program. The SDK keeps
     * its state in singletons, so a robot world can host only one robot controller.
     *
     * @param storageRoot directory that stands in for the controller's external storage
     * @param program     the directories and jars that make up the robot program; the SDK scans them for OpModes
     * @param log         receives every log line the SDK and the robot program produce, in logcat style
     * @param environment the hubs the controller is wired to and where each device is plugged in
     */
    public static synchronized RobotHost create(
            Path storageRoot, List<Path> program, Consumer<String> log, RobotEnvironment environment) {
        return create(storageRoot, program, program, log, environment);
    }

    /**
     * Like {@link #create(Path, List, Consumer, RobotEnvironment)}, and says which entries of the program hold the team's
     * own code. The scan of the program reports the classes of the team in full, and summarises in one line those of the
     * SDK and of libraries that cannot be loaded on a desktop, instead of the hundred lines that each of them takes.
     *
     * @param team the entries of {@code program} with the team's own classes; the same list as {@code program} reports
     *             every class in full
     */
    public static synchronized RobotHost create(
            Path storageRoot, List<Path> program, List<Path> team, Consumer<String> log, RobotEnvironment environment) {
        if (created) {
            throw new IllegalStateException("This robot world already hosts a robot controller; the SDK's singletons allow only one");
        }
        created = true;
        return new SdkRobotHost(storageRoot, List.copyOf(program), List.copyOf(team), log, environment);
    }
}
