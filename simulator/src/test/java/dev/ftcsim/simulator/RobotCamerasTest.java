package dev.ftcsim.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.hardware.camera.CameraRack;
import dev.ftcsim.hardware.device.DeviceHost;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.simulator.profile.ProfileException;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a host gets from the profile's cameras: the model the profile describes, plugged in under its name. */
class RobotCamerasTest {
    private static final long STEP_NANOS = 4_166_667L;

    /** An upright tag two metres ahead of a robot at the origin, its face turned back at the robot. */
    private static final FieldTag TAG = new FieldTag(20, 0.1651, -1, new Pose3(2.0, 0.0, 0.45, -0.5, -0.5, 0.5, 0.5));

    private static RobotProfile hornet() throws IOException {
        return RobotProfileLoader.loadBundled("hornet-2026.json");
    }

    /** A host with the tag in its field and in the map of the game, a robot standing still at the origin, and a clock. */
    private static final class Host {
        final long[] clock = {0};
        final ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).stay(30).build();
        final DeviceHost host = new DeviceHost(body, (origin, direction, maxMeters) -> null, () -> clock[0]);

        Host(boolean withDecodeMap) {
            host.provideFieldTags(List.of(TAG));
            if (withDecodeMap) {
                host.provideGameMap("decode", List.of(TAG));
            }
        }

        void runFor(long nanos) {
            long end = clock[0] + nanos;
            while (clock[0] < end) {
                clock[0] += STEP_NANOS;
                body.advanceTo(clock[0]);
                host.step(clock[0]);
            }
        }
    }

    private static JsonNode results(CameraRack rack, String camera, long nowNanos) throws IOException {
        CameraWire.Reply reply = rack.get(camera, "/results", nowNanos);
        assertTrue(reply.ok(), "GET /results answered " + reply.status());
        return new ObjectMapper().readTree(reply.body());
    }

    @Test
    void theProfilesCameraIsPluggedInUnderItsNameAndSeesTheTagThroughItsOwnMountAndMap() throws Exception {
        Host world = new Host(true);

        CameraRack rack = RobotCameras.build(hornet(), world.host, 1);
        world.runFor(300_000_000L);
        JsonNode results = results(rack, "limelight", world.clock[0]);

        assertEquals(List.of("limelight"), rack.names());
        assertTrue(rack.connect("limelight"));
        assertEquals(1, results.get("v").asInt());
        assertEquals(20, results.get("Fiducial").get(0).get("fID").asInt());
        assertEquals(1, results.get("botpose_tagcount").asInt());
        // The camera estimates where the robot is from the one tag: the origin, facing +X.
        assertEquals(0.0, results.get("botpose").get(0).asDouble(), 0.1);
        assertEquals(0.0, results.get("botpose").get(1).asDouble(), 0.1);
        assertEquals(0.0, results.get("botpose").get(5).asDouble(), 5.0);
        assertEquals(List.of(), rack.problems());
    }

    @Test
    void aCameraTheProfileDoesNotListIsUnpluggedAndTheRackSaysSo() throws Exception {
        CameraRack rack = RobotCameras.build(hornet(), new Host(true).host, 1);

        assertFalse(rack.connect("webcam"));
        assertFalse(rack.get("webcam", "/results", 0).reachable());
        assertEquals(1, rack.problems().size());
        assertTrue(rack.problems().get(0).contains("\"webcam\"") && rack.problems().get(0).contains("limelight"), rack.problems().toString());
    }

    @Test
    void aMapTheHostCannotSupplyIsAProfileErrorThatNamesTheCameraAndTheMap() throws Exception {
        ProfileException failure = assertThrows(ProfileException.class, () -> RobotCameras.build(hornet(), new Host(false).host, 1));

        assertTrue(failure.getMessage().contains("cameras.limelight") && failure.getMessage().contains("decode"), failure.getMessage());
    }

    @Test
    void theSeedFixesTheNoiseOfTheImage() throws Exception {
        String[] replies = new String[3];
        long[] seeds = {7, 7, 8};
        for (int i = 0; i < seeds.length; i++) {
            Host world = new Host(true);
            CameraRack rack = RobotCameras.build(hornet(), world.host, seeds[i]);
            world.runFor(300_000_000L);
            replies[i] = rack.get("limelight", "/results", world.clock[0]).body();
        }

        assertEquals(replies[0], replies[1], "the same seed gives the same bytes");
        assertNotEquals(replies[0], replies[2], "another seed gives other noise");
    }
}
