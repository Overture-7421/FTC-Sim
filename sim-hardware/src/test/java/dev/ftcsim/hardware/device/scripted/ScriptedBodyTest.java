package dev.ftcsim.hardware.device.scripted;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.contracts.world.BodyPose;
import org.junit.jupiter.api.Test;

class ScriptedBodyTest {

    private static final double EPSILON = 1e-12;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void aStraightMoveGoesAlongTheHeadingItStartedWith() {
        ScriptedBody body = ScriptedBody.startingAt(1, 2, Math.PI / 2).move(2, 0.5, 0, 0).build();

        BodyPose pose = body.at(SECOND);

        assertEquals(1.0, pose.x(), EPSILON);
        assertEquals(2.5, pose.y(), EPSILON);
        assertEquals(Math.PI / 2, pose.yaw(), EPSILON);
        assertEquals(0.0, pose.vx(), EPSILON);
        assertEquals(0.5, pose.vy(), EPSILON);
    }

    @Test
    void aQuarterCircleEndsWhereTheGeometryOfTheCircleSays() {
        // Radius 1 m, counter-clockwise, starting at the origin facing +X: the circle's centre is at (0, 1).
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(Math.PI / 2, 1, 0, 1).build();

        BodyPose pose = body.at(Math.round(Math.PI / 2 * SECOND) - 1);

        assertEquals(1.0, pose.x(), 1e-8);
        assertEquals(1.0, pose.y(), 1e-8);
        assertEquals(Math.PI / 2, pose.yaw(), 1e-8);
        assertEquals(1.0, pose.wz(), EPSILON);
    }

    @Test
    void movesFollowEachOtherFromWhereThePreviousOneEnded() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0)
                .move(1, 1, 0, 0)
                .move(1, 0, 0, Math.PI / 2)
                .move(1, 1, 0, 0)
                .build();

        BodyPose pose = body.at(3 * SECOND);

        assertEquals(1.0, pose.x(), 1e-9);
        assertEquals(1.0, pose.y(), 1e-9);
        assertEquals(Math.PI / 2, pose.yaw(), 1e-9);
    }

    @Test
    void afterTheLastMoveTheChassisStandsStill() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(1, 1, 0, 0).build();

        BodyPose pose = body.at(5 * SECOND);

        assertEquals(1.0, pose.x(), EPSILON);
        assertEquals(0.0, pose.vx(), EPSILON);
        assertEquals(5 * SECOND, pose.timeNanos());
    }

    @Test
    void theSampleDescribesTheInstantTheHostAdvancedTo() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(10, 2, 0, 0).build();

        body.advanceTo(SECOND / 2);

        assertEquals(1.0, body.sample().x(), EPSILON);
    }
}
