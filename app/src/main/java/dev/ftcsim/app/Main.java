package dev.ftcsim.app;

import dev.ftcsim.ui.App;
import java.util.List;

/** FTC-Sim as a user runs it: the window on a worker process and the project importer. */
public final class Main {
    private static final int USAGE = 2;

    private Main() {}

    /**
     * Options are the window's: {@code --exit-after=<seconds>}, {@code --no-focus} and {@code --settings=<file>}; or
     * {@value SelfTest#OPTION} alone, which opens no window and checks that this installation works.
     */
    public static void main(String[] args) {
        if (List.of(args).contains(SelfTest.OPTION)) {
            if (args.length != 1) {
                System.err.println(SelfTest.OPTION + " takes no other option");
                System.exit(USAGE);
            }
            System.exit(SelfTest.forThisMachine(System.out).run());
        }
        RealHost host = RealHost.forThisMachine();
        try {
            App.run(host, args);
        } finally {
            host.close();
        }
        // Closing the window ends the program, whatever threads the libraries behind the window leave running.
        System.exit(0);
    }
}
