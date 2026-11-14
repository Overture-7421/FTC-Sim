package dev.ftcsim.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the shipped stage-0 scripts under Windows PowerShell 5.1, the way a PC with no Java would. By default the machine's own
 * execution policy applies; set FTCSIM_TEST_EXECUTION_POLICY (for example to Bypass) on a PC whose policy blocks local scripts.
 */
final class Stage0 {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private Stage0() {
    }

    static Path scripts() {
        return Path.of(System.getProperty("ftcsim.stage0.scripts"));
    }

    static Path powershell() {
        return Path.of(System.getenv("WINDIR"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    /** Runs FTC-Sim.ps1 with the given arguments; a null environment value removes the variable. */
    static Result run(Map<String, String> environment, String... arguments) {
        return start(scripts().resolve("FTC-Sim.ps1"), environment, arguments).await();
    }

    /**
     * Runs a snippet against the stage-0 libraries (not the entry script): the snippet may use any function from ftcsim\,
     * report facts with Out-Fact, and a failure is reported as KIND/MESSAGE lines with exit code 3.
     */
    static Result scenario(Path work, Path manifest, String body) {
        return startScenario(work, manifest, "scenario.ps1", body).await();
    }

    static Running startScenario(Path work, Path manifest, String fileName, String body) {
        String script = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                ". " + quote(scripts().resolve("ftcsim").resolve("Messages.ps1")),
                "Set-StrictMode -Version Latest",
                "foreach ($library in @(Get-ChildItem -LiteralPath " + quote(scripts().resolve("ftcsim"))
                        + " -Filter '*.ps1' | Where-Object { $_.Name -ne 'Messages.ps1' } | Sort-Object Name)) { . $library.FullName }",
                "$manifest = Read-RuntimeManifest " + quote(manifest),
                "Initialize-Networking",
                "function Out-Fact([string]$Name, $Value) { Write-Host ('OUT ' + $Name + '=' + $Value) }",
                "try {",
                body,
                "} catch {",
                "    Write-Host ('KIND ' + (Get-FtcKind $_))",
                "    Write-Host ('MESSAGE ' + $_.Exception.Message)",
                "    exit 3",
                "}",
                "");
        try {
            Path file = Files.writeString(work.resolve(fileName), script);
            return start(file, Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String quote(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }

    /**
     * Runs FTC-Sim.ps1 -NonInteractive inside a runspace that is locked to ConstrainedLanguage, which is how policies such as
     * Smart App Control or AppLocker script rules restrict PowerShell. The language mode is the engine's own, so the
     * shipped script is exercised unchanged; the host script around it only relays what the restricted script printed.
     */
    static Result runConstrained(Path work, Path cacheRoot) {
        String script = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$runspace = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspace()",
                "$runspace.Open()",
                "$runspace.SessionStateProxy.LanguageMode = [System.Management.Automation.PSLanguageMode]::ConstrainedLanguage",
                "$shell = [System.Management.Automation.PowerShell]::Create()",
                "$shell.Runspace = $runspace",
                "[void]$shell.AddCommand(" + quote(scripts().resolve("FTC-Sim.ps1")) + ").AddParameter('NonInteractive').AddParameter('CacheRoot', "
                        + quote(cacheRoot) + ")",
                "$output = @($shell.Invoke())",
                "foreach ($record in $shell.Streams.Information) { Write-Host $record.MessageData.Message }",
                "foreach ($record in $shell.Streams.Error) { Write-Host ('ERROR ' + $record) }",
                "Write-Host ('OUT languageMode=' + $runspace.SessionStateProxy.LanguageMode)",
                "exit [int]$shell.Runspace.SessionStateProxy.PSVariable.GetValue('LASTEXITCODE')",
                "");
        try {
            return start(Files.writeString(work.resolve("constrained.ps1"), script), Map.of()).await();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs the shipped FTC-Sim.cmd under cmd.exe, the way a double-click or a command prompt does. Java cannot override
     * SystemRoot for a child process and cmd.exe resets PROCESSOR_ARCHITEW6432 when it starts, so a helper batch file sets
     * these variables (and the rest of {@code environment}; null clears one) before it calls the wrapper. SystemRoot should
     * name a folder holding a test-double powershell.exe, unless the real one is wanted.
     */
    static Result runWrapper(Path work, Path systemRoot, Map<String, String> environment, String... arguments) {
        return runWrapperOf(scripts().resolve("FTC-Sim.cmd"), work, systemRoot, environment, arguments);
    }

    /** {@link #runWrapper} for a wrapper that was copied somewhere else, for instance into a folder with spaces in its name. */
    static Result runWrapperOf(Path wrapper, Path work, Path systemRoot, Map<String, String> environment, String... arguments) {
        List<String> lines = new ArrayList<>(List.of("@echo off", "set \"SystemRoot=" + systemRoot + "\""));
        environment.forEach((key, value) -> lines.add("set \"" + key + "=" + (value == null ? "" : value) + "\""));
        lines.add("call \"" + wrapper + "\" %*");
        lines.add("exit /b %ERRORLEVEL%");
        try {
            Path helper = Files.writeString(work.resolve("run-wrapper.cmd"), String.join("\r\n", lines) + "\r\n");
            List<String> command = new ArrayList<>(List.of("cmd.exe", "/c", helper.toString()));
            command.addAll(List.of(arguments));
            return launch(command, Map.of()).await();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs FTC-Sim.ps1 with its output in a file instead of a pipe, for a run that leaves the application running (-Detach):
     * that process holds whatever handles it inherited, and a pipe would not reach its end until the application ends.
     */
    static Result runToFile(Path output, Map<String, String> environment, String... arguments) {
        ProcessBuilder builder = builder(powershellCommand(scripts().resolve("FTC-Sim.ps1"), arguments), environment)
                .redirectErrorStream(true).redirectOutput(output.toFile());
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out after " + TIMEOUT + ":\n" + Files.readString(output));
            }
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Running start(Path script, Map<String, String> environment, String... arguments) {
        return launch(powershellCommand(script, arguments), environment);
    }

    private static List<String> powershellCommand(Path script, String... arguments) {
        List<String> command = new ArrayList<>(List.of(powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive"));
        String policy = System.getenv("FTCSIM_TEST_EXECUTION_POLICY");
        if (policy != null && !policy.isBlank()) {
            command.add("-ExecutionPolicy");
            command.add(policy);
        }
        command.add("-File");
        command.add(script.toString());
        command.addAll(List.of(arguments));
        return command;
    }

    private static ProcessBuilder builder(List<String> command, Map<String, String> environment) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("JAVA_HOME");
        builder.environment().put("FTCSIM_NO_PAUSE", "1");
        environment.forEach((key, value) -> {
            if (value == null) {
                builder.environment().remove(key);
            } else {
                builder.environment().put(key, value);
            }
        });
        return builder;
    }

    static Running launch(List<String> command, Map<String, String> environment) {
        ProcessBuilder builder = builder(command, environment);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return new Running(process, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A started process whose output is collected in the background. */
    static final class Running {
        private final Process process;
        private final CompletableFuture<String> output;

        Running(Process process, CompletableFuture<String> output) {
            this.process = process;
            this.output = output;
        }

        Result await() {
            try {
                if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    kill();
                    throw new IllegalStateException("Timed out after " + TIMEOUT + ":\n" + output.getNow("(no output yet)"));
                }
                return new Result(process.exitValue(), output.get(30, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                kill();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Kills exactly the process this test started, and anything it started in turn. */
        void kill() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    record Result(int exitCode, String output) {

        private static final Pattern FACT = Pattern.compile("(?m)^OUT ([^=]+)=(.*)$");

        Map<String, String> facts() {
            Map<String, String> facts = new HashMap<>();
            Matcher matcher = FACT.matcher(output);
            while (matcher.find()) {
                facts.put(matcher.group(1), matcher.group(2).strip());
            }
            return facts;
        }

        String fact(String name) {
            return facts().get(name);
        }

        String kind() {
            return line("KIND ");
        }

        String line(String prefix) {
            return output.lines().filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length())).findFirst().orElse(null);
        }

        /** Whether a line of the output is exactly this, ignoring case, as the names of Windows files are. */
        boolean hasLine(String expected) {
            return output.lines().anyMatch(expected::equalsIgnoreCase);
        }

        /** A string value from the FTCSIM-RESULT line that FTC-Sim.ps1 -ResolveOnly prints. */
        String json(String key) {
            String line = line("FTCSIM-RESULT ");
            if (line == null) {
                throw new IllegalStateException("No FTCSIM-RESULT line in:\n" + output);
            }
            Matcher matcher = Pattern.compile("\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(line);
            if (!matcher.find()) {
                throw new IllegalStateException("No string '" + key + "' in " + line);
            }
            return unescape(matcher.group(1));
        }

        boolean jsonFlag(String key) {
            String line = line("FTCSIM-RESULT ");
            if (line == null) {
                throw new IllegalStateException("No FTCSIM-RESULT line in:\n" + output);
            }
            return line.contains("\"" + key + "\":true");
        }

        /** A whole-number value from the FTCSIM-RESULT line. */
        int jsonInt(String key) {
            String line = line("FTCSIM-RESULT ");
            if (line == null) {
                throw new IllegalStateException("No FTCSIM-RESULT line in:\n" + output);
            }
            Matcher matcher = Pattern.compile("\"" + key + "\":(\\d+)").matcher(line);
            if (!matcher.find()) {
                throw new IllegalStateException("No number '" + key + "' in " + line);
            }
            return Integer.parseInt(matcher.group(1));
        }

        private static String unescape(String text) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(++i);
                    if (next == 'u' && i + 4 < text.length()) {
                        result.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        result.append(next);
                    }
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }
    }
}
