package dev.ftcsim.game;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.ftcsim.game.decode.DecodeField;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The module holds the field framework and one package per game, which stay apart: a game knows the framework and
 * nothing of the other game, and the framework knows no game. Nothing else in the build can say so, because they share
 * one module.
 */
class GamePackagesTest {
    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            "dev/ftcsim/field", List.of("dev/ftcsim/game/"),
            "dev/ftcsim/game/decode", List.of("dev/ftcsim/game/biobuzz/"),
            "dev/ftcsim/game/biobuzz", List.of("dev/ftcsim/game/decode/"));

    @Test
    void theFieldFrameworkAndEachGameReferenceNoneOfTheOthers() throws Exception {
        Path classes = Path.of(DecodeField.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        FORBIDDEN.forEach((owner, forbidden) -> {
            List<Path> files = classFiles(classes.resolve(owner));
            assertFalse(files.isEmpty(), "no classes found for " + owner);
            for (Path file : files) {
                String text = read(file);
                for (String other : forbidden) {
                    assertFalse(text.contains(other), file.getFileName() + " of " + owner + " refers to " + other);
                }
            }
        });
    }

    private static List<Path> classFiles(Path folder) {
        try (Stream<Path> all = Files.walk(folder)) {
            return all.filter(path -> path.toString().endsWith(".class")).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
