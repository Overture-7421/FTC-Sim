package dev.ftcsim.hardware.camera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What a camera was asked to do that it does not do, counted by kind, with when it was first asked. */
final class UsageLog {

    private record Entry(long firstNanos, int count) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    void note(String what, long nowNanos) {
        entries.merge(what, new Entry(nowNanos, 1), (first, again) -> new Entry(first.firstNanos(), first.count() + 1));
    }

    /** One line per kind of use, each starting with {@code prefix}. */
    List<String> lines(String prefix) {
        List<String> lines = new ArrayList<>();
        entries.forEach((what, entry) -> lines.add(prefix + what + " ("
                + (entry.count() == 1 ? "once" : entry.count() + " times")
                + ", first at " + String.format(Locale.ROOT, "%.3f", entry.firstNanos() / 1e9) + " s)"));
        return lines;
    }
}
