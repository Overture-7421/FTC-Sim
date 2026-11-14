package dev.ftcsim.importer.javares;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which Java resources (the non-class files of libraries) an APK keeps and how files of one name are combined, as the
 * Android Gradle plugin's {@code mergeJavaResource} task decides it (version 8.7.0; {@link #forAnApk()} gives its
 * defaults for an application). A path is tested as {@code "/" + entry name}
 * against glob patterns, in the order pick-first, merge, exclude; a pattern that starts with neither {@code /} nor
 * {@code *} gets a {@code /} in front, as the plugin does, and the globs are those of the platform's default file
 * system, as in the plugin. Files that end in {@code .class} or {@code .so} are never Java resources.
 */
public final class PackagingRules {

    /** What to do with the files of one name. */
    public enum Action {
        /** Leave the file out. */
        EXCLUDE,
        /** Concatenate the files of that name, in order; this is how {@code META-INF/services} files from several libraries are combined. */
        MERGE,
        /** The first file wins and the others are dropped without a word. */
        PICK_FIRST,
        /** No rule names the file: the first one wins, but the plugin would have stopped the build if the others differ. */
        KEEP
    }

    /** The plugin's default {@code excludes} (Android Gradle plugin 8.7.0, {@code PackagingOptionsUtilsKt.defaultExcludes}). */
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            "/META-INF/LICENSE",
            "/META-INF/LICENSE.txt",
            "/META-INF/MANIFEST.MF",
            "/META-INF/NOTICE",
            "/META-INF/NOTICE.txt",
            "/META-INF/*.DSA",
            "/META-INF/*.EC",
            "/META-INF/*.SF",
            "/META-INF/*.RSA",
            "/META-INF/maven/**",
            "/META-INF/proguard/*",
            "/META-INF/com.android.tools/**",
            "/NOTICE",
            "/NOTICE.txt",
            "/LICENSE.txt",
            "/LICENSE",
            "**/.svn/**",
            "**/CVS/**",
            "**/SCCS/**",
            "**/.*/**",
            "**/.*",
            "**/*~",
            "**/thumbs.db",
            "**/picasa.ini",
            "**/about.html",
            "**/package.html",
            "**/overview.html",
            "**/protobuf.meta",
            "**/_*",
            "**/_*/**",
            "**/*.kotlin_metadata");

    /** The plugin's default {@code merges} ({@code PackagingOptionsUtilsKt.defaultMerges}). */
    public static final List<String> DEFAULT_MERGES = List.of("/META-INF/services/**", "jacoco-agent.properties");

    /**
     * What an APK leaves out on top of {@link #DEFAULT_EXCLUDES}: Kotlin module files, which only the Kotlin compiler
     * reads ({@code ResourcesApkPackagingImpl}). The jars of a library keep them.
     */
    public static final String APK_EXCLUDE = "/META-INF/*.kotlin_module";

    private static final List<String> NEVER_RESOURCES = List.of(".class", ".so");

    private final List<PathMatcher> excludes;
    private final List<PathMatcher> pickFirsts;
    private final List<PathMatcher> merges;

    public PackagingRules(Collection<String> excludes, Collection<String> pickFirsts, Collection<String> merges) {
        this.excludes = compile(excludes);
        this.pickFirsts = compile(pickFirsts);
        this.merges = compile(merges);
    }

    /** The plugin's defaults for the APK of an application, which is what a project gets that does not change its packaging options. */
    public static PackagingRules forAnApk() {
        List<String> excludes = new ArrayList<>(DEFAULT_EXCLUDES);
        excludes.add(APK_EXCLUDE);
        return new PackagingRules(excludes, List.of(), DEFAULT_MERGES);
    }

    /** @param entryName the name of a jar entry or the path below a resources folder, with '/' separators and no leading '/' */
    public Action actionFor(String entryName) {
        for (String suffix : NEVER_RESOURCES) {
            if (entryName.endsWith(suffix)) {
                return Action.EXCLUDE;
            }
        }
        Path path;
        try {
            path = Path.of(("/" + entryName).replace('/', File.separatorChar));
        } catch (InvalidPathException notAPathHere) {
            return Action.KEEP;
        }
        if (matches(pickFirsts, path)) {
            return Action.PICK_FIRST;
        }
        if (matches(merges, path)) {
            return Action.MERGE;
        }
        return matches(excludes, path) ? Action.EXCLUDE : Action.KEEP;
    }

    private static boolean matches(List<PathMatcher> matchers, Path path) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> compile(Collection<String> patterns) {
        return patterns.stream()
                .map(pattern -> pattern.startsWith("/") || pattern.startsWith("*") ? pattern : "/" + pattern)
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }
}
