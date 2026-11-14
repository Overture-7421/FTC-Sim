package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.hardware.Gamepad;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Static state of the fixture robot program. Because it is static it lives as long as the robot world does, which
 * is how real robot programs share data between OpModes (a pose, a starting position) within one session.
 */
public final class Probe {
    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, List<Double>> SERIES = new ConcurrentHashMap<>();

    /** Written by one OpMode and read by the next one to show that static state survives across OpModes. */
    public static volatile String session = "nothing written yet";

    private Probe() {}

    public static void count(String what) {
        COUNTS.computeIfAbsent(what, key -> new AtomicInteger()).incrementAndGet();
    }

    public static Map<String, Integer> counts() {
        Map<String, Integer> snapshot = new HashMap<>();
        COUNTS.forEach((what, count) -> snapshot.put(what, count.get()));
        return snapshot;
    }

    /** Appends a reading, for scenarios whose point is what the hardware reported and when. */
    public static void record(String what, double value) {
        SERIES.computeIfAbsent(what, key -> Collections.synchronizedList(new ArrayList<>())).add(value);
    }

    public static Map<String, List<Double>> series() {
        Map<String, List<Double>> snapshot = new HashMap<>();
        SERIES.forEach((what, values) -> {
            synchronized (values) {
                snapshot.put(what, List.copyOf(values));
            }
        });
        return snapshot;
    }

    /** Real robot code lives with the SDK's 250 ms telemetry interval; the tests would only wait for it. */
    public static void transmitTelemetryImmediately(Telemetry telemetry) {
        telemetry.setMsTransmissionInterval(0);
    }

    /** Every field a driver station gamepad reports, on one line. */
    public static String describe(int number, Gamepad pad) {
        return String.format(
                Locale.ROOT,
                "pad%d lx=%.2f ly=%.2f rx=%.2f ry=%.2f lt=%.2f rt=%.2f up=%b down=%b left=%b right=%b"
                        + " a=%b b=%b x=%b y=%b lb=%b rb=%b ls=%b rs=%b start=%b back=%b guide=%b",
                number,
                pad.left_stick_x,
                pad.left_stick_y,
                pad.right_stick_x,
                pad.right_stick_y,
                pad.left_trigger,
                pad.right_trigger,
                pad.dpad_up,
                pad.dpad_down,
                pad.dpad_left,
                pad.dpad_right,
                pad.a,
                pad.b,
                pad.x,
                pad.y,
                pad.left_bumper,
                pad.right_bumper,
                pad.left_stick_button,
                pad.right_stick_button,
                pad.start,
                pad.back,
                pad.guide);
    }
}
