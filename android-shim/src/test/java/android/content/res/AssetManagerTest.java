package android.content.res;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssetManagerTest {
    private final AssetManager assets = new AssetManager();

    @Test
    void anAssetIsReadFromBelowTheAssetsRootOfTheClassPath() throws IOException {
        try (InputStream stream = assets.open("sample/greeting.txt")) {
            assertEquals("hello from the assets", new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip());
        }
    }

    @Test
    void aMissingAssetIsAFileNotFoundException() {
        assertThrows(FileNotFoundException.class, () -> assets.open("sample/absent.txt"));
    }

    @Test
    void aDirectoryListsTheNamesBelowIt() throws IOException {
        assertArrayEquals(new String[] {"greeting.txt"}, assets.list("sample"));
        assertTrue(List.of(assets.list("")).contains("sample"));
    }

    @Test
    void aMissingDirectoryOrAFileListsNothing() throws IOException {
        assertArrayEquals(new String[0], assets.list("absent"));
        assertArrayEquals(new String[0], assets.list("sample/greeting.txt"));
    }
}
