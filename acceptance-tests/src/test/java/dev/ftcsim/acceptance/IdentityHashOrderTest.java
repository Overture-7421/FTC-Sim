package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Objects that do not override {@code hashCode} are hashed by identity, and a hash set of them is iterated in the order of
 * those hashes. HotSpot makes them with a generator that each thread owns and that starts from a number which depends on
 * how many threads the JVM made before, so the order is a function of the JVM's build, its flags and the sequence of
 * threads it started. Robot programs depend on it without knowing: SolversLib's command scheduler keeps its commands in
 * such sets. This is the assumption the simulator's claim of repeatable runs rests on, tested: the same program in two JVM
 * processes with the same flags is iterated in the same order. That another JIT setting gives another order is the
 * reason the claim is made for one JVM build and set of flags, and is reported here and not asserted.
 */
@Timeout(300)
class IdentityHashOrderTest {
    private static String order(List<String> jvmFlags) throws IOException, InterruptedException {
        Path output = Files.createTempFile("ftcsim-identity-hash", ".txt");
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(jvmFlags);
            command.add("-cp");
            command.add(System.getProperty("ftcsim.childClasspath"));
            command.add(IdentityHashProbe.class.getName());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError("the probe did not finish");
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            return text.lines()
                    .filter(line -> line.startsWith(IdentityHashProbe.RESULT))
                    .map(line -> line.substring(IdentityHashProbe.RESULT.length()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the probe printed no order (" + process.exitValue() + "):\n" + text));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void theSameProgramInTwoJvmsWithTheSameFlagsIteratesAnIdentityHashedSetInTheSameOrder() throws Exception {
        for (List<String> flags : List.of(List.<String>of(), List.of("-Xint"))) {
            String first = order(flags);
            assertEquals(first, order(flags), "flags " + flags);
            System.out.println("IDENTITY-HASH> flags " + flags + " iterate as " + first);
        }
    }
}
