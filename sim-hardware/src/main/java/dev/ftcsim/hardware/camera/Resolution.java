package dev.ftcsim.hardware.camera;

/**
 * The capture resolutions of an AprilTag pipeline of the Limelight 3A, with the frame rate Limelight documents for
 * each (2x detector downscale) and the latencies the model gives it. The field of view is the same in both.
 */
enum Resolution {
    /** 640 x 480: 50 frames per second (Limelight 3A quick-start). Latencies are estimates. */
    VGA("640x480", 640, 480, 50, 12.0, 6.0, 1.0),
    /**
     * 1280 x 960: 20 frames per second (Limelight 3A quick-start). The capture latency is the one in the JSON
     * examples of Limelight's results specification (37.62 ms), which do not say which resolution they were taken
     * at; the targeting latency gives the specification's one-tag AprilTag example (19.78 ms) for one tag, and each
     * further tag is an estimate.
     */
    SXGA("1280x960", 1280, 960, 20, 37.6, 18.8, 1.0);

    static final double HORIZONTAL_FOV_DEGREES = 54.5;
    static final double VERTICAL_FOV_DEGREES = 42.0;

    private final String label;
    private final int width;
    private final int height;
    private final int framesPerSecond;
    private final double captureLatencyMillis;
    private final double baseTargetingMillis;
    private final double perTagMillis;

    Resolution(
            String label, int width, int height, int framesPerSecond, double captureLatencyMillis, double baseTargetingMillis,
            double perTagMillis) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.framesPerSecond = framesPerSecond;
        this.captureLatencyMillis = captureLatencyMillis;
        this.baseTargetingMillis = baseTargetingMillis;
        this.perTagMillis = perTagMillis;
    }

    String label() {
        return label;
    }

    Pinhole camera() {
        return Pinhole.fromFieldOfView(width, height, HORIZONTAL_FOV_DEGREES, VERTICAL_FOV_DEGREES);
    }

    long framePeriodNanos() {
        return 1_000_000_000L / framesPerSecond;
    }

    double framesPerSecond() {
        return framesPerSecond;
    }

    /** Capture latency {@code cl}: from the middle of the exposure to the start of the pipeline, in milliseconds. */
    double captureLatencyMillis() {
        return captureLatencyMillis;
    }

    /** Targeting latency {@code tl}: what the pipeline itself takes, in milliseconds. */
    double targetingMillis(int tagsDetected) {
        return baseTargetingMillis + perTagMillis * tagsDetected;
    }

    static Resolution of(String label) {
        for (Resolution resolution : values()) {
            if (resolution.label.equals(label)) {
                return resolution;
            }
        }
        return null;
    }

    static String labels() {
        StringBuilder labels = new StringBuilder();
        for (Resolution resolution : values()) {
            labels.append(labels.length() == 0 ? "" : ", ").append(resolution.label);
        }
        return labels.toString();
    }
}
