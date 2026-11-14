package dev.ftcsim.physics;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The committed golden hashes of the scenario, and the comparison against a run. */
final class GoldenHashes {

    private static final String RESOURCE = "golden-hashes.txt";

    private GoldenHashes() {}

    /** One {@code <step> <sha256>} line per hash point; lines starting with {@code #} are comments. */
    static List<String> expected() {
        try (InputStream in = GoldenHashes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the test resources");
            }
            List<String> hashes = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    hashes.add(line.strip());
                }
            }
            return hashes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The golden line for each hash a run produced, in the same format as {@link #expected()}. */
    static List<String> lines(List<String> hashes) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++) {
            lines.add((i + 1) * GoldenScenario.HASH_INTERVAL + " " + hashes.get(i));
        }
        return lines;
    }

    /** Null when the run matches the golden hashes, otherwise where it first departs from them. */
    static String firstDifference(List<String> actual) {
        List<String> expected = expected();
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "first differs at step " + expected.get(i).split(" ")[0] + ": golden " + expected.get(i)
                        + " but got " + actual.get(i);
            }
        }
        return expected.size() == actual.size()
                ? null
                : "expected " + expected.size() + " hashes but got " + actual.size();
    }
}
