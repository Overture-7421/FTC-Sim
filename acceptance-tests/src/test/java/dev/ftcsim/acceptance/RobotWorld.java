package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.contracts.hooks.RobotHooks;
import dev.ftcsim.runtime.HookRewriter;
import dev.ftcsim.runtime.RobotClassLoader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A robot world for tests: the SDK, the simulator's adapters and some scenario code, loaded the way the
 * simulator loads a robot program, with the JDK calls the simulator virtualises already redirected.
 */
class RobotWorld implements AutoCloseable {
    private static final Map<Path, Path> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final AtomicInteger COPIES = new AtomicInteger();
    private static Path snapshotRoot;

    private final RobotClassLoader loader;

    protected RobotWorld(RobotClassLoader loader) {
        this.loader = loader;
    }

    static RobotWorld open(RobotHooks hooks) throws IOException {
        return new RobotWorld(newLoader(hooks));
    }

    static RobotClassLoader newLoader(RobotHooks hooks) throws IOException {
        Hooks.install(hooks);
        return new RobotClassLoader(
                classpath("ftcsim.robotClasspath"),
                RobotWorld.class.getClassLoader(),
                name -> name.startsWith("dev.ftcsim.contracts."),
                new HookRewriter());
    }

    /**
     * The entries of a path-list system property that Gradle sets for the tests. A class folder is a copy made the
     * first time it is asked for and kept for the life of this JVM: another Gradle run in the same tree, say a
     * colleague's compile, may rebuild or delete the original while a test is still loading classes from it, and that
     * must not fail this test. Jars stay where they are.
     */
    static List<Path> classpath(String property) {
        return Arrays.stream(System.getProperty(property).split(File.pathSeparator)).map(Path::of).map(RobotWorld::stable).toList();
    }

    /** The same entries as a path-list string, for files that name a class path. */
    static String classpathText(String property) {
        return classpath(property).stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private static Path stable(Path entry) {
        if (!Files.isDirectory(entry)) {
            return entry;
        }
        return SNAPSHOTS.computeIfAbsent(entry, original -> {
            try {
                return copy(original);
            } catch (IOException failure) {
                throw new UncheckedIOException("cannot keep a copy of " + original, failure);
            }
        });
    }

    private static synchronized Path copy(Path original) throws IOException {
        if (snapshotRoot == null) {
            Path root = Files.createTempDirectory("ftcsim-classes");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(root)));
            snapshotRoot = root;
        }
        Path target = Files.createDirectories(snapshotRoot.resolve(COPIES.incrementAndGet() + "-" + original.getFileName()));
        Files.walkFileTree(original, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(original.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(original.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        return target;
    }

    private static void deleteQuietly(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // A temporary folder that cannot be removed now is removed with the next clean-up of the temp folder.
        }
    }

    /** Runs a scenario class from the robot world and returns the plain value it produced. */
    @SuppressWarnings("unchecked")
    <T> T run(String scenarioClass) throws Exception {
        Class<?> type = load(scenarioClass);
        return ((Callable<T>) type.getDeclaredConstructor().newInstance()).call();
    }

    protected Class<?> load(String className) throws ClassNotFoundException {
        return loader.loadClass(className);
    }

    @Override
    public void close() throws IOException {
        Hooks.installDefault();
        loader.close();
    }
}
