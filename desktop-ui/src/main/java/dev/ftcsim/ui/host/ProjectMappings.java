package dev.ftcsim.ui.host;

import dev.ftcsim.contracts.session.RobotMapping;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The robot mapping that lives with a team's project: {@code ftcsim.json} at the project's root, the only file FTC-Sim
 * ever adds to a project. The application around the window implements it, because it knows the file's format and
 * where files may be written. The file is FTC-Sim's own: it is read and written whole.
 *
 * <p>Every method touches the disk: call them off the UI thread.
 */
public interface ProjectMappings {

    /**
     * A file that exists.
     *
     * @param file      the file that was read
     * @param mapping   what it maps, or null when the file is not one this version reads ({@code problem} says why)
     * @param run       how the file says a run starts; {@link RunDefaults#NONE} when it is not read
     * @param keptByApp whether the file is FTC-Sim's own copy, because the project's folder could not be written to
     * @param problem   a short sentence for the person when the file cannot be used, else null
     * @param startPositions the start positions the team named in the file; none when it is not read
     */
    record Found(Path file, RobotMapping mapping, RunDefaults run, boolean keptByApp, String problem, List<StartPosition> startPositions) {

        public Found {
            startPositions = startPositions == null ? List.of() : List.copyOf(startPositions);
        }

        /** A file with no named start positions. */
        public Found(Path file, RobotMapping mapping, RunDefaults run, boolean keptByApp, String problem) {
            this(file, mapping, run, keptByApp, problem, List.of());
        }

        /** Whether the file maps a robot, so that a run can use it. */
        public boolean usable() {
            return mapping != null;
        }
    }

    /**
     * A mapping that was just written.
     *
     * @param file      the file that was written
     * @param keptByApp whether it was kept in FTC-Sim's own folder because the project's cannot be written to
     */
    record Saved(Path file, boolean keptByApp) {}

    /** The project's file, or empty when it has none. A file that cannot be used is found, with its problem. */
    Optional<Found> find(Path project) throws IOException;

    /**
     * Writes the whole mapping as the project's file and keeps the run defaults that file had. A file that is not one this
     * version reads is copied to {@code ftcsim.old.json} next to it first, and never lost.
     */
    Saved save(Path project, RobotMapping mapping) throws IOException;

    /** Writes how a run starts into the project's existing file and leaves the mapping as it was. */
    void saveRunDefaults(Path project, RunDefaults run) throws IOException;

    /** Writes the project's named start positions into its existing file, replacing the ones it had, and leaves the rest as it was. */
    void saveStartPositions(Path project, List<StartPosition> positions) throws IOException;
}
