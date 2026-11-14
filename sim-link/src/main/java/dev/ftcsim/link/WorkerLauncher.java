package dev.ftcsim.link;

import dev.ftcsim.link.wire.Handshake;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Starts a worker process and connects to it. The launcher listens on an ephemeral port of the loopback interface,
 * starts a Java virtual machine whose main class connects back to that port, and hands it a random token on standard
 * input; only a program that knows the token can be the worker of this launch. Everything the worker writes to its
 * standard output and error goes to a log file in the log directory and to a bounded ring of recent lines. When a
 * worker starts, the files of all but the newest {@value #LOGS_KEPT} launches (including this one) are deleted from the
 * log directory.
 *
 * <p>The worker's class path travels in an argument file, because a Windows command line cannot carry the class path
 * of a program of this size. The file is written in the code page of the computer, which is what the Java launcher
 * reads it in. Relative paths in a request mean the same to the worker as to this program: the worker starts in this
 * program's working directory.
 *
 * <pre>{@code
 * RemoteSimulatorLink link = new WorkerLauncher(workerJars, logDirectory).launch();
 * link.addListener(listener);
 * link.open(request);
 * ...
 * link.close(); // asks the worker to end, and ends it if it does not
 * }</pre>
 */
public final class WorkerLauncher {

    /** The class that serves the port in the {@code worker} module. */
    public static final String DEFAULT_MAIN_CLASS = "dev.ftcsim.worker.WorkerMain";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(3);

    /** How many launches' logs the log directory holds once a worker has started; the older ones are deleted. */
    public static final int LOGS_KEPT = 10;

    private final List<Path> classpath;
    private final Path logDirectory;
    private final List<String> jvmArguments = new ArrayList<>();
    private final List<String> programArguments = new ArrayList<>();
    private List<Path> simulatorClasspath = List.of();
    private Path javaExecutable;
    private Path nativeDirectory;
    private String mainClass = DEFAULT_MAIN_CLASS;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Duration shutdownGrace = DEFAULT_SHUTDOWN_GRACE;

    /**
     * @param classpath    everything the worker's main class needs, as jars and class directories
     * @param logDirectory where the worker's log, and the crash report of the Java runtime if it crashes, are written
     */
    public WorkerLauncher(List<Path> classpath, Path logDirectory) {
        this.classpath = List.copyOf(classpath);
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory");
        if (this.classpath.isEmpty()) {
            throw new IllegalArgumentException("the worker needs a class path");
        }
    }

    /** The Java launcher to run the worker with; by default the one this program runs on. */
    public WorkerLauncher javaExecutable(Path java) {
        this.javaExecutable = java;
        return this;
    }

    /** Where the worker unpacks the physics engine's native library; by default a per-user cache. */
    public WorkerLauncher nativeDirectory(Path directory) {
        this.nativeDirectory = directory;
        return this;
    }

    /**
     * The simulator's own jars that a robot world needs besides the program: the Android shim and the SDK adapters.
     * Only a program that carries its libraries but not these, such as one the project importer prepared, needs them.
     */
    public WorkerLauncher simulatorClasspath(List<Path> entries) {
        this.simulatorClasspath = List.copyOf(entries);
        return this;
    }

    /** Further options for the worker's virtual machine, such as a heap size. */
    public WorkerLauncher jvmArguments(List<String> arguments) {
        jvmArguments.clear();
        jvmArguments.addAll(arguments);
        return this;
    }

    /** Which class to run, and what to pass it after the port; only a test of the launcher itself needs to say. */
    public WorkerLauncher mainClass(String name, List<String> arguments) {
        this.mainClass = Objects.requireNonNull(name, "name");
        programArguments.clear();
        programArguments.addAll(arguments);
        return this;
    }

    /** How long to wait for the worker to connect; its virtual machine and class loading start from cold. */
    public WorkerLauncher connectTimeout(Duration timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    /** How long closing the link waits for the worker to end by itself before ending it. */
    public WorkerLauncher shutdownGrace(Duration grace) {
        this.shutdownGrace = grace;
        return this;
    }

    /**
     * Starts the worker and returns the link to it once it has connected.
     *
     * @throws IOException if the process cannot be started, ends before it connects, speaks another protocol version
     *                     or does not connect in time; the message says what the worker wrote to its error output
     */
    public RemoteSimulatorLink launch() throws IOException {
        Files.createDirectories(logDirectory);
        WorkerLogs.prune(logDirectory, LOGS_KEPT - 1);
        String id = launchId();
        Path logFile = logDirectory.resolve("worker-" + id + ".log");
        Path crashReport = logDirectory.resolve("worker-" + id + ".crash.log");
        Path argumentFile = logDirectory.resolve("worker-" + id + ".args");
        Path storage = Files.createTempDirectory("ftcsim-storage-");
        String token = Handshake.newToken();
        Process process = null;
        LaunchedWorker worker = null;
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            Path natives = nativeDirectory == null ? WorkerEnvironment.defaultNativeDirectory() : nativeDirectory;
            String arguments = argumentFile(crashReport, natives, storage);
            Charset charset = argumentFileCharset();
            requireRepresentable(charset, arguments + argumentFile);
            Files.writeString(argumentFile, arguments, charset);
            List<String> command = new ArrayList<>();
            command.add((javaExecutable == null ? currentJava() : javaExecutable).toString());
            command.add("@" + argumentFile);
            command.add(mainClass);
            command.add(WorkerEnvironment.PORT_ARGUMENT);
            command.add(Integer.toString(server.getLocalPort()));
            command.addAll(programArguments);
            process = new ProcessBuilder(command).start();
            worker = new LaunchedWorker(process, logFile, crashReport, List.of(argumentFile, storage));
            worker.sendToken(token);
            RemoteSimulatorLink link = RemoteSimulatorLink.accept(server, token, connectTimeout, worker, shutdownGrace);
            Files.deleteIfExists(argumentFile);
            return link;
        } catch (IOException | RuntimeException failure) {
            abandon(process, worker, argumentFile, storage);
            throw failure;
        }
    }

    private String argumentFile(Path crashReport, Path natives, Path storage) {
        List<String> lines = new ArrayList<>();
        lines.add("--enable-native-access=ALL-UNNAMED");
        // The robot world seeds ThreadLocalRandom through sun.misc.Unsafe; without this the JDK prints a four-line warning once.
        lines.add("--sun-misc-unsafe-memory-access=allow");
        lines.add("-Dfile.encoding=UTF-8");
        lines.add("-Dstdout.encoding=UTF-8");
        lines.add("-Dstderr.encoding=UTF-8");
        lines.add("-D" + WorkerEnvironment.NATIVE_DIRECTORY_PROPERTY + "=" + natives);
        lines.add("-D" + WorkerEnvironment.STORAGE_DIRECTORY_PROPERTY + "=" + storage);
        lines.add("-XX:ErrorFile=" + crashReport);
        if (!simulatorClasspath.isEmpty()) {
            lines.add("-D" + WorkerEnvironment.SIMULATOR_CLASSPATH_PROPERTY + "="
                    + simulatorClasspath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        lines.addAll(jvmArguments);
        lines.add("-cp");
        lines.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        return lines.stream().map(WorkerLauncher::quote).collect(Collectors.joining("\n", "", "\n"));
    }

    /** One token of an argument file: in double quotes, where a backslash and a quote need a backslash of their own. */
    static String quote(String argument) {
        return '"' + argument.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * The Java launcher reads an argument file in the encoding of the operating system, which on Windows is the
     * code page and not UTF-8, so a path with a letter such as the one in a user named Zoë is only right in that.
     */
    static Charset argumentFileCharset() {
        String name = System.getProperty("native.encoding");
        try {
            return name == null ? Charset.defaultCharset() : Charset.forName(name);
        } catch (IllegalArgumentException unknown) {
            return Charset.defaultCharset();
        }
    }

    /** A path with a character that the code page lacks cannot reach the worker, which is better said than guessed at. */
    static void requireRepresentable(Charset charset, String text) throws IOException {
        CharsetEncoder encoder = charset.newEncoder();
        if (encoder.canEncode(text)) {
            return;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            if (!encoder.canEncode(character)) {
                throw new IOException("A path of the worker holds the character '" + character + "' (U+"
                        + String.format("%04X", codePoint) + "), which the code page " + charset.displayName()
                        + " of this computer cannot hand to the Java launcher. Use folders whose names are written in that code page.");
            }
            i += Character.charCount(codePoint);
        }
    }

    /** The Java launcher of the virtual machine this program runs on. */
    static Path currentJava() {
        Optional<String> running = ProcessHandle.current().info().command();
        if (running.isPresent()) {
            Path path = Path.of(running.get());
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if ((name.equals("java.exe") || name.equals("javaw.exe") || name.equals("java")) && Files.isExecutable(path)) {
                return path;
            }
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    private static String launchId() {
        byte[] random = new byte[2];
        new SecureRandom().nextBytes(random);
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + "-" + HexFormat.of().formatHex(random);
    }

    private static void abandon(Process process, LaunchedWorker worker, Path argumentFile, Path storage) {
        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (worker != null) {
            worker.release();
        } else {
            WorkerEnvironment.deleteTree(argumentFile);
            WorkerEnvironment.deleteTree(storage);
        }
    }
}
