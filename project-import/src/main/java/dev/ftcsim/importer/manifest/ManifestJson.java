package dev.ftcsim.importer.manifest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes {@code manifest.json}. Reading is strict: an unknown property, a missing or null one, a
 * duplicate key, a number where text belongs or text where a number does, and anything after the closing brace
 * are errors that name the place in the file, because a manifest that is only half understood is worse than none.
 * This is the only class of the module that touches JSON.
 */
public final class ManifestJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    /** Two spaces, one property or element per line, and '\n' on every system, so a manifest reads and diffs the same everywhere. */
    private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private ManifestJson() {}

    public static PackageManifest read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    /**
     * Reads a manifest. A manifest of an older format version that this reader still knows (see
     * {@link PackageManifest#OLDEST_VERSION}) comes back as a current one: importer revision 1, no Java resources.
     *
     * @param source where the text came from, for the error messages
     * @throws ManifestException when the text is not a well-formed manifest
     */
    public static PackageManifest parse(String json, String source) {
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (JsonProcessingException failure) {
            throw new ManifestException(source + ": " + failure.getOriginalMessage(), failure);
        }
        if (!(tree instanceof ObjectNode root)) {
            throw new ManifestException(source + ": the manifest is not a JSON object");
        }
        JsonNode format = root.get("format");
        JsonNode version = root.get("formatVersion");
        if (version != null && !version.isInt()) {
            throw new ManifestException(source + ": formatVersion is not a whole number");
        }
        if (format != null && format.isTextual() && version != null) {
            ManifestRules.checkHeader(source, format.textValue(), version.intValue());
            if (version.intValue() == 1) {
                upgradeFromVersion1(root, source);
            }
        }
        PackageManifest manifest;
        try {
            manifest = MAPPER.treeToValue(root, PackageManifest.class);
        } catch (JsonMappingException failure) {
            throw new ManifestException(source + ": " + failure.getOriginalMessage() + " at " + failure.getPathReference(), failure);
        } catch (JsonProcessingException failure) {
            throw new ManifestException(source + ": " + failure.getOriginalMessage(), failure);
        }
        ManifestRules.validate(manifest, source);
        return manifest;
    }

    /** Version 1 had neither the importer revision nor the Java resources jar; it is the first revision of the importer. */
    private static void upgradeFromVersion1(ObjectNode root, String source) {
        if (root.has("importerRevision")) {
            throw new ManifestException(source + ": importerRevision is not a property of a version 1 manifest");
        }
        if (root.get("resources") instanceof ObjectNode resources) {
            if (resources.has("javaResources")) {
                throw new ManifestException(source + ": resources.javaResources is not a property of a version 1 manifest");
            }
            resources.put("javaResources", "");
        }
        root.put("importerRevision", 1);
        root.put("formatVersion", PackageManifest.VERSION);
    }

    public static void write(Path file, PackageManifest manifest) throws IOException {
        Files.writeString(file, toJson(manifest), StandardCharsets.UTF_8);
    }

    public static String toJson(PackageManifest manifest) {
        try {
            return MAPPER.writer(PRINTER).writeValueAsString(manifest) + "\n";
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("a manifest could not be written as JSON", failure);
        }
    }
}
