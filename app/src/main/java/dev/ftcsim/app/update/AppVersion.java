package dev.ftcsim.app.update;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Which version of FTC-Sim this is, as the build that made the jar wrote it into {@code version.properties}. */
public final class AppVersion {
    private static final String RESOURCE = "/dev/ftcsim/app/version.properties";

    private AppVersion() {}

    /** The version this build was made for; {@link Version#DEVELOPMENT} for a build that was not made for a release. */
    public static String current() {
        try (InputStream in = AppVersion.class.getResourceAsStream(RESOURCE)) {
            return in == null ? Version.DEVELOPMENT : read(in);
        } catch (IOException unreadable) {
            return Version.DEVELOPMENT;
        }
    }

    /** The version a {@code version.properties} says. */
    static String read(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(in);
        String version = properties.getProperty("version", Version.DEVELOPMENT).strip();
        return version.isEmpty() || version.contains("$") ? Version.DEVELOPMENT : version;
    }

    /** Whether this build was made for a release, and so keeps itself up to date: a development build never looks. */
    public static boolean isRelease() {
        String version = current();
        if (version.equals(Version.DEVELOPMENT)) {
            return false;
        }
        try {
            Version.parse(version);
            return true;
        } catch (IllegalArgumentException notAVersion) {
            return false;
        }
    }
}
