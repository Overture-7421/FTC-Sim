package dev.ftcsim.simulator;

import java.nio.file.Path;
import java.util.List;

/**
 * The robot program in the form the simulator runs it: everything the robot world loads, and the part of it that
 * is the team's own code.
 *
 * @param robotClasspath   the classes of the robot world: the team's program and libraries, the FTC SDK, the
 *                         simulator's Android shim and its SDK adapters
 * @param programClasspath the entries of {@code robotClasspath} that hold the team's OpModes, which the SDK scans
 * @param teamClasspath    the entries of {@code programClasspath} that hold the team's own code and not the libraries'
 *                         or the SDK's; the scan of the program reports a class of these that cannot be loaded in
 *                         full, and one line stands for all the classes of the rest. The same entries as
 *                         {@code programClasspath} report every class in full
 * @param description      a name for the program in logs and in the UI, for example a project name and the SDK version
 */
public record RobotProgram(
        List<Path> robotClasspath, List<Path> programClasspath, List<Path> teamClasspath, String description) {

    public RobotProgram {
        robotClasspath = List.copyOf(robotClasspath);
        programClasspath = List.copyOf(programClasspath);
        teamClasspath = List.copyOf(teamClasspath);
    }

    /** A program that does not say which of its entries are the team's own, so that the scan reports all of it in full. */
    public RobotProgram(List<Path> robotClasspath, List<Path> programClasspath, String description) {
        this(robotClasspath, programClasspath, programClasspath, description);
    }
}
