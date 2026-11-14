package android.os;

/**
 * Build identity of the simulated robot controller: a REV Control Hub, the most common FTC controller,
 * running the Android release those units ship with.
 */
public final class Build {
    public static final String UNKNOWN = "unknown";
    public static final String MANUFACTURER = identity("REV Robotics");
    public static final String MODEL = identity("Control Hub v1.0");
    public static final String BRAND = identity("REV");
    public static final String DEVICE = identity("controlhub");

    /** Deprecated on real Android but still what the SDK reads before API 26. */
    public static final String SERIAL = identity("FTCSIM000000");

    private Build() {}

    public static String getSerial() {
        return SERIAL;
    }

    // Constructed at run time so that no consumer inlines a value, as with the real framework.
    private static String identity(String value) {
        return new String(value);
    }

    public static final class VERSION {
        public static final int SDK_INT = Integer.parseInt("25");
        public static final String RELEASE = identity("7.1.1");

        private VERSION() {}
    }

    public static final class VERSION_CODES {
        public static final int N = 24;
        public static final int N_MR1 = 25;
        public static final int O = 26;
        public static final int O_MR1 = 27;
        public static final int P = 28;

        private VERSION_CODES() {}
    }
}
