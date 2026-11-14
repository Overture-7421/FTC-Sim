package dev.ftcsim.importer.manifest;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The paths a package may contain. A path is relative to the package folder, uses '/' and is safe to create
 * on Windows as written: no climbing out with {@code ..}, no drive letters or streams ({@code :}), no characters
 * Windows forbids in names, no device names such as {@code NUL}, and no name that ends in a dot or a space, which
 * Windows silently drops.
 */
public final class PackagePaths {
    private static final Pattern FORBIDDEN = Pattern.compile("[<>:\"|?*\\\\\\p{Cntrl}]");
    private static final Set<String> DEVICES = devices();

    private PackagePaths() {}

    /** @return why the path is not acceptable, or null when it is */
    public static String problemWith(String path) {
        if (path.isEmpty()) {
            return "is empty";
        }
        if (path.startsWith("/")) {
            return "is absolute";
        }
        if (path.endsWith("/")) {
            return "ends with '/'";
        }
        if (FORBIDDEN.matcher(path).find()) {
            return "has a character that Windows does not allow in names, or a backslash";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                return "has an empty segment";
            }
            if (segment.equals(".") || segment.equals("..")) {
                return "has a '" + segment + "' segment";
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                return "has a segment that ends in a dot or a space";
            }
            String stem = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
            if (DEVICES.contains(stem.toUpperCase(Locale.ROOT))) {
                return "has a segment that is a Windows device name";
            }
        }
        return null;
    }

    private static Set<String> devices() {
        Set<String> names = new HashSet<>(Set.of("CON", "PRN", "AUX", "NUL"));
        for (int i = 1; i <= 9; i++) {
            names.add("COM" + i);
            names.add("LPT" + i);
        }
        return Set.copyOf(names);
    }
}
