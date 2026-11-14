package dev.ftcsim.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the FTC-Sim.cmd of an installation under cmd.exe the way a driver's PC does, apart from the Java it finds: no
 * JAVA_HOME unless the test names one, nothing but Windows on the PATH, and %LOCALAPPDATA% in a folder of the test, so that
 * nothing of the real user's is read or written. A helper batch sets that up, because Java cannot clear a variable for
 * cmd.exe's own lookups.
 */
final class FtcSimCmd {

    private static final long TIMEOUT_SECONDS = 300;

    /** What the run printed, which is what a driver's console shows, and the exit code of FTC-Sim.cmd. */
    record Result(int exitCode, String output, long millis) {}

    private FtcSimCmd() {}

    /** @param javaHome the JAVA_HOME of the run; empty for a PC that has none */
    static Result run(Path wrapper, Path work, Path localAppData, String javaHome, String... arguments) throws IOException, InterruptedException {
        Path windows = Path.of(System.getenv("SystemRoot"));
        // Folder names with accented letters are part of the test, and cmd.exe reads a batch file in the console's code page.
        List<String> lines = List.of(
                "@echo off",
                "chcp 65001 >nul",
                "set \"JAVA_HOME=" + javaHome + "\"",
                "set \"PATH=" + windows.resolve("System32") + "\"",
                "set \"LOCALAPPDATA=" + localAppData + "\"",
                "set \"FTCSIM_NO_PAUSE=1\"",
                "call \"" + wrapper + "\" %*",
                "exit /b %ERRORLEVEL%");
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
