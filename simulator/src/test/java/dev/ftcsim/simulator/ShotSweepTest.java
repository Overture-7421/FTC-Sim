package dev.ftcsim.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.simulator.robot.HornetMapping;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hornet's own shooter table, flywheel speed against distance, played into the DECODE field by the bundled robot
 * profile from stances all over the launch zones. It fails when the launcher's calibration or the field's scoring
 * stops working together: a ball that falls short on the goal's front panel, flies over its backboard or jams in the
 * goal. The bars are well below what the calibration reaches, which docs/robots/hornet-fit.md tells.
 */
class ShotSweepTest {

    private static final int[] SPEEDS = {-6, -5, -4, -3, -2, -1, 1, 2, 3, 4, 5, 6};
    private static final int[] AIMS = {-3, -2, -1, 1, 2, 3};
    private static final double VOLLEY_INTERVAL_SECONDS = 0.3;
    private static final int NEAR_INCHES = 65;
    // A ball that has not been counted and lies above this height rests on something, not on the floor.
    private static final double FLOOR_REST_M = 0.2;
    // The boards stand on the goal's panels, 0.984 m, and reach 1.365 m at the corner; a shot that banks meets them between.
    private static final double BOARD_LOW_M = 0.9;
    private static final double BOARD_HIGH_M = 1.35;
    // The goal's corner of the field: from its end wall to the archway, and from its side wall to the front panel's end.
    private static final double GOAL_END_X = -1.1;
    private static final double GOAL_START_Y = 1.0;

    private static final double MIN_SCORING_SHARE = 0.95;
    private static final double MAX_ALLIANCE_GAP = 0.02;
    private static final double MIN_SPEED_WINDOW_SHARE = 0.90;
    private static final double MIN_AIM_WINDOW_SHARE = 0.85;
    private static final double MIN_COMPLETE_VOLLEY_SHARE = 0.93;

    private static RobotSpec profile;
    private static final Map<Alliance, List<ShotSweep.Result>> RESULTS = new EnumMap<>(Alliance.class);

    @BeforeAll
    static void sweepBothAlliances() throws IOException {
        profile = RobotDesign.decode().spec(HornetMapping.mapping());
        for (Alliance alliance : Alliance.values()) {
            List<ShotSweep.Result> results = ShotSweep.run(profile, ShotSweep.spots(profile, alliance), SPEEDS, AIMS);
            RESULTS.put(alliance, results);
            print(alliance, results);
        }
    }

    private static List<ShotSweep.Result> placed(Alliance alliance) {
        return RESULTS.get(alliance).stream().filter(result -> result.nominal().placed()).toList();
    }

    private static double share(List<ShotSweep.Result> results, java.util.function.Predicate<ShotSweep.Result> test) {
        return results.isEmpty() ? 0 : results.stream().filter(test).count() / (double) results.size();
    }

    private static boolean holdsSpeed(ShotSweep.Result result) {
        return result.nominal().scored() && result.speedWindow()[0] <= -3 && result.speedWindow()[1] >= 3;
    }

    private static boolean holdsAim(ShotSweep.Result result) {
        return result.nominal().scored() && result.aimWindow()[0] <= -3 && result.aimWindow()[1] >= 3;
    }

    @Test
    void everyLegalStanceScoresAtTheTableSpeed() {
        for (Alliance alliance : Alliance.values()) {
            List<ShotSweep.Result> stances = placed(alliance);
            assertTrue(stances.size() >= 90, alliance + " has " + stances.size() + " legal stances");
            List<String> misses = new ArrayList<>();
            for (ShotSweep.Result result : stances) {
                if (!result.nominal().scored()) {
                    misses.add(String.format("%d in %+.0f deg -> rest (%.2f, %.2f, %.2f)", result.spot().inches(),
                            result.spot().bearingDegrees(), result.nominal().restX(), result.nominal().restY(), result.nominal().restZ()));
                }
            }
            assertTrue(1 - misses.size() / (double) stances.size() >= MIN_SCORING_SHARE, alliance + " misses from " + misses);
            for (ShotSweep.Result result : stances) {
                if (result.spot().inches() <= NEAR_INCHES) {
                    assertTrue(result.nominal().scored(), alliance + " near shot from " + result.spot() + " misses");
                }
            }
        }
    }

    @Test
    void bothAlliancesScoreAlike() {
        double blue = share(placed(Alliance.BLUE), result -> result.nominal().scored());
        double red = share(placed(Alliance.RED), result -> result.nominal().scored());
        assertTrue(Math.abs(blue - red) <= MAX_ALLIANCE_GAP, "blue " + blue + " against red " + red);
        assertEquals(placed(Alliance.BLUE).size(), placed(Alliance.RED).size(), "the same stances are legal on both sides");
    }

    @Test
    void aSpeedErrorOfThreePercentAndAnAimErrorOfThreeDegreesStillScore() {
        for (Alliance alliance : Alliance.values()) {
            double speed = share(placed(alliance), ShotSweepTest::holdsSpeed);
            double aim = share(placed(alliance), ShotSweepTest::holdsAim);
            assertTrue(speed >= MIN_SPEED_WINDOW_SHARE, alliance + " holds +-3 % speed from only " + speed + " of its stances");
            assertTrue(aim >= MIN_AIM_WINDOW_SHARE, alliance + " holds +-3 degrees from only " + aim + " of its stances");
        }
    }

    @Test
    void everyShotAtTheTableSpeedBanksOffTheBoardsAndDropsIn() {
        for (Alliance alliance : Alliance.values()) {
            for (ShotSweep.Result result : placed(alliance)) {
                double height = result.nominal().boardHeight();
                assertTrue(height >= BOARD_LOW_M && height <= BOARD_HIGH_M,
                        String.format("%s shot from %s meets the boards at %.2f m", alliance, result.spot(), height));
            }
        }
    }

    @Test
    void noShotAtTheTableSpeedLeavesTheFieldAndNoBallLodgesOnTheGoal() {
        for (Alliance alliance : Alliance.values()) {
            for (ShotSweep.Result result : placed(alliance)) {
                assertTrue(!result.nominal().leftField(), alliance + " shot from " + result.spot() + " left the field");
                for (HornetShot.Outcome shot : result.all()) {
                    assertTrue(!lodgedInTheGoal(shot), String.format("%s shot from %s lodged at (%.2f, %.2f, %.2f)", alliance,
                            result.spot(), shot.restX(), shot.restY(), shot.restZ()));
                }
            }
        }
    }

    /** Not counted, still in the field and resting above the floor within the goal's own corner of the field. */
    private static boolean lodgedInTheGoal(HornetShot.Outcome shot) {
        boolean inTheGoal = shot.restX() < GOAL_END_X && Math.abs(shot.restY()) > GOAL_START_Y;
        return shot.placed() && !shot.scored() && !shot.leftField() && shot.restZ() > FLOOR_REST_M && inTheGoal;
    }

    @Test
    void shotsInThreesAllFindTheirWayToTheRamp() {
        for (Alliance alliance : Alliance.values()) {
            List<ShotSweep.Spot> spots = placed(alliance).stream().map(ShotSweep.Result::spot).toList();
            List<HornetShot.Volley> volleys = ShotSweep.volleys(profile, spots, 3, VOLLEY_INTERVAL_SECONDS);
            long complete = volleys.stream().filter(volley -> volley.counted() == 3).count();
            System.out.println(String.format("SWEEP> %s volleys of three, %.1f s apart: %d of %d complete", alliance,
                    VOLLEY_INTERVAL_SECONDS, complete, volleys.size()));
            assertTrue(complete / (double) volleys.size() >= MIN_COMPLETE_VOLLEY_SHARE, alliance + ": " + complete + " of " + volleys.size());
        }
    }

    @Test
    void aShotIsTheSameWhicheverThreadFiresIt() {
        List<ShotSweep.Result> results = placed(Alliance.BLUE);
        for (int i = 0; i < results.size(); i += 17) {
            ShotSweep.Result result = results.get(i);
            assertEquals(result.nominal(), ShotSweep.shoot(profile, result.spot(), 0, 0), "nominal shot from " + result.spot());
            if (result.bySpeedPercent().containsKey(5)) {
                assertEquals(result.bySpeedPercent().get(5), ShotSweep.shoot(profile, result.spot(), 5, 0), "+5 % from " + result.spot());
            }
        }
    }

    private static void print(Alliance alliance, List<ShotSweep.Result> results) {
        TreeMap<Integer, List<ShotSweep.Result>> byDistance = new TreeMap<>();
        for (ShotSweep.Result result : results) {
            if (result.nominal().placed()) {
                byDistance.computeIfAbsent(result.spot().inches(), key -> new ArrayList<>()).add(result);
            }
        }
        System.out.println("SWEEP> " + alliance + ": distance to the aim point, stances that score, the speed and aim window that "
                + "all of them hold, and where the ball meets the boards");
        for (Map.Entry<Integer, List<ShotSweep.Result>> row : byDistance.entrySet()) {
            int scored = 0;
            int speedLow = -99;
            int speedHigh = 99;
            int aimLow = -99;
            int aimHigh = 99;
            double heightLow = Double.MAX_VALUE;
            double heightHigh = 0;
            for (ShotSweep.Result result : row.getValue()) {
                if (result.nominal().scored()) {
                    scored++;
                }
                speedLow = Math.max(speedLow, result.speedWindow()[0]);
                speedHigh = Math.min(speedHigh, result.speedWindow()[1]);
                aimLow = Math.max(aimLow, result.aimWindow()[0]);
                aimHigh = Math.min(aimHigh, result.aimWindow()[1]);
                if (!Double.isNaN(result.nominal().boardHeight())) {
                    heightLow = Math.min(heightLow, result.nominal().boardHeight());
                    heightHigh = Math.max(heightHigh, result.nominal().boardHeight());
                }
            }
            System.out.println(String.format("SWEEP>   %3d in: %d/%d  speed %+d..%+d %%  aim %+d..%+d deg  board z %.2f..%.2f m", row.getKey(), scored,
                    row.getValue().size(), speedLow, speedHigh, aimLow, aimHigh, heightLow, heightHigh));
        }
        List<ShotSweep.Result> stances = results.stream().filter(result -> result.nominal().placed()).toList();
        System.out.println(String.format("SWEEP> %s: %d of %d stances score; +-3 %% speed holds in %d, +-3 deg aim in %d", alliance,
                stances.stream().filter(result -> result.nominal().scored()).count(), stances.size(),
                stances.stream().filter(ShotSweepTest::holdsSpeed).count(), stances.stream().filter(ShotSweepTest::holdsAim).count()));
    }
}
