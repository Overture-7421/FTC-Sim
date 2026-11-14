package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.app.JdkDiscovery.Jdk;
import dev.ftcsim.app.JdkDiscovery.Search;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkDiscoveryTest {

    @TempDir
    Path root;

    private Search search(FakeMachine pc) throws IOException {
        return new JdkDiscovery(pc.machine(), JdkPatterns.bundled()).search();
    }

    @Test
    void java21IsPreferredThenJava17ThenTheOthersNewestFirstWhateverTheOrderTheyWereFoundIn() throws IOException {
        FakeMachine pc = new FakeMachine(root);
        FakeMachine.jdk(pc.programFiles("Eclipse Adoptium", "jdk-17.0.9.9-hotspot"), "17.0.9");
        FakeMachine.jdk(pc.programFiles("Java", "jdk-19"), "19.0.2");
        FakeMachine.jdk(pc.programFiles("Java", "jdk-18"), "18.0.2");
        FakeMachine.jdk(pc.programFiles("Java", "jdk-25"), "25.0.1");
        Path android = FakeMachine.jdk(pc.programFiles("Android", "Android Studio", "jbr"), "21.0.9", "x86_64", "JetBrains s.r.o.");

        Search search = search(pc);
        assertEquals(android, search.best().orElseThrow().home());
        assertEquals(21, search.best().orElseThrow().major());
        assertEquals("JetBrains s.r.o.", search.best().orElseThrow().vendor());

        Files.delete(android.resolve("release"));
        assertEquals(17, search(pc).best().orElseThrow().major(), "without the 21, the 17");

        Files.delete(pc.programFiles("Eclipse Adoptium", "jdk-17.0.9.9-hotspot", "release"));
        assertEquals(19, search(pc).best().orElseThrow().major(), "then the newest of the rest");
        assertEquals(List.of(19, 18), search(pc).usable().stream().map(Jdk::major).sorted(Comparator.reverseOrder()).toList());
    }

    @Test
    void everyLocationOfTheLaunchersManifestIsSearched() throws IOException {
        FakeMachine pc = new FakeMachine(root);
        List<Path> homes = List.of(
                pc.programFiles("Eclipse Adoptium", "jdk-21.0.5.11-hotspot"),
                pc.programFiles("Microsoft", "jdk-17.0.13.11-hotspot"),
                pc.programFiles("Amazon Corretto", "jdk21.0.5_11"),
                pc.programFiles("Android", "Android Studio Meerkat", "jbr"),
                pc.localAppData("Programs", "Eclipse Adoptium", "jdk-21.0.5.11-hotspot"),
                pc.localAppData("Programs", "Android Studio", "jbr"),
                pc.localAppData("JetBrains", "Toolbox", "apps", "AndroidStudio", "ch-0", "243.1", "jbr"),
                pc.userProfile(".jdks", "temurin-21"),
                pc.userProfile(".gradle", "jdks", "eclipse_adoptium-21-amd64-windows"));
        for (Path home : homes) {
            FakeMachine.jdk(home, "21.0.5");
        }

        assertEquals(
                homes.stream().map(home -> home.toAbsolutePath().normalize()).sorted().toList(),
                search(pc).usable().stream().map(Jdk::home).sorted().toList());
    }

    @Test
    void javaHomeCountsAndIsTriedFirstAndAJdkReachedTwiceWaysIsListedOnce() throws IOException {
        FakeMachine pc = new FakeMachine(root);
        Path elsewhere = FakeMachine.jdk(root.resolve("tools").resolve("jdk"), "21.0.1");
        FakeMachine.jdk(pc.programFiles("Java", "jdk-21"), "21.0.9");
        pc.variables.put("JAVA_HOME", "\"" + elsewhere + "\"");

        Search search = search(pc);
        assertEquals(elsewhere, search.best().orElseThrow().home(), "JAVA_HOME wins a tie");
        assertEquals(2, search.usable().size());

        pc.variables.put("JAVA_HOME", pc.programFiles("Java", "jdk-21").toString());
        assertEquals(1, search(pc).usable().size(), "the same folder again is the same JDK");
    }

    @Test
    void whatLooksLikeAJdkAndCannotBeUsedIsSaidWithTheReason() throws IOException {
        FakeMachine pc = new FakeMachine(root);
        FakeMachine.jdk(pc.programFiles("Java", "jdk-25"), "25.0.1");
        FakeMachine.jdk(pc.programFiles("Java", "jdk-8"), "1.8.0_392");
        FakeMachine.jdk(pc.programFiles("Java", "jdk-21-arm"), "21.0.5", "aarch64", "Microsoft");
        FakeMachine.jdk(pc.programFiles("Java", "mystery"), null);
        Path jre = pc.programFiles("Java", "jre-17");
        FakeMachine.jdk(jre, "17.0.9");
        Files.delete(jre.resolve("bin").resolve("javac.exe"));
        Files.createDirectories(pc.programFiles("Java", "not-java", "docs"));
        pc.variables.put("JAVA_HOME", root.resolve("gone").toString());

        Search search = search(pc);

        assertTrue(search.best().isEmpty());
        String reasons = String.join("\n", search.rejected());
        assertTrue(reasons.contains("jdk-25") && reasons.contains("it is Java 25, but a project's own build needs Java 17 to 21"), reasons);
        assertTrue(reasons.contains("jdk-8") && reasons.contains("it is Java 8"), reasons);
        assertTrue(reasons.contains("jdk-21-arm") && reasons.contains("it is a aarch64 JDK; only 64-bit x64"), reasons);
        assertTrue(reasons.contains("mystery") && reasons.contains("no release file"), reasons);
        assertTrue(reasons.contains("jre-17") && reasons.contains("without the compiler"), reasons);
        assertTrue(reasons.contains("not-java") && reasons.contains("no bin\\java.exe"), reasons);
        assertTrue(reasons.contains("gone") && reasons.contains("not a folder"), reasons);
    }

    @Test
    void aComputerWithoutTheVariablesOfTheLocationsHasNoneOfThemAndNothingFails() throws IOException {
        FakeMachine pc = new FakeMachine(root);
        FakeMachine.jdk(pc.programFiles("Java", "jdk-21"), "21.0.9");
        pc.variables.clear();

        Search search = search(pc);

        assertTrue(search.usable().isEmpty() && search.rejected().isEmpty(), search.toString());
    }
}
