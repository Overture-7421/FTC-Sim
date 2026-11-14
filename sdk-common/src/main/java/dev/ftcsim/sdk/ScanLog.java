package dev.ftcsim.sdk;

import android.util.Log;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stands between the SDK's log and the host's while the SDK scans the robot program for OpModes. The scan loads every
 * class of the program, and the SDK's class manager reports each one that cannot be linked with a warning and the whole
 * stack of the failure, about a hundred lines. On a desktop JVM that is dozens of classes of the SDK and of libraries,
 * those that need parts of Android's user interface which the simulator does not have, and nothing in them is the
 * team's concern; their thousands of lines would hide the few that are. Those classes are reported by one line that
 * says how many there were and why, and the classes of the team's own code are reported in full, as they are the ones
 * whose failure a team can act on.
 *
 * <p>Which classes are the team's is a matter of where they come from, never of what they are called.
 */
final class ScanLog implements Log.Sink {
    private static final String TAG = "ClassManager";
    private static final int REASONS_SHOWN = 5;
    private static final int REASON_LENGTH = 120;

    private final Log.Sink next;
    /** The names of the classes of the team's own code, or null when every class is to be reported in full. */
    private final Set<String> teamClasses;

    private Thread scanning;
    private boolean swallowing;
    private int skipped;
    private final Map<String, Integer> reasons = new LinkedHashMap<>();

    ScanLog(Log.Sink next, Set<String> teamClasses) {
        this.next = next;
        this.teamClasses = teamClasses;
    }

    /** From now on the calling thread's class manager reports are summarised, until {@link #endScan}. */
    synchronized void beginScan() {
        scanning = Thread.currentThread();
        swallowing = false;
        skipped = 0;
        reasons.clear();
    }

    /** Ends the summary of the scan, which is one line if the scan left classes out. */
    void endScan() {
        String summary;
        synchronized (this) {
            scanning = null;
            swallowing = false;
            summary = summary();
        }
        if (summary != null) {
            next.log(Log.WARN, TAG, summary);
        }
    }

    @Override
    public void log(int priority, String tag, String message) {
        if (!consumes(priority, tag, message)) {
            next.log(priority, tag, message);
        }
    }

    private synchronized boolean consumes(int priority, String tag, String message) {
        if (teamClasses == null || scanning != Thread.currentThread() || !TAG.equals(tag)) {
            swallowing = false;
            return false;
        }
        if (priority == Log.WARN) {
            int space = message.indexOf(' ');
            if (space > 0 && !teamClasses.contains(message.substring(0, space))) {
                skipped++;
                reasons.merge(reasonOf(message.substring(space + 1)), 1, Integer::sum);
                swallowing = true;
                return true;
            }
            swallowing = false;
            return false;
        }
        // What follows a skipped class's warning is that failure's stack, as error lines, until the next class.
        return swallowing && priority == Log.ERROR;
    }

    /** The throwable that kept the class from linking, as {@code NoClassDefFoundError: x/Y} becomes "missing x/Y". */
    private static String reasonOf(String failure) {
        String reason = failure.trim();
        for (String prefix : List.of("java.lang.NoClassDefFoundError: ", "java.lang.ClassNotFoundException: ")) {
            if (reason.startsWith(prefix)) {
                return "missing " + reason.substring(prefix.length()).replace('/', '.');
            }
        }
        return reason.length() > REASON_LENGTH ? reason.substring(0, REASON_LENGTH) + "..." : reason;
    }

    private String summary() {
        if (skipped == 0) {
            return null;
        }
        List<Map.Entry<String, Integer>> byCount = new ArrayList<>(reasons.entrySet());
        byCount.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        StringBuilder line = new StringBuilder();
        line.append(skipped).append(skipped == 1 ? " class" : " classes").append(" of the SDK and its libraries could not be loaded here, ")
                .append("mostly parts of Android's user interface that the simulator does not have, and were left out of the scan (");
        for (int i = 0; i < Math.min(REASONS_SHOWN, byCount.size()); i++) {
            line.append(i == 0 ? "" : "; ").append(byCount.get(i).getKey()).append(" for ").append(byCount.get(i).getValue());
        }
        if (byCount.size() > REASONS_SHOWN) {
            line.append("; and ").append(byCount.size() - REASONS_SHOWN).append(" other reasons");
        }
        return line.append("); the classes of the robot program itself are reported in full").toString();
    }
}
