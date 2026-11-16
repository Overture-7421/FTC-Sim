package dev.ftcsim.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the FTC-Sim.cmd of an installation under cmd.exe the way a driver's PC does: no JAVA_HOME, nothing but Windows on
 * the PATH (the installation brings its own Java), and %LOCALAPPDATA% in a folder of the test, so that nothing of the real
 * user's is read or written. A helper batch sets that up, because Java cannot clear a variable for cmd.exe's own lookups.
 */
final class FtcSimCmd {

    private static final long TIMEOUT_SECONDS = 300;

    /** What the run printed, which is what a driver's console shows, and the exit code of FTC-Sim.cmd. */
    record Result(int exitCode, String output, long millis) {}

    private FtcSimCmd() {}

    /** @param pause whether a failure may wait for a key, as it does for a double-click; a script sets FTCSIM_NO_PAUSE and does not */
    static Result run(Path wrapper, Path work, Path localAppData, boolean pause, String... arguments) throws IOException, InterruptedException {
        Path windows = Path.of(System.getenv("SystemRoot"));
        // Folder names with accented letters are part of the test, and cmd.exe reads a batch file in the console's code page.
        List<String> lines = new ArrayList<>(List.of(
                "@echo off",
                "chcp 65001 >nul",
                "set \"JAVA_HOME=\"",
                "set \"PATH=" + windows.resolve("System32") + "\"",
                "set \"LOCALAPPDATA=" + localAppData + "\""));
        if (!pause) {
            lines.add("set \"FTCSIM_NO_PAUSE=1\"");
        }
        lines.addAll(List.of("call \"" + wrapper + "\" %*", "exit /b %ERRORLEVEL%"));
        Files.writeString(work.resolve("run.cmd"), String.join("\r\n", lines) + "\r\n", StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(List.of("cmd.exe", "/d", "/c", ".\\run.cmd"));
        command.addAll(List.of(arguments));
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("FTC-Sim.cmd did not end within " + TIMEOUT_SECONDS + " seconds:\n" + new String(output, StandardCharsets.UTF_8));
        }
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
