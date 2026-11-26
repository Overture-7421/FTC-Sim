package dalvik.system;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathClassLoaderTest {
    @TempDir
    Path folder;

    @Test
    void findsClassesListedInTheDexArchiveEvenWhenParentFiltersThemOut() throws Exception {
        String targetName = PathClassLoaderTest.class.getName();
        Path apk = apkWithSingleClass(targetName);
        ClassLoader filteringParent = new ClassLoader(PathClassLoader.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (targetName.equals(name)) {
                    throw new ClassNotFoundException("excluded: " + name);
                }
                return super.loadClass(name, resolve);
            }
        };

        PathClassLoader loader = new PathClassLoader(apk.toString(), "", filteringParent);

        assertSame(PathClassLoaderTest.class, Class.forName(targetName, false, loader));
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.example.NotInDex"));
    }

    @Test
    void missingDexPathDefinesNoClasses() {
        PathClassLoader loader = new PathClassLoader(folder.resolve("missing.jar").toString(), ClassLoader.getPlatformClassLoader());

        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.example.Missing"));
    }

    private Path apkWithSingleClass(String className) throws IOException {
        Path apk = Files.createTempFile(folder, "app", ".apk");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(apk))) {
            out.putNextEntry(new ZipEntry("classes.dex"));
            out.write(minimalDex(className));
            out.closeEntry();
        }
        return apk;
    }

    /** Minimal DEX header + single class_def matching {@link DexFile}'s reader layout. */
    private static byte[] minimalDex(String className) {
        byte[] descriptor = ("L" + className.replace('.', '/') + ";").getBytes(UTF_8);
        int headerSize = 112;
        int stringIdsOffset = headerSize;
        int typeIdsOffset = stringIdsOffset + 4;
        int classDefsOffset = typeIdsOffset + 4;
        int stringDataOffset = classDefsOffset + 32;
        ByteBuffer dex = ByteBuffer.allocate(stringDataOffset + 2 + descriptor.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        dex.put("dex\n035\0".getBytes(UTF_8));
        dex.putInt(60, stringIdsOffset);
        dex.putInt(64, 1);
        dex.putInt(68, typeIdsOffset);
        dex.putInt(96, 1);
        dex.putInt(100, classDefsOffset);
        dex.putInt(stringIdsOffset, stringDataOffset);
        dex.putInt(typeIdsOffset, 0);
        dex.putInt(classDefsOffset, 0);
        dex.position(stringDataOffset);
        dex.put((byte) descriptor.length);
        dex.put(descriptor);
        dex.put((byte) 0);
        return dex.array();
    }
}
