package dev.ftcsim.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** A computer described by a folder: its "C:" and the few environment variables the application looks at. */
final class FakeMachine {
    final Path root;
    final Map<String, String> variables = new HashMap<>();

    FakeMachine(Path root) {
        this.root = root;
        variables.put("ProgramFiles", root.resolve("Program Files").toString());
        variables.put("LOCALAPPDATA", root.resolve("Users").resolve("student").resolve("AppData").resolve("Local").toString());
        variables.put("USERPROFILE", root.resolve("Users").resolve("student").toString());
    }

    Machine machine() {
        return new Machine(variables::get);
    }

    Path programFiles(String... below) {
        return resolve(Path.of(variables.get("ProgramFiles")), below);
    }

    Path localAppData(String... below) {
        return resolve(Path.of(variables.get("LOCALAPPDATA")), below);
    }

    Path userProfile(String... below) {
        return resolve(Path.of(variables.get("USERPROFILE")), below);
    }

    private static Path resolve(Path base, String... below) {
        Path path = base;
        for (String name : below) {
            path = path.resolve(name);
        }
        return path;
    }

    /** A JDK as a vendor installs it: both launchers and the release file. Pass a null version for no release file. */
    static Path jdk(Path home, String javaVersion, String architecture, String implementor) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java.exe"), "");
        Files.writeString(home.resolve("bin").resolve("javac.exe"), "");
        if (javaVersion != null) {
            Files.writeString(
                    home.resolve("release"),
                    "IMPLEMENTOR=\"" + implementor + "\"\nJAVA_VERSION=\"" + javaVersion + "\"\nOS_ARCH=\"" + architecture + "\"\nOS_NAME=\"Windows\"\n");
        }
        return home;
    }

    static Path jdk(Path home, String javaVersion) throws IOException {
        return jdk(home, javaVersion, "x86_64", "Eclipse Adoptium");
    }
}
