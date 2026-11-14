package dev.ftcsim.game.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.game.decode.Zones.Footprint;
import org.junit.jupiter.api.Test;

class ZonesTest {

    private static final double HALF = 0.2286;

    private static Footprint robot(double x, double y, double yaw) {
        return new Footprint(x, y, yaw, HALF, HALF);
    }

    @Test
    void aRobotCoveringAnyPartOfALaunchLineIsOverIt() {
        assertTrue(Zones.isOverLaunchLine(robot(-0.5, 0.5, 0)), "on the back line");
        assertTrue(Zones.isOverLaunchLine(robot(-0.5, -0.5, 0.3)), "on the other leg of the back line");
        assertTrue(Zones.isOverLaunchLine(robot(1.5, 0, 0)), "in the front launch zone");
        assertTrue(Zones.isOverLaunchLine(robot(1.0, 0.0, 0)), "its edge covers the point of the front line");
        assertTrue(Zones.isOverLaunchLine(robot(-1.45, -1.3, 0)), "the depot of the blue goal is a launch line too");
        assertTrue(Zones.isOverLaunchLine(robot(-1.45, 1.3, 0)), "and so is the depot of the red goal");
        assertFalse(Zones.isOverLaunchLine(robot(0.5, 1.3, 0)));
        assertFalse(Zones.isOverLaunchLine(robot(0.7, 0.0, 0)), "clear of the point of the front line");
        assertFalse(Zones.isOverLaunchLine(robot(-0.5, 1.4, 0.8)));
    }

    @Test
    void aRobotIsFullyPartlyOrNotOnItsBase() {
        assertEquals(2, Zones.baseStatus(robot(0.958, 0.839, 0), Alliance.BLUE));
        assertEquals(1, Zones.baseStatus(robot(1.17, 0.839, 0), Alliance.BLUE));
        assertEquals(1, Zones.baseStatus(robot(0.958, 0.839, Math.PI / 4), Alliance.BLUE), "its corners stick out when turned");
        assertEquals(0, Zones.baseStatus(robot(0.2, 0.839, 0), Alliance.BLUE));
        assertEquals(2, Zones.baseStatus(robot(0.958, -0.838, 0), Alliance.RED));
        assertEquals(0, Zones.baseStatus(robot(0.958, 0.839, 0), Alliance.RED), "the other alliance's base is not its own");
    }

    @Test
    void aBallIsOverTheDepotWhenItsOutlineTouchesTheTape() {
        double radius = Dimensions.ARTIFACT_RADIUS;
        double onTape = Zones.DEPOT_X;
        assertTrue(Zones.isOverDepot(onTape, Zones.DEPOT_Y, radius, Alliance.BLUE));
        assertTrue(Zones.isOverDepot(onTape, -Zones.DEPOT_Y, radius, Alliance.RED));
        assertFalse(Zones.isOverDepot(onTape, -Zones.DEPOT_Y, radius, Alliance.BLUE), "the other goal's depot");
        double away = Dimensions.frontNormal().x() * 0.3;
        assertFalse(Zones.isOverDepot(onTape + away, Zones.DEPOT_Y + Dimensions.frontNormal().y() * 0.3, radius, Alliance.BLUE));
    }
}
