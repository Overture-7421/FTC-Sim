package dev.ftcsim.worker;

import dev.ftcsim.importer.pack.PackageException;
import dev.ftcsim.importer.pack.PackageLayout;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.RobotProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a program that the project importer prepared: a folder with a {@value PackageLayout#MANIFEST} and the files it
 * lists, every one of which is checked against its hash. The package holds the team's code, its libraries and the FTC
 * SDK it was built with; the simulator's own jars (the Android shim and the SDK adapters) are not part of it, so the
 * resolver is given them and puts them first on the robot class path.
 */
public final class PreparedProgramResolver implements ProgramResolver {

    private final List<Path> simulatorClasspath;

    /** @param simulatorClasspath the simulator's own jars or class folders that the robot world needs besides a package */
    public PreparedProgramResolver(List<Path> simulatorClasspath) {
        this.simulatorClasspath = List.copyOf(simulatorClasspath);
    }

    @Override
    public Optional<RobotProgram> resolve(Path path) throws IOException {
        if (!Files.isRegularFile(path.resolve(PackageLayout.MANIFEST))) {
            return Optional.empty();
        }
        if (simulatorClasspath.isEmpty()) {
            throw new IOException(path + " is a prepared program, but this worker was not told where the simulator's own jars are "
                    + "(the system property ftcsim.simulatorClasspath), which a prepared program needs to run");
        }
        PreparedProgram prepared;
        try {
            prepared = PreparedProgram.open(path);
        } catch (PackageException damaged) {
            throw new IOException(damaged.getMessage(), damaged);
        }
        List<Path> robot = new ArrayList<>(simulatorClasspath);
        robot.addAll(prepared.robotClasspath());
        String description = prepared.manifest().project().name() + " (FTC SDK " + prepared.sdkVersion() + ")";
        return Optional.of(new RobotProgram(robot, prepared.applicationClasspath(), prepared.programClasspath(), description));
    }

    @Override
    public String layout() {
        return "a folder with a " + PackageLayout.MANIFEST + " that the project importer wrote";
    }
}
