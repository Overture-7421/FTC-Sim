package dev.ftcsim.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Guards the clean-machine story: the pinned natives link the C runtime statically, so loading them
 * needs no Visual C++ redistributable, only the Windows kernel.
 */
class NativeImportsTest {

    private static final String ENTRY = "windows/x86-64/com/github/stephengold/joltjni.dll";

    @Test
    void theReleaseNativeImportsOnlyKernel32() throws IOException {
        try (InputStream in = NativeImportsTest.class.getResourceAsStream("/" + ENTRY)) {
            assertEquals(List.of("KERNEL32.dll"), List.copyOf(PeImports.read(in.readAllBytes()).keySet()));
        }
    }

    @Test
    void theDebugNativeImportsOnlyKernel32() throws IOException {
        try (ZipFile jar = new ZipFile(System.getProperty("ftcsim.debugNativesJar"));
                InputStream in = jar.getInputStream(jar.getEntry(ENTRY))) {
            assertEquals(List.of("KERNEL32.dll"), List.copyOf(PeImports.read(in.readAllBytes()).keySet()));
        }
    }
}
