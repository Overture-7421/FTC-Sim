package dev.ftcsim.importer.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.importer.Fixtures;
import dev.ftcsim.importer.scan.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ManifestJsonTest {
    private static final String HASH = "a".repeat(64);

    static PackageManifest valid() {
        return valid("resources/java-res.jar");
    }

    /** @param javaResources the jar of Java resources the manifest names, or empty for none */
    static PackageManifest valid(String javaResources) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("file", "profile/robot.json");
        profile.put("revision", 3);
        profile.put("tags", List.of("decode", "biobuzz"));
        List<PackageManifest.PackageFile> files = new ArrayList<>(List.of(
                new PackageManifest.PackageFile("libs/001-x.jar", HASH, 10),
                new PackageManifest.PackageFile("program/TeamCode.jar", HASH, 20),
                new PackageManifest.PackageFile("resources/ftcsim-resources.tsv", HASH, 0),
                new PackageManifest.PackageFile("resources/r.jar", HASH, 5)));
        if (!javaResources.isEmpty()) {
            files.add(new PackageManifest.PackageFile(javaResources, HASH, 7));
            files.sort(Comparator.comparing(PackageManifest.PackageFile::path));
        }
        return new PackageManifest(
                PackageManifest.FORMAT,
                PackageManifest.VERSION,
                PackageManifest.IMPORTER_REVISION,
                new PackageManifest.Project("Hornet", ":TeamCode", "org.team", HASH, 3),
                new PackageManifest.Sdk("11.1.0", List.of("org.firstinspires.ftc:RobotCore:11.1.0")),
                List.of("program/TeamCode.jar"),
                List.of(new PackageManifest.Library("libs/001-x.jar", "g:x:1")),
                new PackageManifest.Resources("resources/r.jar", "resources", javaResources),
                new PackageManifest.OpModes(1, List.of(new OpModeDescriptor("Drive", "Main", OpModeKind.TELEOP, "org.team.Drive"))),
                List.of(new Finding("THREAD_USE", "org.team.Drive", "run()V", "Creates a java.lang.Thread.")),
                files,
                Map.of("robotProfile", profile));
    }

    private static ManifestException rejecting(UnaryOperator<String> change) {
        String json = change.apply(ManifestJson.toJson(valid()));
        return assertThrows(ManifestException.class, () -> ManifestJson.parse(json, "manifest.json"));
    }

    @Test
    void aManifestSurvivesAWriteAndARead() {
        PackageManifest manifest = valid();

        PackageManifest read = ManifestJson.parse(ManifestJson.toJson(manifest), "manifest.json");

        assertEquals(manifest, read);
        assertEquals(3, ((Map<?, ?>) read.extras().get("robotProfile")).get("revision"), "what later parts attach to extras is kept as written");
        assertEquals(List.of("decode", "biobuzz"), ((Map<?, ?>) read.extras().get("robotProfile")).get("tags"));
    }

    @Test
    void theJsonListsThePropertiesInTheOrderThePackageIsUnderstood() {
        String json = ManifestJson.toJson(valid());

        assertTrue(json.indexOf("\"format\"") < json.indexOf("\"formatVersion\""));
        assertTrue(json.indexOf("\"formatVersion\"") < json.indexOf("\"project\""));
        assertTrue(json.indexOf("\"files\"") < json.indexOf("\"extras\""));
        assertTrue(json.endsWith("}\n"));
    }

    @Test
    void anUnknownPropertyIsAnErrorThatNamesIt() {
        ManifestException failure = rejecting(json -> json.replace("\"sdk\"", "\"surprise\" : 1,\n  \"sdk\""));

        assertTrue(failure.getMessage().contains("surprise") && failure.getMessage().startsWith("manifest.json: "), failure.getMessage());
    }

    @Test
    void aMissingPropertyIsAnErrorThatNamesIt() {
        ManifestException failure = rejecting(json -> json.replace("\"namespace\" : \"org.team\",", ""));

        assertTrue(failure.getMessage().contains("namespace"), failure.getMessage());
    }

    @Test
    void aNullDuplicateKeyWrongTypeOrTrailingTextIsAnError() {
        assertTrue(rejecting(json -> json.replace("\"11.1.0\"", "null")).getMessage().contains("version"));
        assertTrue(rejecting(json -> json.replace("\"format\"", "\"format\" : \"x\", \"format\"")).getMessage().contains("format"));
        assertTrue(rejecting(json -> json.replace("\"formatVersion\" : 2", "\"formatVersion\" : \"2\"")).getMessage().contains("formatVersion"));
        assertTrue(rejecting(json -> json + "{}").getMessage().contains("manifest.json"));
        assertTrue(rejecting(json -> json.replace("\"TELEOP\"", "0")).getMessage().contains("kind"));
        assertTrue(rejecting(json -> "{").getMessage().startsWith("manifest.json: "));
        assertTrue(rejecting(json -> "[]").getMessage().contains("not a JSON object"));
    }

    @Test
    void aNewerFormatIsRefusedWithTheVersionsInTheMessage() {
        ManifestException failure = rejecting(json -> json.replace("\"formatVersion\" : 2", "\"formatVersion\" : 3"));

        assertEquals(
                "manifest.json: formatVersion is 3, but this version of FTC-Sim reads versions 1 to 2: the package was made by a newer "
                        + "FTC-Sim, so update FTC-Sim to open it",
                failure.getMessage());
    }

    @Test
    void anOlderFormatAndAnotherKindOfFileAreRefused() {
        assertTrue(rejecting(json -> json.replace("\"formatVersion\" : 2", "\"formatVersion\" : 0")).getMessage().contains("prepare the program again"));
        assertTrue(rejecting(json -> json.replace(PackageManifest.FORMAT, "something-else")).getMessage().contains("not a prepared FTC-Sim program"));
    }

    @Test
    void aManifestOfFormatVersion1IsStillReadAsTheFirstImporterRevisionWithoutJavaResources() {
        PackageManifest current = valid("");
        String version1 = Fixtures.asVersion1Manifest(ManifestJson.toJson(current));

        PackageManifest read = ManifestJson.parse(version1, "manifest.json");

        assertEquals(
                new PackageManifest(
                        current.format(), PackageManifest.VERSION, 1, current.project(), current.sdk(), current.program(), current.libraries(),
                        current.resources(), current.opModes(), current.warnings(), current.files(), current.extras()),
                read);
        assertEquals("", read.resources().javaResources());
        assertEquals(1, read.importerRevision());
    }

    @Test
    void whatVersion1NeverHadIsRefusedInAVersion1Manifest() {
        String version1 = ManifestJson.toJson(valid()).replace("\"formatVersion\" : 2", "\"formatVersion\" : 1");

        ManifestException failure = assertThrows(ManifestException.class, () -> ManifestJson.parse(version1, "manifest.json"));

        assertEquals("manifest.json: importerRevision is not a property of a version 1 manifest", failure.getMessage());
        String withJavaResources = Fixtures.asVersion1Manifest(ManifestJson.toJson(valid(""))).replace("\"resources\" : {", "\"resources\" : {\n    \"javaResources\" : \"x\",");
        assertTrue(assertThrows(ManifestException.class, () -> ManifestJson.parse(withJavaResources, "manifest.json")).getMessage().contains("javaResources"));
    }

    @Test
    void theJavaResourcesJarMustBeAListedFileAndTheRevisionMustBePositive() {
        assertTrue(rejecting(json -> json.replace("\"javaResources\" : \"resources/java-res.jar\"", "\"javaResources\" : \"resources/missing.jar\""))
                .getMessage().contains("resources.javaResources"));
        assertTrue(rejecting(json -> json.replace("\"importerRevision\" : " + PackageManifest.IMPORTER_REVISION, "\"importerRevision\" : 0"))
                .getMessage().contains("importerRevision"));
    }

    @Test
    void unsafePathsAreRefusedWhereverTheyAppear() {
        for (String bad : List.of("../evil.jar", "/abs.jar", "libs\\\\x.jar", "libs/CON.jar", "libs/x.jar.", "libs//x.jar", "c:/x.jar")) {
            ManifestException failure = rejecting(json -> json.replace("\"libs/001-x.jar\"", "\"" + bad + "\""));
            assertTrue(failure.getMessage().contains("files[0].path") || failure.getMessage().contains("libraries[0].path"), bad + ": " + failure.getMessage());
        }
    }

    @Test
    void whatTheManifestPointsAtMustBeListed() {
        assertTrue(rejecting(json -> json.replaceFirst("(\"program\"\\s*:\\s*\\[\\s*)\"program/TeamCode.jar\"", "$1\"program/Other.jar\"")).getMessage().contains("program[0]"));
        assertTrue(rejecting(json -> json.replace("\"classes\" : \"resources/r.jar\"", "\"classes\" : \"resources/missing.jar\"")).getMessage().contains("resources.classes"));
        assertTrue(rejecting(json -> json.replace("\"directory\" : \"resources\"", "\"directory\" : \"elsewhere\"")).getMessage().contains("resources.directory"));
    }

    @Test
    void hashesSizesCountsAndDuplicatesAreChecked() {
        assertTrue(rejecting(json -> json.replaceFirst("\"sha256\" : \"a{64}\"", "\"sha256\" : \"ABC\"")).getMessage().contains("sha256"));
        assertTrue(rejecting(json -> json.replaceFirst("\"size\" : 10", "\"size\" : -1")).getMessage().contains("size"));
        assertTrue(rejecting(json -> json.replace("\"count\" : 1", "\"count\" : 2")).getMessage().contains("opModes.count"));
        ManifestException twice = rejecting(json -> json.replace("\"path\" : \"program/TeamCode.jar\"", "\"path\" : \"LIBS/001-X.JAR\""));
        assertTrue(twice.getMessage().contains("twice"), twice.getMessage());
    }

    @Test
    void aManifestMayNotListItself() {
        PackageManifest manifest = valid();
        List<PackageManifest.PackageFile> files = new ArrayList<>(manifest.files());
        files.add(new PackageManifest.PackageFile("manifest.json", HASH, 1));
        String json = ManifestJson.toJson(new PackageManifest(
                manifest.format(), manifest.formatVersion(), manifest.importerRevision(), manifest.project(), manifest.sdk(), manifest.program(), manifest.libraries(),
                manifest.resources(), manifest.opModes(), manifest.warnings(), files, manifest.extras()));

        ManifestException failure = assertThrows(ManifestException.class, () -> ManifestJson.parse(json, "m"));

        assertTrue(failure.getMessage().contains("itself"), failure.getMessage());
    }

    @Test
    void laterPartsAddExtrasAndFilesWithoutDisturbingTheRest() {
        PackageManifest manifest = valid().withExtra("fieldReferences", Map.of("decode", "fields/decode.json"))
                .withFiles(List.of(new PackageManifest.PackageFile("fields/decode.json", HASH, 7)));

        PackageManifest read = ManifestJson.parse(ManifestJson.toJson(manifest), "manifest.json");

        assertEquals(manifest, read);
        assertEquals(List.of("fields/decode.json", "libs/001-x.jar"), read.files().stream().map(PackageManifest.PackageFile::path).limit(2).toList());
        assertTrue(read.extras().containsKey("robotProfile") && read.extras().containsKey("fieldReferences"));
    }
}
