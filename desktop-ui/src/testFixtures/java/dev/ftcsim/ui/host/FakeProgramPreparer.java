package dev.ftcsim.ui.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@link ProgramPreparer} for developing and testing the window without a Gradle build: it recognises folders by a
 * marker file and plays a plausible preparation of six steps with lines of output, a failure and cancellation, all
 * paced by a {@link Scheduler} so that a test can drive time by hand. Nothing in the project is read and nothing is
 * built; the "prepared program" it reports is an empty folder in the work folder.
 *
 * <p>A folder with {@code manifest.json} is a prepared program, one with {@code settings.gradle} (or {@code .kts}) is a
 * project, anything else is not recognised. Which way a preparation goes depends on the folder's name, so a person can
 * see each outcome: a name containing {@code broken} fails to compile, one containing {@code no-sdk} finds no Android
 * SDK, any other succeeds, with two warnings. A request that is not {@linkplain Request#trusted() trusted} is refused.
 */
public final class FakeProgramPreparer implements ProgramPreparer {
    static final long STEP_MILLIS = 400L;
    static final long LINE_MILLIS = 60L;
    static final long CANCEL_MILLIS = 500L;
    static final String SDK_VERSION = "11.1.0";

    private static final String[] STEPS = {
        "Copying the project, leaving the original alone",
        "Building the project with its own Gradle build",
        "Generating the Android resources",
        "Finding the OpModes",
        "Packaging the program",
        "Checking the prepared program"
    };
    private static final String[] GRADLE_TASKS = {
        ":TeamCode:preBuild", ":TeamCode:preDebugBuild", ":TeamCode:compileDebugAidl", ":TeamCode:generateDebugBuildConfig",
        ":TeamCode:mergeDebugResources", ":TeamCode:processDebugManifest", ":TeamCode:compileDebugJavaWithJavac",
        ":TeamCode:processDebugJavaRes", ":TeamCode:bundleLibCompileToJarDebug", ":FtcRobotController:compileDebugJavaWithJavac"
    };
    private static final List<String> OPMODES = List.of("Mecanum TeleOp", "Drive Square", "Competition Auto");
    private static final List<String> WARNINGS = List.of(
            "Vision.java reads the camera through a class that the simulator does not provide yet, so that code may not see anything.",
            "Lift.java waits in a loop on the robot's thread, which can make the OpMode run slowly in the simulator.");

    private final Scheduler scheduler;
    private final Path workFolder;

    /**
     * @param scheduler how the preparation waits between its events
     * @param workFolder where the "prepared programs" and their logs are made
     */
    public FakeProgramPreparer(Scheduler scheduler, Path workFolder) {
        this.scheduler = scheduler;
        this.workFolder = workFolder;
    }

    /** The preparer of the window's own development runs: real time, and a folder under the temporary files. */
    public static FakeProgramPreparer realTime() {
        return new FakeProgramPreparer(
                Scheduler.realTime("fake-program-preparer"), Path.of(System.getProperty("java.io.tmpdir"), "ftc-sim-fake-prepared"));
    }

    @Override
    public Inspection inspect(Path folder) {
        String name = nameOf(folder);
        if (!Files.isDirectory(folder)) {
            return new Inspection(Kind.NOT_RECOGNISED, name, "This is not a folder.");
        }
        if (Files.isRegularFile(folder.resolve("manifest.json"))) {
            return new Inspection(Kind.PREPARED_PROGRAM, name, "Built program: SDK " + SDK_VERSION + ", " + OPMODES.size() + " OpModes");
        }
        if (Files.isRegularFile(folder.resolve("settings.gradle")) || Files.isRegularFile(folder.resolve("settings.gradle.kts"))) {
            return new Inspection(
                    Kind.PROJECT, name, "Team project");
        }
        return new Inspection(
                Kind.NOT_RECOGNISED, name,
                "No settings.gradle here. Choose the project's root folder.");
    }

    @Override
    public Job prepare(Request request, Listener listener) {
        Play play = new Play(request, listener);
        play.begin();
        return play;
    }

    private static String nameOf(Path folder) {
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
    }

    private enum Outcome {
        SUCCEEDS,
        DOES_NOT_COMPILE,
        FINDS_NO_SDK
    }

    private record Beat(long delayMillis, Runnable action) {}

    /** One preparation: a list of beats played one after the other, each waiting for its delay. */
    private final class Play implements Job {
        private final Request request;
        private final Listener listener;
        private final String name;
        private final List<Beat> beats = new ArrayList<>();
        private final List<String> log = new ArrayList<>();
        private volatile boolean cancelled;
        private int next;
        private boolean over;

        Play(Request request, Listener listener) {
            this.request = request;
            this.listener = listener;
            this.name = nameOf(request.projectFolder());
        }

        void begin() {
            if (!request.trusted()) {
                scheduler.schedule(0L, () -> listener.failed(new Failure(
                        "NOT_TRUSTED", "Not trusted", List.of(), null)));
                over = true;
                return;
            }
            compose();
            advance();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void compose() {
            String lower = name.toLowerCase(Locale.ROOT);
            Outcome outcome = lower.contains("no-sdk") ? Outcome.FINDS_NO_SDK : lower.contains("broken") ? Outcome.DOES_NOT_COMPILE : Outcome.SUCCEEDS;
            for (int i = 0; i < STEPS.length; i++) {
                int number = i + 1;
                beats.add(new Beat(STEP_MILLIS, () -> listener.step(new Step(number, STEPS.length, STEPS[number - 1]))));
                if (outcome == Outcome.FINDS_NO_SDK && number == 1) {
                    beats.add(new Beat(STEP_MILLIS, () -> fail("NO_ANDROID_SDK",
                            "No Android SDK found. Install Android Studio and open it once.",
                            List.of("SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable."))));
                    return;
                }
                List<String> lines = linesOf(i);
                int shown = outcome == Outcome.DOES_NOT_COMPILE && number == 2 ? 10 : lines.size();
                for (int line = 0; line < shown; line++) {
                    String text = lines.get(line);
                    beats.add(new Beat(LINE_MILLIS, () -> output(text)));
                }
                if (outcome == Outcome.DOES_NOT_COMPILE && number == 2) {
                    beats.add(new Beat(LINE_MILLIS, () -> output("> Task :TeamCode:compileDebugJavaWithJavac FAILED")));
                    beats.add(new Beat(STEP_MILLIS, () -> fail("COMPILATION_FAILED",
                            "Your code does not compile. Fix the errors and build again.",
                            List.of("TeamCode\\src\\main\\java\\org\\firstinspires\\ftc\\teamcode\\Auto.java:42: error: cannot find symbol",
                                    "        lift.setTargetPoint(120);",
                                    "            ^  symbol: method setTargetPoint(int)", "1 error"))));
                    return;
                }
            }
            beats.add(new Beat(STEP_MILLIS, this::succeed));
        }

        private void advance() {
            if (over) {
                return;
            }
            if (cancelled) {
                over = true;
                scheduler.schedule(CANCEL_MILLIS, () -> listener.failed(new Failure("CANCELLED", "Cancelled", List.of(), null)));
                return;
            }
            if (next >= beats.size()) {
                return;
            }
            Beat beat = beats.get(next++);
            scheduler.schedule(beat.delayMillis(), () -> {
                beat.action().run();
                advance();
            });
        }

        private void output(String line) {
            log.add(line);
            listener.output(line);
        }

        private void fail(String code, String message, List<String> details) {
            over = true;
            listener.failed(new Failure(code, message, details, writeLog()));
        }

        private void succeed() {
            over = true;
            Path folder = workFolder.resolve(name + "-prepared");
            try {
                Files.createDirectories(folder);
            } catch (IOException ignored) {
                // The folder only has to exist for the Project screen to accept it; a fake has no better answer.
            }
            listener.finished(new Prepared(folder, name, SDK_VERSION, OPMODES, WARNINGS, writeLog()));
        }

        private Path writeLog() {
            Path file = workFolder.resolve(name + "-build.log");
            try {
                Files.createDirectories(workFolder);
                Files.write(file, log, StandardCharsets.UTF_8);
                return file;
            } catch (IOException unwritable) {
                return null;
            }
        }
    }

    private static List<String> linesOf(int step) {
        return switch (step) {
            case 0 -> List.of(
                    "Copying the project to the work folder", "Leaving out build, .git, .gradle and IDE folders",
                    "Copied 214 files (3.2 MB)", "Recorded the checksum of every file of the original");
            case 1 -> gradleLines();
            case 2 -> List.of("Reading the resources of the project and its libraries", "Generating the R classes", "Wrote the resource table");
            case 3 -> List.of("Scanning 187 classes", "Found " + OPMODES.size() + " OpModes", "Found " + WARNINGS.size() + " things to look at");
            case 4 -> List.of("Writing program/TeamCode.jar", "Writing 36 library jars", "Writing the manifest");
            default -> List.of("Opening the program the way the simulator will", "Checked 41 files", "The original project did not change");
        };
    }

    private static List<String> gradleLines() {
        List<String> lines = new ArrayList<>();
        lines.add("> Configure project :");
        for (String task : GRADLE_TASKS) {
            lines.add("> Task " + task + (task.contains("compile") ? "" : " UP-TO-DATE"));
        }
        lines.add("");
        lines.add("BUILD SUCCESSFUL in 41s");
        lines.add("37 actionable tasks: 9 executed, 28 up-to-date");
        return lines;
    }
}
