package dev.ftcsim.hardware.camera;

import java.util.List;
import java.util.Locale;

/**
 * The text of a Limelight's replies: {@code /results}, as Limelight's JSON results specification lays it out for an
 * AprilTag pipeline, and {@code /status}. Keys are in alphabetical order, as in the specification's examples.
 */
final class LimelightJson {

    /** The camera's own clock reads this much at simulated time zero: it has been up for ten minutes. Estimate. */
    static final long BOOT_AGE_NANOS = 600_000_000_000L;
    /** What the camera's wall clock reads at simulated time zero, in microseconds since 1970 (2026-01-01). Estimate. */
    private static final long WALL_CLOCK_ORIGIN_MICROS = 1_767_225_600_000_000L;
    /** The hardware type id of a Limelight 3A in the JSON dump. Estimate: the specification's example shows 7. */
    private static final int HARDWARE_TYPE = 7;
    private static final String PIPELINE_TYPE_FIDUCIAL = "pipe_fiducial";
    private static final String PIPELINE_TYPE_DEFAULT = "pipe_color";
    private static final double[] ZERO_POSE = {0, 0, 0, 0, 0, 0};

    private LimelightJson() {}

    /** The results of a frame that has completed. */
    static String results(Frame frame, double[] cameraInRobot) {
        StringBuilder json = new StringBuilder(2048);
        List<Frame.Reading> tags = frame.tags();
        Frame.Reading primary = tags.isEmpty() ? null : tags.get(0);
        json.append("{\"Barcode\":[],\"Classifier\":[],\"Detector\":[],\"Fiducial\":[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            fiducial(json, tags.get(i), frame.sendCorners());
        }
        json.append("],\"PythonOut\":[],\"Retro\":[]");
        pose(json, "botpose", frame.botpose());
        number(json, "botpose_avgarea", frame.averageAreaPercent());
        number(json, "botpose_avgdist", frame.averageDistanceMeters());
        pose(json, "botpose_orb", frame.botposeOrb());
        pose(json, "botpose_orb_wpiblue", ZERO_POSE);
        pose(json, "botpose_orb_wpired", ZERO_POSE);
        number(json, "botpose_span", frame.spanMeters());
        integer(json, "botpose_tagcount", frame.tagCount());
        pose(json, "botpose_wpiblue", ZERO_POSE);
        pose(json, "botpose_wpired", ZERO_POSE);
        number(json, "cl", frame.captureLatencyMillis());
        integer(json, "fidx", frame.index());
        number(json, "focus_metric", 0);
        json.append(",\"hw\":{\"cid\":9281,\"cpu\":38.5,\"dfree\":12500,\"dtot\":28000,\"ram\":21.4,\"temp\":49.5}");
        integer(json, "hwtype", HARDWARE_TYPE);
        integer(json, "ignorent", 0);
        integer(json, "imgsrc", 0);
        json.append(",\"imu\":{\"data\":[0,0,0,0,0,0,0,0,0],\"quat\":[1,0,0,0],\"yaw\":0}");
        integer(json, "pID", frame.pipeline());
        json.append(",\"pTYPE\":\"").append(frame.fiducial() ? PIPELINE_TYPE_FIDUCIAL : PIPELINE_TYPE_DEFAULT).append('"');
        pose(json, "stdev_mt1", frame.stdevMt1());
        pose(json, "stdev_mt2", frame.stdevMt2());
        pose(json, "t6c_rs", cameraInRobot);
        number(json, "ta", primary == null ? 0 : 100.0 * primary.area());
        number(json, "tl", frame.targetingLatencyMillis());
        long cameraNanos = BOOT_AGE_NANOS + frame.completionNanos();
        number(json, "ts", cameraNanos / 1e6);
        integer(json, "ts_nt", 0);
        integer(json, "ts_sys", WALL_CLOCK_ORIGIN_MICROS + frame.completionNanos() / 1000);
        integer(json, "ts_us", cameraNanos / 1000);
        number(json, "tx", primary == null ? 0 : primary.txDegrees());
        number(json, "txnc", primary == null ? 0 : primary.txDegrees());
        number(json, "ty", primary == null ? 0 : primary.tyDegrees());
        number(json, "tync", primary == null ? 0 : primary.tyDegrees());
        integer(json, "uirefresh", 0);
        integer(json, "v", tags.isEmpty() ? 0 : 1);
        return json.append('}').toString();
    }

    /** The reply before the first frame has completed: a valid document that reports nothing. */
    static String nothingYet(int pipeline, long nowNanos, double[] cameraInRobot) {
        Frame none = new Frame(
                -1, nowNanos, nowNanos, nowNanos, 0, 0, pipeline, false, false, 0, 0, List.of(), ZERO_POSE, ZERO_POSE, 0, 0, 0, 0,
                ZERO_POSE, ZERO_POSE);
        return results(none, cameraInRobot);
    }

    static String status(String name, int pipeline, boolean fiducial, double framesPerSecond, long framesProcessed) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"cameraQuat\":{\"w\":1,\"x\":0,\"y\":0,\"z\":0},\"cid\":9281,\"cpu\":38.5,\"finalYaw\":0");
        number(json, "fps", framesPerSecond);
        integer(json, "hwType", HARDWARE_TYPE);
        integer(json, "ignoreNT", 0);
        integer(json, "interfaceNeedsRefresh", 0);
        json.append(",\"name\":").append(quote(name));
        integer(json, "pipeImgCount", framesProcessed);
        integer(json, "pipelineIndex", pipeline);
        json.append(",\"pipelineType\":\"").append(fiducial ? PIPELINE_TYPE_FIDUCIAL : PIPELINE_TYPE_DEFAULT).append('"');
        number(json, "ram", 21.4);
        integer(json, "snapshotMode", 0);
        number(json, "temp", 49.5);
        return json.append('}').toString();
    }

    private static void fiducial(StringBuilder json, Frame.Reading tag, boolean sendCorners) {
        json.append("{\"fID\":").append(tag.id()).append(",\"fam\":\"36H11C\"");
        json.append(",\"pts\":[");
        if (sendCorners) {
            for (int i = 0; i < 4; i++) {
                json.append(i > 0 ? "," : "").append('[').append(text(tag.pixels()[2 * i])).append(',')
                        .append(text(tag.pixels()[2 * i + 1])).append(']');
            }
        }
        json.append("],\"skew\":[]");
        pose(json, "t6c_ts", tag.cameraInTarget());
        pose(json, "t6r_fs", tag.robotInField());
        pose(json, "t6r_ts", tag.robotInTarget());
        pose(json, "t6t_cs", tag.targetInCamera());
        pose(json, "t6t_rs", tag.targetInRobot());
        number(json, "ta", tag.area());
        number(json, "tx", tag.txDegrees());
        number(json, "tx_nocross", tag.txDegrees());
        number(json, "txp", tag.centreU());
        number(json, "ty", tag.tyDegrees());
        number(json, "ty_nocross", tag.tyDegrees());
        number(json, "typ", tag.centreV());
        json.append('}');
    }

    private static void pose(StringBuilder json, String key, double[] values) {
        json.append(",\"").append(key).append("\":[");
        for (int i = 0; i < values.length; i++) {
            json.append(i > 0 ? "," : "").append(text(values[i]));
        }
        json.append(']');
    }

    private static void number(StringBuilder json, String key, double value) {
        json.append(",\"").append(key).append("\":").append(text(value));
    }

    private static void integer(StringBuilder json, String key, long value) {
        json.append(",\"").append(key).append("\":").append(value);
    }

    /** A JSON number: shortest text that reads back as the same double; a value that is not finite is written as 0. */
    static String text(double value) {
        if (!Double.isFinite(value) || value == 0.0) {
            return "0";
        }
        return Double.toString(value);
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                quoted.append('\\').append(c);
            } else if (c < 0x20) {
                quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }
}
