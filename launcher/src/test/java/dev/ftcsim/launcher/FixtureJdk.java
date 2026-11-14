package dev.ftcsim.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Fixture JDKs for stage-0 tests. A real java.exe cannot be faked generically, so a tiny test double compiled from
 * StubTool.cs (the .NET Framework compiler ships with every Windows) plays java.exe, javaw.exe and javac.exe and answers
 * from the release file that sits next to the bin directory. Started as the application, it prints how it was started.
 */
final class FixtureJdk {

    private static Path compiledStub;

    private final Path home;
    private final Map<String, String> extra = new LinkedHashMap<>();
    private String runtimeVersion = FakeAdoptium.OPENJDK_VERSION;
    private String javaVersion = runtimeVersion.substring(0, runtimeVersion.indexOf('+'));
    private String osArch = "x86_64";
    private boolean javac = true;
    private boolean javaw = true;

    private FixtureJdk(Path home) {
        this.home = home;
    }

    static FixtureJdk at(Path home) {
        return new FixtureJdk(home);
    }

    FixtureJdk version(String javaVersion, String runtimeVersion) {
        this.javaVersion = javaVersion;
        this.runtimeVersion = runtimeVersion;
        return this;
    }

    /** A runtime of the given Java major version, with version strings shaped like a real build's. */
    FixtureJdk major(int major) {
        return version(major + ".0.1", major + ".0.1+8-LTS");
    }

    FixtureJdk arch(String osArch) {
        this.osArch = osArch;
        return this;
    }

    FixtureJdk withoutJavac() {
        this.javac = false;
        return this;
    }

    FixtureJdk withoutJavaw() {
        this.javaw = false;
        return this;
    }

    FixtureJdk with(String key, String value) {
        extra.put(key, value);
        return this;
    }

    Path build() throws IOException, InterruptedException {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path stub = stub();
        Files.copy(stub, bin.resolve("java.exe"), StandardCopyOption.REPLACE_EXISTING);
        if (javaw) {
            Files.copy(stub, bin.resolve("javaw.exe"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (javac) {
            Files.copy(stub, bin.resolve("javac.exe"), StandardCopyOption.REPLACE_EXISTING);
        }
        StringBuilder release = new StringBuilder()
                .append("IMPLEMENTOR=\"Eclipse Adoptium\"\n")
                .append("JAVA_VERSION=\"").append(javaVersion).append("\"\n")
                .append("JAVA_RUNTIME_VERSION=\"").append(runtimeVersion).append("\"\n")
                .append("OS_ARCH=\"").append(osArch).append("\"\n")
                .append("MODULES=\"java.base").append(javac ? " jdk.compiler" : "").append("\"\n");
        extra.forEach((key, value) -> release.append(key).append("=\"").append(value).append("\"\n"));
        Files.writeString(home.resolve("release"), release);
        return home;
    }

    /** The stub executable, compiled once per test JVM. */
    static synchronized Path stub() throws IOException, InterruptedException {
        if (compiledStub == null) {
            Path directory = Files.createDirectories(WorkDirFactory.base().resolve("stub"));
            Path source = directory.resolve("StubTool.cs");
            try (InputStream resource = FixtureJdk.class.getResourceAsStream("StubTool.cs")) {
                Files.copy(java.util.Objects.requireNonNull(resource, "StubTool.cs resource"), source, StandardCopyOption.REPLACE_EXISTING);
            }
            Path output = directory.resolve("stub.exe");
            Path csc = Path.of(System.getenv("WINDIR"), "Microsoft.NET", "Framework64", "v4.0.30319", "csc.exe");
            Process process = new ProcessBuilder(csc.toString(), "/nologo", "/target:exe", "/optimize+",
                    "/out:" + output, source.toString()).redirectErrorStream(true).start();
            String log = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Compiling the test double failed: " + log);
            }
            compiledStub = output;
        }
        return compiledStub;
    }
}
