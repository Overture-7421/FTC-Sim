package dev.ftcsim.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The folders FTC-Sim keeps for one Windows user, under {@code %LOCALAPPDATA%\FTC-Sim}, and the rules for naming what
 * goes in them. Nothing here touches the disk; whoever writes creates the folders.
 *
 * <pre>
 * natives\                          the physics engine's native library, unpacked by the worker once per version
 * logs\                             one log per worker launch, and the crash report of a worker that crashed
 * import\work\NAME-HASH\            the importer's copy of one project and what it builds there, kept between preparations
 * programs\NAME-FINGERPRINT\        a prepared program: the project's name and the first digits of the hash of its source
 * programs\NAME-FINGERPRINT.build.log   the output of the build that made it
 * import\work\NAME-HASH\programs.txt    the programs made from that project, oldest first, so that old ones can be removed
 * profiles\NAME-HASH.json               FTC-Sim's copy of a robot description that could not be written into its project
 * </pre>
 *
 * Windows limits a path to 260 characters and an Android build nests files deeply, so the names are short.
 */
public final class AppFolders {
    private static final int PROGRAM_NAME_LIMIT = 40;
    private static final int FINGERPRINT_DIGITS = 12;
    private static final int WORKSPACE_NAME_LIMIT = 12;
    private static final int WORKSPACE_HASH_DIGITS = 8;

    private final Path base;

    /** @param base the folder FTC-Sim keeps its files in */
    public AppFolders(Path base) {
        this.base = base.toAbsolutePath().normalize();
    }

    /** {@code %LOCALAPPDATA%\FTC-Sim} of the computer. */
    public static AppFolders forThisUser(Machine machine) {
        return new AppFolders(machine.localAppData().resolve("FTC-Sim"));
    }

    public Path base() {
        return base;
    }

    /** Where the worker unpacks the physics engine's native library; the worker's own default is the same folder. */
    public Path natives() {
        return base.resolve("natives");
    }

    /** Where the logs are: one per start of the application ({@link AppLog}) and per worker launch, and the crash reports of the Java runtime; each kind keeps only the newest. */
    public Path logs() {
        return base.resolve("logs");
    }

    /** Where prepared programs are made. */
    public Path programs() {
        return base.resolve("programs");
    }

    /**
     * The importer's workspace for a project: short, and the same every time for the same project folder, however its
     * path is written, so that the Gradle build it keeps there starts from where the last one ended.
     */
    public Path workspace(Path projectFolder) {
        String key = projectFolder.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        String name = safeName(leaf(projectFolder), WORKSPACE_NAME_LIMIT).toLowerCase(Locale.ROOT);
        return base.resolve("import").resolve("work").resolve(name + "-" + sha256(key).substring(0, WORKSPACE_HASH_DIGITS));
    }

    /** The folder of the program prepared from this source: the same source gives the same folder, changed source another. */
    public Path program(String projectName, String sourceFingerprint) {
        return programs().resolve(safeName(projectName, PROGRAM_NAME_LIMIT) + "-" + sourceFingerprint.substring(0, FINGERPRINT_DIGITS));
    }

    /** Where the build log of a prepared program is kept: beside the program, because a program's folder holds only what its manifest lists. */
    public Path buildLog(Path program) {
        return program.resolveSibling(program.getFileName() + ".build.log");
    }

    /**
     * FTC-Sim's own copy of a project's robot description, for a project whose folder cannot be written to. Named like the
     * project's workspace, so the same project always finds it.
     */
    public Path keptProfile(Path projectFolder) {
        return base.resolve("profiles").resolve(workspace(projectFolder).getFileName() + ".json");
    }

    /** The list of the program folders made from a project, oldest first, kept in its workspace to prune the old ones. */
    public Path programList(Path projectFolder) {
        return workspace(projectFolder).resolve("programs.txt");
    }

    /** Where a program that is being thrown away waits until Windows lets go of it. */
    public Path discarding(String id) {
        return programs().resolve(".discarding-" + id);
    }

    /** The name of a folder or file as a path, whatever it holds. */
    static String leaf(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /** A name that is safe in a Windows path, at most {@code limit} characters long. */
    static String safeName(String text, int limit) {
        String safe = text.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[.]+", "");
        if (safe.length() > limit) {
            safe = safe.substring(0, limit);
        }
        safe = safe.replaceAll("[.]+$", "");
        return safe.isEmpty() ? "project" : safe;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java runtime has SHA-256", impossible);
        }
    }
}
