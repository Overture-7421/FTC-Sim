package dev.ftcsim.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * What the build says about the Java runtime that comes with the application ({@code runtime} beside {@code lib}): the
 * major version it was made from, and the options {@code FTC-Sim.cmd} starts it with. Both are resources of the
 * application's jar, so the self test can check the running Java against them; the options are the file
 * {@code runtime-options} of the distribution, which the launcher reads as a Java argument file.
 *
 * @param major         the Java major version the application is compiled for and its runtime is made from
 * @param jvmArguments  the options the launcher passes to the Java that runs the application
 */
record RuntimePin(int major, List<String> jvmArguments) {

    private static final String OPTIONS_RESOURCE = "runtime-options";
    private static final String VERSION_RESOURCE = "runtime.properties";

    RuntimePin {
        jvmArguments = List.copyOf(jvmArguments);
    }

    /** The pin that came with this application. */
    static RuntimePin bundled() throws IOException {
        try (InputStream version = RuntimePin.class.getResourceAsStream(VERSION_RESOURCE);
                InputStream options = RuntimePin.class.getResourceAsStream(OPTIONS_RESOURCE)) {
            if (version == null || options == null) {
                throw new IOException("the application lacks its runtime description (" + VERSION_RESOURCE + ", " + OPTIONS_RESOURCE + ")");
            }
            return read(version, options);
        }
    }

    static RuntimePin read(InputStream version, InputStream options) throws IOException {
        Properties properties = new Properties();
        properties.load(version);
        int major;
        try {
            major = Integer.parseInt(properties.getProperty("major", "").trim());
        } catch (NumberFormatException notANumber) {
            throw new IOException(VERSION_RESOURCE + " has no major version: " + properties.getProperty("major"), notANumber);
        }
        List<String> arguments = new ArrayList<>();
        for (String line : new String(options.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
            String option = line.strip();
            if (!option.isEmpty() && !option.startsWith("#")) {
                arguments.add(option);
            }
        }
        if (arguments.isEmpty()) {
            throw new IOException(OPTIONS_RESOURCE + " lists no option");
        }
        return new RuntimePin(major, arguments);
    }

    /** Where the runtime folder of the installation this program runs from is, whether it is there or not; empty when it runs from build folders, as in development. */
    static Optional<Path> installedRuntime() {
        Path jar = AppClasspath.applicationJar();
        if (jar == null || !Files.isRegularFile(jar) || jar.getParent() == null || jar.getParent().getParent() == null) {
            return Optional.empty();
        }
        return Optional.of(jar.getParent().getParent().resolve("runtime"));
    }
}
