package dev.ftcsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What the launcher's manifest ({@code jdk-manifest.json}, which the build copies into this module as a resource) says
 * about the Java the application runs on: its major version, which the launcher enforces by never starting the
 * application on another, and the options it starts the application with, which the window needs.
 *
 * @param major         the one Java major version the application runs on
 * @param jvmArguments  the options the launcher passes to the Java that runs the application
 */
record RuntimePin(int major, List<String> jvmArguments) {

    RuntimePin {
        jvmArguments = List.copyOf(jvmArguments);
    }

    /** The pin of the manifest that came with this application. */
    static RuntimePin bundled() throws IOException {
        try (InputStream manifest = RuntimePin.class.getResourceAsStream("jdk-manifest.json")) {
            if (manifest == null) {
                throw new IOException("the application lacks its runtime manifest (jdk-manifest.json)");
            }
            return read(manifest);
        }
    }

    static RuntimePin read(InputStream manifest) throws IOException {
        JsonNode root = new ObjectMapper().readTree(manifest);
        JsonNode major = root.path("runtime").path("major");
        JsonNode options = root.path("application").path("jvmArguments");
        if (!major.isInt() || !options.isArray()) {
            throw new IOException("jdk-manifest.json has no runtime.major or application.jvmArguments");
        }
        List<String> arguments = new ArrayList<>();
        for (JsonNode option : options) {
            if (!option.isTextual() || option.asText().isBlank()) {
                throw new IOException("jdk-manifest.json: application.jvmArguments must be a list of options");
            }
            arguments.add(option.asText());
        }
        return new RuntimePin(major.asInt(), arguments);
    }
}
