package dev.ftcsim.physics;

import java.nio.file.Path;

/**
 * Runs the golden scenario in this JVM and prints the golden hash lines (see {@link GoldenHashes}),
 * preceded by a comment line that names the engine build. The determinism test starts it in fresh
 * JVMs with different JIT modes and native builds, and {@code gradlew -q :sim-physics:goldenHashes}
 * prints the lines that make up the golden resource.
 *
 * <p>Arguments, all optional: the SHA-256 the native library must have (the ReleaseSp pin by
 * default), and the number of worker threads of the job system (0, the default, is the
 * single-threaded system the build is validated with). The extraction directory comes from
 * {@code -Dftcsim.nativeDirectory}.
 */
public final class GoldenRunner {

    private GoldenRunner() {}

    public static void main(String[] args) {
        String dllSha256 = args.length > 0 ? args[0] : TestNatives.RELEASE_SHA256;
        int workerThreads = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        Path nativeDirectory = Path.of(System.getProperty("ftcsim.nativeDirectory"));
        try (PhysicsWorld world = PhysicsWorld.create(nativeDirectory, dllSha256, workerThreads)) {
            System.out.println("# " + world.engineInfo() + ", " + workerThreads + " worker threads");
            GoldenHashes.lines(GoldenScenario.run(world)).forEach(System.out::println);
        }
    }
}
