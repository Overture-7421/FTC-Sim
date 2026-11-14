package dev.ftcsim.app;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds the Android SDK that a team project's build needs. */
final class AndroidSdkDiscovery {
    private final Machine machine;

    AndroidSdkDiscovery(Machine machine) {
        this.machine = machine;
    }

    /**
     * The first existing folder of {@code ANDROID_HOME}, {@code ANDROID_SDK_ROOT} and {@code %LOCALAPPDATA%\Android\Sdk},
     * the places Android Studio and the Android tools use. When none exists, the first of them, so that the importer
     * says that this folder is missing, which is where the user expects it.
     */
    Path find() {
        List<Path> candidates = new ArrayList<>();
        for (String name : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String value = machine.variable(name);
            if (value != null) {
                try {
                    candidates.add(Path.of(value));
                } catch (InvalidPathException invalid) {
                    // Not a path: not an SDK.
                }
            }
        }
        candidates.add(machine.localAppData().resolve("Android").resolve("Sdk"));
        return candidates.stream().filter(Files::isDirectory).findFirst().orElse(candidates.get(0));
    }
}
