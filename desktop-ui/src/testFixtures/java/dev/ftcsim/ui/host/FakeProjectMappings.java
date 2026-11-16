package dev.ftcsim.ui.host;

import dev.ftcsim.contracts.session.RobotMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ProjectMappings} for developing and testing the window: nothing is written into any project. A mapping is a
 * remembered fact, with a small stand-in file in the work folder so that there is a file to point at. The tests read
 * what was asked for through {@link #saved} and {@link #savedRunDefaults}.
 */
public final class FakeProjectMappings implements ProjectMappings {

    private final Path workFolder;
    private final Map<Path, Found> known = new ConcurrentHashMap<>();
    private final List<RobotMapping> saved = new CopyOnWriteArrayList<>();
    private final List<RunDefaults> savedRunDefaults = new CopyOnWriteArrayList<>();
    private final List<List<StartPosition>> savedStartPositions = new CopyOnWriteArrayList<>();
    private volatile IOException failure;

    /** @param workFolder where the stand-in files are written */
    public FakeProjectMappings(Path workFolder) {
        this.workFolder = workFolder;
    }

    /** A project that already has a mapping. */
    public FakeProjectMappings withMapping(Path project, RobotMapping mapping, RunDefaults run) {
        known.put(key(project), new Found(fileOf(project), mapping, run, false, null));
        return this;
    }

    /** A project that already has a mapping and named start positions. */
    public FakeProjectMappings withStartPositions(Path project, List<StartPosition> positions) {
        Found now = known.get(key(project));
        known.put(key(project), new Found(now.file(), now.mapping(), now.run(), now.keptByApp(), now.problem(), positions));
        return this;
    }

    /** A project whose file this version does not read, with the sentence the window shows for it. */
    public FakeProjectMappings withUnreadableFile(Path project, String problem) {
        known.put(key(project), new Found(fileOf(project), null, RunDefaults.NONE, false, problem));
        return this;
    }

    /** The project's file is gone, as if someone deleted it. */
    public FakeProjectMappings forget(Path project) {
        known.remove(key(project));
        return this;
    }

    /** Makes every following call fail with this, or none when null. */
    public FakeProjectMappings failingWith(IOException failure) {
        this.failure = failure;
        return this;
    }

    /** The mappings that {@link #save} was given, in order. */
    public List<RobotMapping> saved() {
        return List.copyOf(saved);
    }

    /** The defaults that {@link #saveRunDefaults} was given, in order. */
    public List<RunDefaults> savedRunDefaults() {
        return List.copyOf(savedRunDefaults);
    }

    @Override
    public Optional<Found> find(Path project) throws IOException {
        fail();
        return Optional.ofNullable(known.get(key(project)));
    }

    @Override
    public Saved save(Path project, RobotMapping mapping) throws IOException {
        fail();
        Path file = fileOf(project);
        Files.createDirectories(workFolder);
        Files.writeString(file, "{\"schema\":2,\"name\":\"" + mapping.name() + "\"}\n", StandardCharsets.UTF_8);
        saved.add(mapping);
        Found before = known.get(key(project));
        RunDefaults run = before == null || before.run() == null ? RunDefaults.NONE : before.run();
        known.put(key(project), new Found(file, mapping, run, false, null, before == null ? List.of() : before.startPositions()));
        return new Saved(file, false);
    }

    @Override
    public void saveRunDefaults(Path project, RunDefaults run) throws IOException {
        fail();
        Found now = known.get(key(project));
        if (now == null || !now.usable()) {
            throw new IOException("This project has no ftcsim.json.");
        }
        savedRunDefaults.add(run);
        known.put(key(project), new Found(now.file(), now.mapping(), run, now.keptByApp(), null, now.startPositions()));
    }

    @Override
    public void saveStartPositions(Path project, List<StartPosition> positions) throws IOException {
        fail();
        Found now = known.get(key(project));
        if (now == null || !now.usable()) {
            throw new IOException("This project has no ftcsim.json.");
        }
        savedStartPositions.add(List.copyOf(positions));
        known.put(key(project), new Found(now.file(), now.mapping(), now.run(), now.keptByApp(), null, positions));
    }

    /** The lists that {@link #saveStartPositions} was given, in order. */
    public List<List<StartPosition>> savedStartPositions() {
        return List.copyOf(savedStartPositions);
    }

    private Path fileOf(Path project) {
        return workFolder.resolve(project.getFileName() + "-ftcsim.json");
    }

    private void fail() throws IOException {
        IOException broken = failure;
        if (broken != null) {
            throw broken;
        }
    }

    private static Path key(Path project) {
        return project.toAbsolutePath().normalize();
    }
}
