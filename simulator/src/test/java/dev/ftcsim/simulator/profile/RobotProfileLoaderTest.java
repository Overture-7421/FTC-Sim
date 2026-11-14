package dev.ftcsim.simulator.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.WheelPosition;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobotProfileLoaderTest {

    private static final String MINIMAL = "{\"schema\":1,\"name\":\"bare\"}";

    private static String message(String json) {
        return assertThrows(ProfileException.class, () -> RobotProfileLoader.parse(json, "test.json")).getMessage();
    }

    @Test
    void theBundledHornetProfileIsValidAndDescribesAMecanumDrivetrainWithFourShafts() throws IOException {
        RobotProfile profile = RobotProfileLoader.loadBundled("hornet-2026.json");

        assertEquals(2, profile.hubs().size());
        assertEquals(173, RobotProfileLoader.parentAddress(profile));
        assertEquals(8, profile.motors().size());
        assertEquals(WheelPosition.FRONT_LEFT, WheelPosition.valueOf(profile.motors().get("frontLeft").wheel().position()));
        assertEquals(-1, profile.motors().get("frontLeft").wheel().polarity());
        assertEquals(11.3, profile.chassis().toConfig().massKg());
        assertEquals(MecanumConfig.generic18Inch().wheelRadiusM(), profile.chassis().toConfig().wheelRadiusM(), "unsaid values are the generic robot's");
    }

    @Test
    void theDriveLossesOfAChassisReachTheConfigAndAnEmptyChassisIsExactlyTheGenericRobot() throws IOException {
        MecanumConfig hornet = RobotProfileLoader.loadBundled("hornet-2026.json").chassis().toConfig();
        MecanumConfig empty = RobotProfileLoader.parse(
                "{\"schema\":1,\"name\":\"x\",\"chassis\":{},\"motors\":{" + allWheels(1) + "}}", "x.json").chassis().toConfig();

        assertTrue(hornet.wheelFrictionNm() > 0 && hornet.rollerRollingResistance() > 0, hornet.toString());
        assertEquals(MecanumConfig.generic18Inch(), empty);
    }

    @Test
    void anAbsentSectionTakesItsDefaultAndAnEmptyRobotHasTheEmbeddedHubOnly() {
        RobotProfile profile = RobotProfileLoader.parse(MINIMAL, "minimal.json");

        assertEquals(12.6, profile.batteryVolts());
        assertEquals(1, profile.hubs().size());
        assertEquals("Control Hub", profile.hubs().get(0).name());
        assertTrue(profile.motors().isEmpty() && profile.servos().isEmpty() && profile.devices().isEmpty());
    }

    @Test
    void aTypoInAPropertyNameIsAnErrorThatNamesTheFileAndThePlace() {
        String message = message("{\"schema\":1,\"name\":\"x\",\"motors\":{\"arm\":{\"port\":0,\"shaft\":{\"loadInertiaKgM2\":1,\"frictionNmsPerRadd\":2}}}}");

        assertTrue(message.startsWith("test.json: "), message);
        assertTrue(message.contains("frictionNmsPerRadd"), message);
        assertTrue(message.contains("motors") && message.contains("arm"), message);
    }

    @Test
    void aMissingRequiredPropertyAndADuplicateKeyAreErrors() {
        assertTrue(message("{\"schema\":1}").contains("name"));
        assertTrue(message("{\"schema\":1,\"name\":\"a\",\"name\":\"b\"}").contains("Duplicate"));
        assertTrue(message("{\"schema\":1,\"name\":\"x\",\"motors\":{\"arm\":{\"shaft\":{\"loadInertiaKgM2\":1}}}}").contains("port"));
    }

    @Test
    void aMotorMustSayWhatItTurnsAndTwoMotorsCannotShareAPort() {
        assertTrue(message("{\"schema\":1,\"name\":\"x\",\"motors\":{\"arm\":{\"port\":0}}}")
                .contains("exactly one of 'wheel' and 'shaft'"));
        String both = "{\"schema\":1,\"name\":\"x\",\"motors\":{\"a\":{\"port\":1,\"shaft\":{\"loadInertiaKgM2\":1}},"
                + "\"b\":{\"port\":1,\"shaft\":{\"loadInertiaKgM2\":1}}}}";
        assertTrue(message(both).contains("already used"), message(both));
        assertTrue(message("{\"schema\":1,\"name\":\"x\",\"motors\":{\"a\":{\"port\":4,\"shaft\":{\"loadInertiaKgM2\":1}}}}")
                .contains("does not exist"));
    }

    @Test
    void aDrivetrainNeedsAChassisAllFourWheelsAndAPolarityOfPlusOrMinusOne() {
        String threeWheels = "{\"schema\":1,\"name\":\"x\",\"chassis\":{},\"motors\":{"
                + wheel("a", 0, "FRONT_LEFT", 1) + "," + wheel("b", 1, "FRONT_RIGHT", 1) + "," + wheel("c", 2, "REAR_LEFT", 1) + "}}";
        assertTrue(message(threeWheels).contains("all four wheels"), message(threeWheels));

        String noChassis = "{\"schema\":1,\"name\":\"x\",\"motors\":{" + allWheels(1) + "}}";
        assertTrue(message(noChassis).contains("no 'chassis'"), message(noChassis));

        String badPolarity = "{\"schema\":1,\"name\":\"x\",\"chassis\":{},\"motors\":{" + allWheels(2) + "}}";
        assertTrue(message(badPolarity).contains("polarity is 1 or -1"), message(badPolarity));

        String fine = "{\"schema\":1,\"name\":\"x\",\"chassis\":{},\"motors\":{" + allWheels(1) + "}}";
        assertEquals(4, RobotProfileLoader.parse(fine, "fine.json").motors().size());
    }

    @Test
    void hubsMustHaveOneParentDistinctAddressesAndEveryDeviceMustSitOnAHubThatExists() {
        assertTrue(message("{\"schema\":1,\"name\":\"x\",\"hubs\":[{\"address\":2,\"name\":\"a\"}]}").contains("exactly one hub is the parent"));
        assertTrue(message("{\"schema\":1,\"name\":\"x\",\"hubs\":[{\"address\":2,\"name\":\"a\",\"parent\":true},{\"address\":2,\"name\":\"b\"}]}")
                .contains("two hubs have address 2"));
        assertTrue(message("{\"schema\":1,\"name\":\"x\",\"servos\":{\"s\":{\"hub\":9,\"port\":0}}}").contains("hub 9 is not one of the profile's hubs"));
        assertTrue(message("{\"schema\":1,\"name\":\"x\",\"devices\":{\"d\":{\"type\":\"t\",\"bus\":4}}}").contains("I2C bus 4 does not exist"));
    }

    @Test
    void aSchemaThisSimulatorDoesNotKnowIsRefusedRatherThanGuessedAt() {
        assertTrue(message("{\"schema\":2,\"name\":\"x\"}").contains("schema is 2"));
    }

    @Test
    void theBundledNamesAreTheShippedProfilesAndEachOneLoads() throws IOException {
        List<String> names = RobotProfileLoader.bundledNames();

        assertTrue(names.contains("hornet-2026.json"), names.toString());
        for (String name : names) {
            assertFalse(RobotProfileLoader.loadBundled(name).name().isBlank(), name);
        }
    }

    @Test
    void jsonFilesAreListedSortedFromADirectoryAndFromInsideAJar(@TempDir Path folder) throws IOException {
        Path profiles = Files.createDirectory(folder.resolve("profiles"));
        for (String name : List.of("b.json", "a.json", "notes.txt")) {
            Files.writeString(profiles.resolve(name), "{}");
        }
        Files.writeString(Files.createDirectory(profiles.resolve("nested")).resolve("c.json"), "{}");
        Path jar = folder.resolve("shipped.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry : List.of("profiles/b.json", "profiles/a.json", "profiles/notes.txt", "profiles/nested/c.json")) {
                out.putNextEntry(new JarEntry(entry));
                out.write('{');
                out.closeEntry();
            }
        }

        List<String> expected = List.of("a.json", "b.json");
        assertEquals(expected, RobotProfileLoader.jsonFilesAt(profiles.toUri().toURL()));
        assertEquals(expected, RobotProfileLoader.jsonFilesAt(URI.create("jar:" + jar.toUri() + "!/profiles").toURL()));
    }

    @Test
    void theBundledHornetProfileHasItsLimelightWithAMountAndAnAprilTagPipelineOnTheDecodeMap() throws IOException {
        RobotProfile.Camera camera = RobotProfileLoader.loadBundled("hornet-2026.json").cameras().get("limelight");

        assertEquals("limelight-3a", camera.type());
        assertEquals("decode", camera.pipelines().get("0").fieldMap());
        assertEquals("1280x960", camera.settings().get("pipeline0.resolution"));
        assertEquals(0.16, camera.settings().get("mount.x"));
        assertTrue(RobotProfileLoader.parse(MINIMAL, "minimal.json").cameras().isEmpty(), "robots have no cameras unless the profile lists some");
    }

    @Test
    void aCameraNeedsAKnownTypeAMountAndPipelinesTheModelCanRun() {
        String mount = "\"mount\":{\"x\":0.1,\"y\":0,\"z\":0.2}";
        String pipeline = "\"pipelines\":{\"0\":{\"type\":\"apriltag\",\"fieldMap\":\"decode\"}}";
        assertEquals(1, RobotProfileLoader.parse(camera("\"type\":\"limelight-3a\"," + mount + "," + pipeline), "fine.json").cameras().size());

        String unknown = message(camera("\"type\":\"limelight-9\"," + mount));
        assertTrue(unknown.contains("cameras.front") && unknown.contains("limelight-9") && unknown.contains("limelight-3a"), unknown);
        String noMount = message(camera("\"type\":\"limelight-3a\""));
        assertTrue(noMount.contains("mount"), noMount);
        String noZ = message(camera("\"type\":\"limelight-3a\",\"mount\":{\"x\":1,\"y\":0}"));
        assertTrue(noZ.contains("'z'") || noZ.contains("\"z\"") || noZ.contains(" z"), noZ);
        String noMap = message(camera("\"type\":\"limelight-3a\"," + mount + ",\"pipelines\":{\"0\":{\"type\":\"apriltag\"}}"));
        assertTrue(noMap.contains("fieldMap"), noMap);
        String typo = message(camera("\"type\":\"limelight-3a\"," + mount
                + ",\"pipelines\":{\"0\":{\"type\":\"apriltag\",\"fieldMap\":\"none\",\"resolutoin\":\"640x480\"}}"));
        assertTrue(typo.contains("resolutoin"), typo);
    }

    @Test
    void aCamerasPipelineSettingsAreCheckedAndTheMessageSaysWhichCameraAndWhichSetting() {
        String head = "\"type\":\"limelight-3a\",\"mount\":{\"x\":0.1,\"y\":0,\"z\":0.2},\"pipelines\":{";
        String colour = message(camera(head + "\"0\":{\"type\":\"colour\",\"fieldMap\":\"none\"}}"));
        assertTrue(colour.contains("cameras.front") && colour.contains("pipeline0.type") && colour.contains("apriltag"), colour);

        String slot = message(camera(head + "\"first\":{\"type\":\"apriltag\",\"fieldMap\":\"none\"}}"));
        assertTrue(slot.contains("pipelines.first") && slot.contains("a number"), slot);

        String resolution = message(camera(head + "\"1\":{\"type\":\"apriltag\",\"fieldMap\":\"none\",\"resolution\":\"1920x1080\"}}"));
        assertTrue(resolution.contains("pipeline1.resolution") && resolution.contains("1280x960"), resolution);

        String ids = message(camera(head + "\"2\":{\"type\":\"apriltag\",\"fieldMap\":\"none\",\"ids\":[20,700]}}"));
        assertTrue(ids.contains("pipeline2.ids") && ids.contains("700"), ids);

        String pitch = message(camera("\"type\":\"limelight-3a\",\"mount\":{\"x\":0,\"y\":0,\"z\":0,\"pitchDegrees\":120}"));
        assertTrue(pitch.contains("mount.pitchDegrees"), pitch);
    }

    @Test
    void digitalAndAnalogPinsAreOptionalAndAPinWithoutASourceIsStillListed() {
        RobotProfile bare = RobotProfileLoader.parse(MINIMAL, "minimal.json");
        assertTrue(bare.digitals().isEmpty() && bare.analogs().isEmpty());

        RobotProfile profile = RobotProfileLoader.parse(pins(
                "\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"slide\",\"counts\":1200.5,\"closedWhen\":\"ABOVE\"}},"
                        + "\"beam\":{\"hub\":173,\"channel\":7,\"source\":{\"type\":\"constant\",\"level\":\"LOW\"}},"
                        + "\"idle\":{\"channel\":3}},"
                        + "\"analogs\":{\"pot\":{\"channel\":1,\"source\":{\"type\":\"encoder\",\"motor\":\"slide\",\"voltsAtZero\":0.5,\"voltsPerCount\":-0.0001}},"
                        + "\"ref\":{\"channel\":0,\"source\":{\"type\":\"constant\",\"volts\":1.65}}}"), "pins.json");

        RobotProfile.DigitalSource limit = profile.digitals().get("limit").source();
        assertEquals("encoder", limit.type());
        assertEquals("slide", limit.motor());
        assertEquals(1200.5, limit.counts());
        assertEquals("ABOVE", limit.closedWhen());
        assertNull(profile.digitals().get("limit").hub(), "the parent hub when the hub is not said");
        assertEquals("LOW", profile.digitals().get("beam").source().level());
        assertNull(profile.digitals().get("idle").source());
        assertEquals(-0.0001, profile.analogs().get("pot").source().voltsPerCount());
        assertEquals(1.65, profile.analogs().get("ref").source().volts());
        assertEquals(List.of("limit", "beam", "idle"), List.copyOf(profile.digitals().keySet()), "in the order of the file");
    }

    @Test
    void aPinMustBeOnAHubAndAChannelThatExistAndTwoNamesCannotShareAChannel() {
        String hub = message(pins("\"digitals\":{\"limit\":{\"hub\":9,\"channel\":0}}"));
        assertTrue(hub.contains("digitals.limit") && hub.contains("hub 9 is not one of the profile's hubs"), hub);

        String digital = message(pins("\"digitals\":{\"limit\":{\"channel\":8}}"));
        assertTrue(digital.contains("digitals.limit") && digital.contains("channel 8 does not exist") && digital.contains("0..7"), digital);
        String analog = message(pins("\"analogs\":{\"pot\":{\"channel\":4}}"));
        assertTrue(analog.contains("analogs.pot") && analog.contains("channel 4 does not exist") && analog.contains("0..3"), analog);
        String negative = message(pins("\"analogs\":{\"pot\":{\"channel\":-1}}"));
        assertTrue(negative.contains("analogs.pot") && negative.contains("does not exist"), negative);

        String shared = message(pins("\"digitals\":{\"a\":{\"channel\":2},\"b\":{\"channel\":2}}"));
        assertTrue(shared.contains("digitals.b") && shared.contains("channel 2 of hub 173 is already used"), shared);
        String missing = message(pins("\"digitals\":{\"limit\":{}}"));
        assertTrue(missing.contains("channel"), missing);

        RobotProfile separate = RobotProfileLoader.parse(pins(
                "\"digitals\":{\"a\":{\"channel\":2}},\"analogs\":{\"b\":{\"channel\":2}}"), "separate.json");
        assertEquals(1, separate.digitals().size(), "a digital and an analog pin may have the same number");
        assertEquals(1, separate.analogs().size());
    }

    @Test
    void aSourceNamesItsKindAndExactlyTheSettingsOfThatKindAndTheMessageSaysWhichPlaceInTheFile() {
        String kind = message(pins("\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"pot\"}}}"));
        assertTrue(kind.contains("digitals.limit.source.type") && kind.contains("'pot'") && kind.contains("constant or encoder"), kind);
        String noType = message(pins("\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"level\":\"LOW\"}}}"));
        assertTrue(noType.contains("type"), noType);

        String foreign = message(pins("\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"constant\",\"level\":\"LOW\",\"counts\":5}}}"));
        assertTrue(foreign.contains("digitals.limit.source") && foreign.contains("'constant'") && foreign.contains("not 'counts'"), foreign);
        String missingLevel = message(pins("\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"constant\"}}}"));
        assertTrue(missingLevel.contains("digitals.limit.source") && missingLevel.contains("needs 'level'"), missingLevel);
        String missingSide = message(pins(
                "\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"slide\",\"counts\":10}}}"));
        assertTrue(missingSide.contains("digitals.limit.source") && missingSide.contains("needs 'closedWhen'"), missingSide);
        String analogForeign = message(pins("\"analogs\":{\"pot\":{\"channel\":0,\"source\":{\"type\":\"constant\",\"volts\":1,\"motor\":\"slide\"}}}"));
        assertTrue(analogForeign.contains("analogs.pot.source") && analogForeign.contains("not 'motor'"), analogForeign);
        String analogMissing = message(pins("\"analogs\":{\"pot\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"slide\",\"voltsAtZero\":1}}}"));
        assertTrue(analogMissing.contains("analogs.pot.source") && analogMissing.contains("needs 'voltsPerCount'"), analogMissing);
    }

    @Test
    void theValuesOfASourceAreCheckedAgainstWhatTheyMean() {
        String level = message(pins("\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"constant\",\"level\":\"high\"}}}"));
        assertTrue(level.contains("digitals.limit.source.level") && level.contains("'high'") && level.contains("HIGH or LOW"), level);
        String side = message(pins(
                "\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"slide\",\"counts\":1,\"closedWhen\":\"UP\"}}}"));
        assertTrue(side.contains("digitals.limit.source.closedWhen") && side.contains("ABOVE") && side.contains("BELOW"), side);
        String motor = message(pins(
                "\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"slid\",\"counts\":1,\"closedWhen\":\"BELOW\"}}}"));
        assertTrue(motor.contains("digitals.limit.source.motor") && motor.contains("'slid'") && motor.contains("[slide]"), motor);

        String tooHigh = message(pins("\"analogs\":{\"pot\":{\"channel\":0,\"source\":{\"type\":\"constant\",\"volts\":3.4}}}"));
        assertTrue(tooHigh.contains("analogs.pot.source.volts") && tooHigh.contains("0 to 3.3 V"), tooHigh);
        String negative = message(pins(
                "\"analogs\":{\"pot\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"slide\",\"voltsAtZero\":-0.1,\"voltsPerCount\":0.001}}}"));
        assertTrue(negative.contains("analogs.pot.source.voltsAtZero") && negative.contains("0 to 3.3 V"), negative);
        String noMotor = message(pins(
                "\"analogs\":{\"pot\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"arm\",\"voltsAtZero\":1,\"voltsPerCount\":0.001}}}"));
        assertTrue(noMotor.contains("analogs.pot.source.motor") && noMotor.contains("'arm'"), noMotor);
    }

    @Test
    void aTypoInASourceIsAnErrorThatNamesTheFileAndThePlaceLikeEveryOtherSection() {
        String typo = message(pins("\"digitals\":{\"limit\":{\"channel\":0,\"source\":{\"type\":\"encoder\",\"motor\":\"slide\",\"count\":1}}}"));

        assertTrue(typo.startsWith("test.json: "), typo);
        assertTrue(typo.contains("count") && typo.contains("digitals") && typo.contains("limit") && typo.contains("source"), typo);
        String analog = message(pins("\"analogs\":{\"pot\":{\"channel\":0,\"sorce\":{\"type\":\"constant\",\"volts\":1}}}"));
        assertTrue(analog.contains("sorce") && analog.contains("analogs") && analog.contains("pot"), analog);
    }

    /** A robot with one shaft motor, 'slide', and whatever else the test adds. */
    private static String pins(String sections) {
        return "{\"schema\":1,\"name\":\"x\",\"motors\":{\"slide\":{\"port\":0,\"shaft\":{\"loadInertiaKgM2\":0.001}}}," + sections + "}";
    }

    private static String camera(String body) {
        return "{\"schema\":1,\"name\":\"x\",\"cameras\":{\"front\":{" + body + "}}}";
    }

    private static String wheel(String name, int port, String position, int polarity) {
        return "\"" + name + "\":{\"port\":" + port + ",\"wheel\":{\"position\":\"" + position + "\",\"polarity\":" + polarity + "}}";
    }

    private static String allWheels(int polarity) {
        return wheel("a", 0, "FRONT_LEFT", polarity) + "," + wheel("b", 1, "FRONT_RIGHT", polarity) + ","
                + wheel("c", 2, "REAR_LEFT", polarity) + "," + wheel("d", 3, "REAR_RIGHT", polarity);
    }
}
