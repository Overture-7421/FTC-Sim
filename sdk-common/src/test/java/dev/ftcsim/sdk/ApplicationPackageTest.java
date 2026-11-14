package dev.ftcsim.sdk;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dalvik.system.DexFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationPackageTest {
    @TempDir
    Path folder;

    @Test
    void listsTheLoadableClassesOfDirectoriesAndJarsOnce() throws IOException {
        Path classes = Files.createDirectories(folder.resolve("classes/org/acme"));
        for (String file : List.of("Robot.class", "Robot$Helper.class", "package-info.class", "notes.txt")) {
            Files.createFile(classes.resolve(file));
        }
        Path library = jar("library.jar", "com/lib/Util.class", "org/acme/Robot.class", "META-INF/versions/9/com/lib/Util.class",
                "module-info.class", "readme.md");

        Path apk = ApplicationPackage.create(List.of(folder.resolve("classes"), library));

        assertEquals(List.of("com.lib.Util", "org.acme.Robot", "org.acme.Robot$Helper"), classesOf(apk));
    }

    @Test
    void namesThatNeedMultipleBytesOrALongLengthSurvive() throws IOException {
        String longName = "p".repeat(300) + ".Long";
        Path library = jar("names.jar", "org/acme/ロボット$Ä.class", longName.replace('.', '/') + ".class");

        assertEquals(List.of("org.acme.ロボット$Ä", longName).stream().sorted().toList(), classesOf(ApplicationPackage.create(List.of(library))));
    }

    @Test
    void aProgramWithoutClassesGivesAnEmptyButWellFormedPackage() throws IOException, NoSuchAlgorithmException {
        Path apk = ApplicationPackage.create(List.of());

        assertEquals(List.of(), classesOf(apk));
        assertWellFormed(dexOf(apk));
    }

    @Test
    void theDexFileCarriesItsSizeChecksumAndSignature() throws IOException, NoSuchAlgorithmException {
        assertWellFormed(dexOf(ApplicationPackage.create(List.of(jar("two.jar", "a/A.class", "b/B$C.class")))));
    }

    @Test
    void aMissingEntryOfTheProgramIsAnError() {
        assertThrows(IOException.class, () -> ApplicationPackage.create(List.of(folder.resolve("absent.jar"))));
    }

    private static void assertWellFormed(byte[] dex) throws NoSuchAlgorithmException {
        ByteBuffer header = ByteBuffer.wrap(dex).order(LITTLE_ENDIAN);
        assertEquals("dex\n035\0", new String(dex, 0, 8, ISO_8859_1));
        assertEquals(dex.length, header.getInt(32));
        assertEquals(0x70, header.getInt(36));
        assertEquals(0x12345678, header.getInt(40));
        Adler32 checksum = new Adler32();
        checksum.update(dex, 12, dex.length - 12);
        assertEquals((int) checksum.getValue(), header.getInt(8));
        byte[] signature = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(dex, 32, dex.length));
        assertArrayEquals(signature, Arrays.copyOfRange(dex, 12, 32));
    }

    private static List<String> classesOf(Path apk) throws IOException {
        return Collections.list(new DexFile(apk.toString()).entries()).stream().sorted().toList();
    }

    private static byte[] dexOf(Path apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            return zip.getInputStream(zip.getEntry("classes.dex")).readAllBytes();
        }
    }

    private Path jar(String name, String... entries) throws IOException {
        Path jar = folder.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.closeEntry();
            }
        }
        return jar;
    }
}
