package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.VoltageSensor;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Reads the battery voltage on every pass of its loop, and, when the test asks for it, also from a timer thread in
 * real time, as the battery plugin of a dashboard library does. The timer is a thread the simulation does not schedule.
 */
@TeleOp(name = "Timer Battery", group = "Threads")
public final class TimerBatteryTeleOp extends OpMode {
    /** A system property: how many times the timer reads the battery before it stops itself; none by default. */
    public static final String READS_PROPERTY = "ftcsim.test.batteryTimerReads";

    private static final long TIMER_PERIOD_MILLIS = 2;

    private VoltageSensor battery;
    private Timer timer;
    private int loops;

    @Override
    public void init() {
        Probe.transmitTelemetryImmediately(telemetry);
        battery = hardwareMap.voltageSensor.iterator().next();
        int reads = Integer.getInteger(READS_PROPERTY, 0);
        if (reads > 0) {
            timer = new Timer("battery-timer", true);
            timer.schedule(new TimerTask() {
                private int made;

                @Override
                public void run() {
                    try {
                        battery.getVoltage();
                        Probe.count("foreign reads");
                    } catch (RuntimeException failure) {
                        Probe.count("foreign read failed: " + failure);
                        cancel();
                        return;
                    }
                    if (++made == reads) {
                        cancel();
                    }
                }
            }, 0, TIMER_PERIOD_MILLIS);
        }
    }

    @Override
    public void loop() {
        Probe.record("loop at ns", System.nanoTime());
        telemetry.addData("loops", ++loops);
        telemetry.addData("volts", String.format(Locale.ROOT, "%.3f", battery.getVoltage()));
    }

    @Override
    public void stop() {
        if (timer != null) {
            timer.cancel();
        }
    }
}
