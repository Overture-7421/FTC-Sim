package dev.ftcsim.sdk;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The application package of the simulated robot controller. On a device that is the APK, a zip file whose
 * {@code classes.dex} holds every class of the app, and code that wants to know what the app contains opens it
 * and reads that list; the SDK does so to find OpModes and hooks, and Panels to find its plugins. The simulator
 * builds the same file from the directories and jars of the robot program, with a class list in place of the
 * code, because the classes are loaded from the program itself.
 */
final class ApplicationPackage {
    private static final String CLASS_SUFFIX = ".class";

    private ApplicationPackage() {}

    /** Creates the package in a temporary file that is deleted when the JVM exits. */
    static Path create(List<Path> program) throws IOException {
        Path apk = Files.createTempFile("ftcsim-app-", ".apk");
        apk.toFile().deleteOnExit();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(DexIndex.of(classNames(program)));
            zip.closeEntry();
        }
        return apk;
    }

    /** The names of the classes in the directories and jars, in the form {@code Class.forName} takes. */
    static Set<String> classNames(List<Path> entries) throws IOException {
        Set<String> classNames = new TreeSet<>();
        for (Path entry : entries) {
            addClassesOf(entry, classNames);
        }
        return classNames;
    }

    private static void addClassesOf(Path entry, Set<String> classNames) throws IOException {
        if (Files.isDirectory(entry)) {
            try (Stream<Path> files = Files.walk(entry)) {
                files.filter(Files::isRegularFile)
                        .map(file -> entry.relativize(file).toString().replace(File.separatorChar, '/'))
                        .forEach(resource -> addIfClass(resource, classNames));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        } else {
            try (ZipFile jar = new ZipFile(entry.toFile())) {
                jar.stream().map(ZipEntry::getName).forEach(resource -> addIfClass(resource, classNames));
            }
        }
    }

    /** Class files under META-INF belong to other Java versions or tools, and the module and package descriptors are no classes. */
    private static void addIfClass(String resource, Set<String> classNames) {
        boolean loadable = resource.endsWith(CLASS_SUFFIX)
                && !resource.startsWith("META-INF/")
                && !resource.endsWith("module-info.class")
                && !resource.endsWith("package-info.class");
        if (loadable) {
            classNames.add(resource.substring(0, resource.length() - CLASS_SUFFIX.length()).replace('/', '.'));
        }
    }
}
