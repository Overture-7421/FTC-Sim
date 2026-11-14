package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/**
 * Starts two threads that outlive the OpMode, as a library's do: one ends when it is interrupted, the other swallows
 * interrupts and runs until the test sets the system property {@code ftcsim.test.releaseThreads}.
 */
@TeleOp(name = "Library Threads", group = "Threads")
public final class LibraryThreadsTeleOp extends OpMode {
    public static final String RELEASE_PROPERTY = "ftcsim.test.releaseThreads";

    @Override
    public void init() {
        Thread polite = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(20);
                }
            } catch (InterruptedException interrupted) {
                Probe.count("polite thread interrupted");
            }
        }, "library-polite");
        Thread stubborn = new Thread(() -> {
            while (System.getProperty(RELEASE_PROPERTY) == null) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Probe.count("stubborn thread ignored an interrupt");
                }
            }
        }, "library-stubborn");
        polite.start();
        stubborn.start();
    }

    @Override
    public void loop() {}
}
