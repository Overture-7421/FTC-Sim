package dev.ftcsim.app.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A version of FTC-Sim as a release is tagged: {@code 1.4.2}, optionally with a leading {@code v} and a pre-release part
 * ({@code 1.5.0-rc.1}). A release with a pre-release part is older than the same numbers without it.
 *
 * @param major      the first number
 * @param minor      the second number, 0 when the text has only one
 * @param patch      the third number, 0 when the text has fewer
 * @param preRelease what follows the first hyphen, empty for a final release
 */
public record Version(int major, int minor, int patch, String preRelease) implements Comparable<Version> {
    private static final Pattern FORM = Pattern.compile("v?(\\d{1,6})(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?(?:-([0-9A-Za-z.-]+))?");

    /** The version a build has when it was not made for a release: it never looks for updates. */
    public static final String DEVELOPMENT = "0.0.0-dev";

    /**
     * @throws IllegalArgumentException if the text is not a version
     */
    public static Version parse(String text) {
        Matcher match = FORM.matcher(text.strip());
        if (!match.matches()) {
            throw new IllegalArgumentException("not a version: '" + text + "'");
        }
        return new Version(
                Integer.parseInt(match.group(1)),
                match.group(2) == null ? 0 : Integer.parseInt(match.group(2)),
                match.group(3) == null ? 0 : Integer.parseInt(match.group(3)),
                match.group(4) == null ? "" : match.group(4));
    }

    @Override
    public int compareTo(Version other) {
        int byNumbers = Integer.compare(major, other.major);
        if (byNumbers == 0) {
            byNumbers = Integer.compare(minor, other.minor);
        }
        if (byNumbers == 0) {
            byNumbers = Integer.compare(patch, other.patch);
        }
        if (byNumbers != 0) {
            return byNumbers;
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return preRelease.isEmpty() ? (other.preRelease.isEmpty() ? 0 : 1) : -1;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    /** The identifiers between dots, numbers as numbers and the rest as text; fewer identifiers are older. */
    private static int comparePreRelease(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean numbers = a[i].matches("\\d{1,9}") && b[i].matches("\\d{1,9}");
            int order = numbers ? Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i])) : a[i].compareTo(b[i]);
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
