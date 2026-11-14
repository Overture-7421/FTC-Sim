package dalvik.system;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading real class lists is covered where they are written, by {@code ApplicationPackageTest} in sdk-common. */
class DexFileTest {
    @TempDir
    Path folder;

    @Test
    void aMissingCodeFileIsAnError() {
        assertThrows(IOException.class, () -> new DexFile(folder.resolve("absent.apk").toString()));
    }

    @Test
    void anApplicationPackageWithoutDexFilesDefinesNoClasses() throws IOException {
        Path apk = applicationPackage("AndroidManifest.xml", "<manifest/>");

        assertEquals(List.of(), Collections.list(new DexFile(apk.toString()).entries()));
    }

    @Test
    void aClassesDexThatIsNoDexFileIsAnError() throws IOException {
        Path apk = applicationPackage("classes.dex", "this is not a dex file, however long it is made to be, it has no header at all");

        assertThrows(IOException.class, () -> new DexFile(apk.toString()));
    }

    private Path applicationPackage(String entry, String content) throws IOException {
        Path apk = Files.createTempFile(folder, "app", ".apk");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(apk))) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(content.getBytes(UTF_8));
            out.closeEntry();
        }
        return apk;
    }
}
