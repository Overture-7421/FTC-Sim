package dev.ftcsim.simulator;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Shots at Hornet's own flywheel speeds from stances all over the launch zones, each in a world of its own, so that the
 * calibration of a robot and the DECODE field can be judged without the robot's program.
 */
final class ShotSweep {

    // Hornet's shooter table (Shooter.java): distance to the aim point in inches and the flywheel velocity in ticks per
    // second, to which the program adds OFFSET_TICKS. Between the entries the program interpolates; here it is linear.
    private static final double[] TABLE_INCHES = {35, 45, 55, 65, 75, 85, 95, 105, 115, 125, 135, 145, 155, 160};
    private static final double[] TABLE_TICKS = {880, 860, 900, 920, 970, 1000, 1040, 1080, 1130, 1170, 1210, 1240, 1260, 1280};
    private static final double OFFSET_TICKS = 12;
    private static final double INCH = 0.0254;
    // The aim point is Pedro (8, 136) in, which is 64 in from the end wall and from the side wall of the blue goal.
    private static final double AIM = -64 * INCH;
    private static final double WALL = 1.7945;
    // The small launch triangle against the audience wall: its apex, and half of its base on that wall.
    private static final double SMALL_APEX_X = 1.1957;
    private static final double SMALL_HALF_BASE = 0.59;

    /** The bearings, in degrees off the line from the goal's corner to the centre of the field, that stances are taken from. */
    private static final double BEARING_STEP = 2.5;
    private static final double BEARING_LIMIT = 80;
    private static final int STANCES_PER_DISTANCE = 5;

    /** A stance of the robot for a shot at a distance from the aim point and a bearing off the line from the goal's corner to the centre of the field. */
    record Spot(Alliance alliance, int inches, double bearingDegrees, HornetShot.Stance stance) {}

    /** What one spot gave at the table speed and, if asked for, at other speeds and aims. */
    record Result(
            Spot spot, HornetShot.Outcome nominal, TreeMap<Integer, HornetShot.Outcome> bySpeedPercent,
            TreeMap<Integer, HornetShot.Outcome> byAimDegrees) {

        /** How far the speed can be off, in whole percent, in each direction before the ball stops scoring; both zero if even the table speed misses. */
        int[] speedWindow() {
            return window(bySpeedPercent, nominal.scored());
        }

        int[] aimWindow() {
            return window(byAimDegrees, nominal.scored());
        }

        /** The nominal shot and every other shot taken from this spot. */
        List<HornetShot.Outcome> all() {
            List<HornetShot.Outcome> all = new ArrayList<>();
            all.add(nominal);
            all.addAll(bySpeedPercent.values());
            all.addAll(byAimDegrees.values());
            return all;
        }

        private static int[] window(TreeMap<Integer, HornetShot.Outcome> shots, boolean nominalScores) {
            if (!nominalScores) {
                return new int[] {0, 0};
            }
            int low = 0;
            while (shots.containsKey(low - 1) && shots.get(low - 1).scored()) {
                low--;
            }
            int high = 0;
            while (shots.containsKey(high + 1) && shots.get(high + 1).scored()) {
                high++;
            }
            return new int[] {low, high};
        }
    }

    private ShotSweep() {}

    /** Hornet's flywheel velocity for a distance in inches, the offset included. */
    static double ticksPerSecond(double inches) {
        int i = 0;
        while (i < TABLE_INCHES.length - 2 && inches > TABLE_INCHES[i + 1]) {
            i++;
        }
        double along = (inches - TABLE_INCHES[i]) / (TABLE_INCHES[i + 1] - TABLE_INCHES[i]);
        along = Math.max(0, Math.min(1, along));
        return TABLE_TICKS[i] + along * (TABLE_TICKS[i + 1] - TABLE_TICKS[i]) + OFFSET_TICKS;
    }

    /**
     * For every distance from 35 to 160 in, up to five stances spread over the bearings at which the robot fits in the field
     * and has some part of it over a launch zone.
     */
    static List<Spot> spots(RobotSpec profile, Alliance alliance) {
        MecanumConfig chassis = profile.chassis();
        List<Spot> spots = new ArrayList<>();
        for (int inches = 35; inches <= 160; inches += 5) {
            List<Spot> legal = new ArrayList<>();
            for (double bearing = -BEARING_LIMIT; bearing <= BEARING_LIMIT; bearing += BEARING_STEP) {
                Spot spot = spot(alliance, inches, bearing);
                if (fitsInField(spot.stance(), chassis) && touchesLaunchZone(spot.stance(), chassis)) {
                    legal.add(spot);
                }
            }
            int count = Math.min(STANCES_PER_DISTANCE, legal.size());
            for (int i = 0; i < count; i++) {
                spots.add(legal.get(count == 1 ? 0 : (int) Math.round(i * (legal.size() - 1.0) / (count - 1))));
            }
        }
        return spots;
    }

    /** The stance at a distance from the aim point and a bearing; blue is the original and red its mirror image. */
    static Spot spot(Alliance alliance, int inches, double bearingDegrees) {
        double angle = Math.toRadians(45 + bearingDegrees);
        double x = AIM + inches * INCH * Math.cos(angle);
        double y = AIM + inches * INCH * Math.sin(angle);
        double heading = Math.atan2(AIM - y, AIM - x);
        if (alliance == Alliance.RED) {
            y = -y;
            heading = -heading;
        }
        return new Spot(alliance, inches, bearingDegrees, new HornetShot.Stance(x, y, heading));
    }

    private static boolean inLaunchZone(double x, double y) {
        boolean big = x <= 0 && Math.abs(y) <= -x;
        boolean small = x >= SMALL_APEX_X && Math.abs(y) <= (x - SMALL_APEX_X) * SMALL_HALF_BASE / (WALL - SMALL_APEX_X);
        return big || small;
    }

    private static boolean touchesLaunchZone(HornetShot.Stance stance, MecanumConfig chassis) {
        if (inLaunchZone(stance.x(), stance.y())) {
            return true;
        }
        for (double[] corner : corners(stance, chassis)) {
            if (inLaunchZone(corner[0], corner[1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean fitsInField(HornetShot.Stance stance, MecanumConfig chassis) {
        for (double[] corner : corners(stance, chassis)) {
            if (Math.abs(corner[0]) > WALL - 0.01 || Math.abs(corner[1]) > WALL - 0.01) {
                return false;
            }
        }
        return true;
    }

    private static double[][] corners(HornetShot.Stance stance, MecanumConfig chassis) {
        double c = Math.cos(stance.heading());
        double s = Math.sin(stance.heading());
        double[][] corners = new double[4][];
        for (int i = 0; i < 4; i++) {
            double along = (i < 2 ? -0.5 : 0.5) * chassis.lengthM();
            double across = (i % 2 == 0 ? -0.5 : 0.5) * chassis.widthM();
            corners[i] = new double[] {stance.x() + along * c - across * s, stance.y() + along * s + across * c};
        }
        return corners;
    }

    /** One shot from a spot, with the flywheels off the table by a percentage and the robot turned by some degrees. */
    static HornetShot.Outcome shoot(RobotSpec profile, Spot spot, double speedPercent, double aimDegrees) {
        HornetShot.Stance stance = spot.stance();
        double turn = Math.toRadians(aimDegrees) * (spot.alliance() == Alliance.RED ? -1 : 1);
        return HornetShot.fire(
                profile,
                spot.alliance(),
                new HornetShot.Stance(stance.x(), stance.y(), stance.heading() + turn),
                ticksPerSecond(spot.inches()) * (1 + speedPercent / 100));
    }

    /**
     * Shoots from every spot at the table speed, and at the given speed and aim offsets, on as many threads as there are
     * cores; the results come in the order of the spots whatever the threads did.
     *
     * @param speedPercents offsets of the flywheel speed to try besides zero, in whole percent
     * @param aimDegrees    offsets of the heading to try besides zero, in whole degrees
     */
    static List<Result> run(RobotSpec profile, List<Spot> spots, int[] speedPercents, int[] aimDegrees) {
        List<Callable<Result>> tasks = new ArrayList<>();
        for (Spot spot : spots) {
            tasks.add(() -> {
                HornetShot.Outcome nominal = shoot(profile, spot, 0, 0);
                TreeMap<Integer, HornetShot.Outcome> bySpeed = new TreeMap<>();
                TreeMap<Integer, HornetShot.Outcome> byAim = new TreeMap<>();
                if (nominal.placed()) {
                    for (int percent : speedPercents) {
                        bySpeed.put(percent, shoot(profile, spot, percent, 0));
                    }
                    for (int degrees : aimDegrees) {
                        byAim.put(degrees, shoot(profile, spot, 0, degrees));
                    }
                }
                return new Result(spot, nominal, bySpeed, byAim);
            });
        }
        return inParallel(profile, tasks);
    }

    /** Three balls from every spot, {@code intervalSeconds} apart, each at the table speed of its spot. */
    static List<HornetShot.Volley> volleys(RobotSpec profile, List<Spot> spots, int balls, double intervalSeconds) {
        List<Callable<HornetShot.Volley>> tasks = new ArrayList<>();
        for (Spot spot : spots) {
            tasks.add(() -> HornetShot.volley(
                    profile, spot.alliance(), spot.stance(), ticksPerSecond(spot.inches()), balls, intervalSeconds, 6));
        }
        return inParallel(profile, tasks);
    }

    private static <T> List<T> inParallel(RobotSpec profile, List<Callable<T>> tasks) {
        // The first world loads the engine; do that before the threads race for it.
        HornetShot.fire(profile, Alliance.BLUE, spot(Alliance.BLUE, 75, 20).stance(), ticksPerSecond(75));
        // Each world holds a 32 MiB temp allocator of the engine's, so the number of threads is bounded.
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException(failed.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
