package dalvik.system;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Base class loader for Android dex archives ({@link PathClassLoader}). On a device it opens the APK or dex jars in
 * {@code dexPath} and defines their classes directly. In FTC-Sim, the robot program's classes live on the robot world's
 * class loader (instrumented with the simulator's bytecode hooks) and the APK carries only a class-name index, so any
 * class listed in {@code dexPath}'s dex entries is loaded from the robot world's class loader.
 */
public class BaseDexClassLoader extends ClassLoader {
    private final Set<String> dexClasses;

    public BaseDexClassLoader(String dexPath, File optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(parent);
        this.dexClasses = readDexClasses(dexPath);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (dexClasses.contains(name)) {
            ClassLoader robotLoader = BaseDexClassLoader.class.getClassLoader();
            if (robotLoader != null) {
                return robotLoader.loadClass(name);
            }
        }
        throw new ClassNotFoundException(name);
    }

    private static Set<String> readDexClasses(String dexPath) {
        if (dexPath == null || dexPath.isEmpty()) {
            return Set.of();
        }
        Set<String> classes = new HashSet<>();
        for (String entry : dexPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isEmpty()) {
                continue;
            }
            File file = new File(entry);
            if (!file.isFile()) {
                continue;
            }
            try {
                DexFile dex = new DexFile(file.getPath());
                try {
                    Collections.list(dex.entries()).forEach(classes::add);
                } finally {
                    dex.close();
                }
            } catch (IOException ignored) {
                // Non-dex jars or unreadable entries simply contribute no dex classes.
            }
        }
        return Set.copyOf(classes);
    }
}
