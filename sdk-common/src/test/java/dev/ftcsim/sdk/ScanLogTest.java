package dev.ftcsim.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScanLogTest {
    private static final String TEAM = "org.team.Intake";
    private static final String LIBRARY = "com.lib.Fragment";

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final Log.Sink out = (priority, tag, message) -> lines.add(priority + "/" + tag + ": " + message);

    /** What the SDK's class manager logs for a class that cannot be linked: a warning, then the failure and its stack. */
    private static void failToLink(Log.Sink sink, String className, String failure, int frames) {
        sink.log(Log.WARN, "ClassManager", className + " " + failure);
        sink.log(Log.ERROR, "ClassManager", failure);
        for (int frame = 0; frame < frames; frame++) {
            sink.log(Log.ERROR, "ClassManager", "\tat some.Frame.method" + frame + "(Frame.java:" + frame + ")");
        }
    }

    @Test
    void theClassesOfLibrariesThatCannotBeLoadedAreOneLineWithTheReasonsAndTheCounts() {
        ScanLog log = new ScanLog(out, Set.of(TEAM));
        log.beginScan();
        for (int i = 0; i < 30; i++) {
            failToLink(log, LIBRARY + i, "java.lang.NoClassDefFoundError: android/preference/PreferenceFragment", 100);
        }
        for (int i = 0; i < 10; i++) {
            failToLink(log, "com.lib.Other" + i, "java.lang.NoClassDefFoundError: android/app/Fragment", 100);
        }
        log.endScan();

        assertEquals(1, lines.size(), lines.toString());
        String summary = lines.get(0);
        assertTrue(summary.startsWith("5/ClassManager: 40 classes of the SDK and its libraries could not be loaded here"), summary);
        assertTrue(summary.contains("missing android.preference.PreferenceFragment for 30"), summary);
        assertTrue(summary.contains("missing android.app.Fragment for 10"), summary);
        assertTrue(summary.indexOf("PreferenceFragment") < summary.indexOf("android.app.Fragment"), "the commonest reason comes first: " + summary);
    }

    @Test
    void aClassOfTheTeamsOwnCodeIsReportedInFullAndNotCounted() {
        ScanLog log = new ScanLog(out, Set.of(TEAM));
        log.beginScan();
        failToLink(log, TEAM, "java.lang.NoClassDefFoundError: com/vendor/Missing", 2);
        log.endScan();

        assertEquals(
                List.of(
                        "5/ClassManager: org.team.Intake java.lang.NoClassDefFoundError: com/vendor/Missing",
                        "6/ClassManager: java.lang.NoClassDefFoundError: com/vendor/Missing",
                        "6/ClassManager: \tat some.Frame.method0(Frame.java:0)",
                        "6/ClassManager: \tat some.Frame.method1(Frame.java:1)"),
                lines);
    }

    @Test
    void theStackOfALibraryClassEndsWithTheNextClassWhetherItIsTheTeamsOrNot() {
        ScanLog log = new ScanLog(out, Set.of(TEAM));
        log.beginScan();
        failToLink(log, LIBRARY, "java.lang.NoClassDefFoundError: a/B", 3);
        failToLink(log, TEAM, "java.lang.NoClassDefFoundError: c/D", 1);
        failToLink(log, LIBRARY, "java.lang.NoClassDefFoundError: a/B", 3);
        log.endScan();

        assertEquals(4, lines.size(), lines.toString());
        assertEquals(
                List.of(
                        "5/ClassManager: org.team.Intake java.lang.NoClassDefFoundError: c/D",
                        "6/ClassManager: java.lang.NoClassDefFoundError: c/D",
                        "6/ClassManager: \tat some.Frame.method0(Frame.java:0)"),
                lines.subList(0, 3));
        assertTrue(lines.get(3).contains("2 classes of the SDK and its libraries"), lines.get(3));
    }

    @Test
    void everythingThatIsNotAClassManagerReportOfTheScanningThreadPassesUntouched() throws InterruptedException {
        ScanLog log = new ScanLog(out, Set.of(TEAM));
        log.beginScan();
        log.log(Log.INFO, "OpmodeRegistration", "registered {Teleop}");
        failToLink(log, LIBRARY, "java.lang.NoClassDefFoundError: a/B", 5);
        log.log(Log.ERROR, "Other", "an error that is somebody else's");
        Thread other = new Thread(() -> failToLink(log, LIBRARY, "java.lang.NoClassDefFoundError: a/B", 1));
        other.start();
        other.join();
        log.endScan();
        log.log(Log.WARN, "ClassManager", LIBRARY + " java.lang.NoClassDefFoundError: after the scan");

        assertEquals(7, lines.size(), lines.toString());
        assertEquals("4/OpmodeRegistration: registered {Teleop}", lines.get(0));
        assertEquals("6/Other: an error that is somebody else's", lines.get(1));
        assertTrue(lines.get(2).startsWith("5/ClassManager: " + LIBRARY), "another thread's report is not summarised: " + lines.get(2));
        assertTrue(lines.get(5).contains("1 class of the SDK and its libraries"), lines.get(5));
        assertTrue(lines.get(6).contains("after the scan"), "nothing is held back once the scan is over: " + lines);
    }

    @Test
    void withoutAListOfTheTeamsClassesEveryClassIsReportedInFull() {
        ScanLog log = new ScanLog(out, null);
        log.beginScan();
        failToLink(log, LIBRARY, "java.lang.NoClassDefFoundError: a/B", 4);
        log.endScan();

        assertEquals(6, lines.size(), lines.toString());
    }

    @Test
    void aFailureThatIsNotAMissingClassIsSaidInItsOwnWordsAndALongOneIsCut() {
        ScanLog log = new ScanLog(out, Set.of(TEAM));
        log.beginScan();
        failToLink(log, LIBRARY, "java.lang.VerifyError: " + "x".repeat(300), 1);
        log.endScan();

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("java.lang.VerifyError: " + "x".repeat(50)), lines.get(0));
        assertTrue(lines.get(0).contains("x..."), "the long reason is cut: " + lines.get(0));
        assertTrue(!lines.get(0).contains("x".repeat(150)), "the long reason is cut: " + lines.get(0));
        assertTrue(lines.get(0).length() < 400, lines.get(0));
    }
}
