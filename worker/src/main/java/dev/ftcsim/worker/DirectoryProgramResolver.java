package dev.ftcsim.worker;

import dev.ftcsim.link.ClasspathFile;
import dev.ftcsim.simulator.RobotProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads a robot program from a folder that holds a {@value #FILE_NAME}: one {@code key=value} line each for
 * {@code robot} and {@code program}, which are class paths in the format of {@link ClasspathFile}, and optionally
 * {@code description}, which is text.
 *
 * <p>{@code robot} is everything the robot world loads: the team's code and libraries, the FTC SDK, the Android shim
 * and the SDK adapters. {@code program} is the part of it that holds the team's OpModes.
 */
public final class DirectoryProgramResolver implements ProgramResolver {

    public static final String FILE_NAME = "program.properties";

    private static final List<String> KEYS = List.of("robot", "program", "description");

    @Override
    public Optional<RobotProgram> resolve(Path path) throws IOException {
        Path file = path.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        ClasspathFile lines = ClasspathFile.read(file, KEYS);
        List<Path> robot = lines.classpath("robot");
        List<Path> program = lines.classpath("program");
        List<Path> strays = program.stream().filter(entry -> !robot.contains(entry)).toList();
        if (!strays.isEmpty()) {
            throw new IOException(file + ": 'program' lists entries that are not on 'robot', so the robot world cannot load them: " + strays);
        }
        String description = lines.text("description", path.toAbsolutePath().normalize().getFileName().toString());
        return Optional.of(new RobotProgram(robot, program, description));
    }

    @Override
    public String layout() {
        return "a folder with a " + FILE_NAME + " file";
    }
}
