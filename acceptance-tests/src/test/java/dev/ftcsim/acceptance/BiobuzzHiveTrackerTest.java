package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.scenarios.opmodes.BiobuzzHive;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive.Alliance;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive.Tilt;
import dev.ftcsim.scenarios.opmodes.BiobuzzHiveTracker;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Pose;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Sighting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The tracker that tells from the tags' height which way a HIVE is tipped, on synthetic frames. */
class BiobuzzHiveTrackerTest {
    /** The red HIVE's audience tags as seen from the audience side, frame {@code n} (every frame of a real camera has its own noise). */
    private static List<Sighting> frame(Map<Integer, BiobuzzHive.Tag> tags, int n) {
        List<Sighting> exact = BiobuzzSightings.cluster(tags, new Pose(1.4, -1.0, Math.atan2(-0.32, -1.0)), 0.0, 34, 35, 36, 37);
        List<Sighting> noisy = new ArrayList<>();
        for (Sighting tag : exact) {
            noisy.add(new Sighting(tag.id(), tag.x(), tag.y() + n * 1e-4, tag.z()));
        }
        return noisy;
    }

    @Test
    void theHiveIsAtItsStartUntilSeveralFramesInARowShowTheOtherTilt() {
        BiobuzzHiveTracker tracker = new BiobuzzHiveTracker(Alliance.RED);
        assertEquals(Tilt.AUDIENCE_UP, tracker.tilt(), "the red HIVE starts with its audience CELL up");
        Map<Integer, BiobuzzHive.Tag> start = BiobuzzHive.tags(Alliance.RED, Tilt.AUDIENCE_UP);
        Map<Integer, BiobuzzHive.Tag> tipped = BiobuzzHive.tags(Alliance.RED, Tilt.SCORING_UP);

        for (int n = 0; n < 20; n++) {
            assertFalse(tracker.update(frame(start, n)));
        }
        for (int n = 0; n < BiobuzzHiveTracker.FRAMES_TO_SWITCH - 1; n++) {
            assertFalse(tracker.update(frame(tipped, n)), "frame " + n + " is not enough");
        }
        assertEquals(Tilt.AUDIENCE_UP, tracker.tilt());
        assertTrue(tracker.update(frame(tipped, 99)));
        assertEquals(Tilt.SCORING_UP, tracker.tilt(), "its scoring CELL faces up now");
        assertFalse(tracker.update(frame(tipped, 100)), "and it stays so");
    }

    @Test
    void aFrameThatAgreesWithTheCurrentTiltStartsTheCountAgain() {
        BiobuzzHiveTracker tracker = new BiobuzzHiveTracker(Alliance.RED);
        Map<Integer, BiobuzzHive.Tag> start = BiobuzzHive.tags(Alliance.RED, Tilt.AUDIENCE_UP);
        Map<Integer, BiobuzzHive.Tag> tipped = BiobuzzHive.tags(Alliance.RED, Tilt.SCORING_UP);
        for (int round = 0; round < 3; round++) {
            for (int n = 0; n < BiobuzzHiveTracker.FRAMES_TO_SWITCH - 1; n++) {
                tracker.update(frame(tipped, round * 10 + n));
            }
            tracker.update(frame(start, round));
        }
        assertEquals(Tilt.AUDIENCE_UP, tracker.tilt(), "a misread frame now and then does not turn the turret");
    }

    @Test
    void theSameFrameAgainIsNotAnotherVoteBecauseTheLoopIsFasterThanTheCamera() {
        BiobuzzHiveTracker tracker = new BiobuzzHiveTracker(Alliance.RED);
        List<Sighting> one = frame(BiobuzzHive.tags(Alliance.RED, Tilt.SCORING_UP), 0);
        for (int i = 0; i < 50; i++) {
            assertFalse(tracker.update(one));
        }
        assertEquals(Tilt.AUDIENCE_UP, tracker.tilt());
    }

    @Test
    void tagsOfAnArmHalfWayBetweenItsStopsVoteForNeitherTilt() {
        // A HIVE swinging through level has its tags about 1.08 m up: 18 cm from either tilt.
        Map<Integer, BiobuzzHive.Tag> level = new LinkedHashMap<>();
        for (int id = 34; id <= 37; id++) {
            BiobuzzHive.Tag at = BiobuzzHive.TAGS.get(id);
            level.put(id, new BiobuzzHive.Tag(at.x(), at.y(), 1.08));
        }
        BiobuzzHiveTracker tracker = new BiobuzzHiveTracker(Alliance.RED);
        for (int n = 0; n < 30; n++) {
            assertFalse(tracker.update(frame(level, n)));
        }
        assertEquals(Tilt.AUDIENCE_UP, tracker.tilt());
    }

    @Test
    void onlyTheTagsOfTheOwnAlliancesHiveCount() {
        BiobuzzHiveTracker tracker = new BiobuzzHiveTracker(Alliance.RED);
        // The blue HIVE tipped, seen from its scoring side, with the red tracker looking.
        Map<Integer, BiobuzzHive.Tag> blueTipped = BiobuzzHive.tags(Alliance.BLUE, Tilt.AUDIENCE_UP);
        for (int n = 0; n < 30; n++) {
            assertFalse(tracker.update(BiobuzzSightings.cluster(blueTipped, new Pose(-1.4 + n * 1e-3, 1.0, Math.atan2(0.32, 1.0)), 0.0, 38, 39, 40, 41)));
        }
        assertEquals(Tilt.AUDIENCE_UP, tracker.tilt());

        BiobuzzHiveTracker blue = new BiobuzzHiveTracker(Alliance.BLUE);
        assertEquals(Tilt.SCORING_UP, blue.tilt(), "the blue HIVE starts with its scoring CELL up");
    }

    @Test
    void theTagMapForATiltPutsTheTagsOfTheCellThatIsUpHighAndTheOtherLow() {
        // Red: tags 34 to 37 on the audience side and 30 to 33 on the scoring side; blue: 38 to 41 and 42 to 45.
        int[][] audienceAndScoringTag = {{34, 30}, {38, 42}};
        for (Alliance alliance : Alliance.values()) {
            int audience = audienceAndScoringTag[alliance.ordinal()][0];
            int scoring = audienceAndScoringTag[alliance.ordinal()][1];
            Map<Integer, BiobuzzHive.Tag> audienceUp = BiobuzzHive.tags(alliance, Tilt.AUDIENCE_UP);
            Map<Integer, BiobuzzHive.Tag> scoringUp = BiobuzzHive.tags(alliance, Tilt.SCORING_UP);
            assertTrue(audienceUp.get(audience).z() > 1.2 && audienceUp.get(scoring).z() < 0.95, alliance + " with the audience end up");
            assertTrue(scoringUp.get(scoring).z() > 1.2 && scoringUp.get(audience).z() < 0.95, alliance + " with the scoring end up");
        }
    }
}
