package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import java.util.function.IntConsumer;

/** A linear OpMode that ends the process, in the way the system property {@code ftcsim.test.exitHow} says. */
@Autonomous(name = "Exiting", group = "Exit")
public final class ExitingAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Probe.count("shutdown hook ran")));
        waitForStart();
        try {
            switch (System.getProperty("ftcsim.test.exitHow", "System.exit")) {
                case "Runtime.exit" -> Runtime.getRuntime().exit(7);
                case "Runtime.halt" -> Runtime.getRuntime().halt(7);
                case "method reference" -> {
                    IntConsumer exit = System::exit;
                    exit.accept(7);
                }
                default -> System.exit(7);
            }
            Probe.count("ran on after exit");
        } finally {
            Probe.count("finally after exit");
        }
    }
}
