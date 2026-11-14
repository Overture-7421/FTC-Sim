package dev.ftcsim.worker;

import dev.ftcsim.simulator.RobotProgram;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Turns the path of a prepared robot program into what the simulator runs. One implementation per layout on disk. */
public interface ProgramResolver {

    /**
     * The program prepared at {@code path}.
     *
     * @return empty when {@code path} is not in the layout this resolver reads, so that another can be tried
     * @throws IOException when it is in this layout and cannot be used; the message says what is wrong
     */
    Optional<RobotProgram> resolve(Path path) throws IOException;

    /** What this resolver reads, in a few words, for the message that says no resolver recognised a path. */
    String layout();
}
