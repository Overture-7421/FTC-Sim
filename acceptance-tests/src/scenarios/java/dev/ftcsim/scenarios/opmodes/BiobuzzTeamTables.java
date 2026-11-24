package dev.ftcsim.scenarios.opmodes;

/**
 * The shot of the team's own BIOBUZZ code (Overture 23619, {@code LaunchConstants}, {@code Hive}, {@code Hood}, {@code Turret}): the
 * flywheel speed and the hood position come from its tables of distance, in inches, from the turret's pivot to the CELL, and the
 * turret points at the CELL from the pivot. Nothing is worked out from the CELL's geometry. The numbers are the team's, copied as
 * they are; so is its target, which is given in Pedro Pathing's field frame (origin at a corner, X along the red wall).
 *
 * <p>The tables are read as SolversLib's {@code InterpLUT} reads them: a monotone cubic through the points, and an error outside the
 * first and last distance, which here is a shot that is refused.
 *
 * <p>Written for Java 8, like the other files a robot project takes.
 */
public final class BiobuzzTeamTables implements BiobuzzShotPlan {
    /** The plan of the team's code. */
    public static final BiobuzzTeamTables PLAN = new BiobuzzTeamTables();

    /** Distance from the turret's pivot to the CELL, in inches. */
    public static final double[] DISTANCES_IN = {36.0, 60.0, 84.0, 108.0, 132.0, 156.0};
    /** The flywheels' speed, in encoder ticks a second. */
    public static final double[] FLYWHEEL_TICKS = {1690.0, 1830.0, 1990.0, 2150.0, 2310.0, 2460.0};
    /** The team's hood command, -1 to 1; the servo is sent {@code (command + 1) / 2}. */
    public static final double[] HOOD_COMMAND = {-0.120, -0.386, -0.572, -0.710, -0.814, -0.894};

    /** The CELL the team aims at, in Pedro Pathing inches: the HIVE's X for each alliance, the Y of each CELL. */
    static final double RED_X = 59.26;
    static final double BLUE_X = 84.74;
    static final double AUDIENCE_CELL_Y = 57.67;
    static final double SCORING_CELL_Y = 86.33;

    private static final double INCH = 0.0254;
    private static final Interpolation TICKS = new Interpolation(DISTANCES_IN, FLYWHEEL_TICKS);
    private static final Interpolation HOOD = new Interpolation(DISTANCES_IN, HOOD_COMMAND);

    private BiobuzzTeamTables() {}

    /** The CELL the team's code aims at, in this simulator's field frame (metres): Pedro's X along the red wall is +Y, its Y is -X. */
    public static double[] target(BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt) {
        double pedroX = alliance == BiobuzzHive.Alliance.RED ? RED_X : BLUE_X;
        double pedroY = tilt == BiobuzzHive.Tilt.AUDIENCE_UP ? AUDIENCE_CELL_Y : SCORING_CELL_Y;
        return new double[] {(72.0 - pedroY) * INCH, (pedroX - 72.0) * INCH};
    }

    @Override
    public BiobuzzShotSolver.Solution solve(
            BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt, double previousHood) {
        double[] target = target(alliance, tilt);
        double axisX = pose.x() + Math.cos(pose.heading()) * BiobuzzShotSolver.TURRET_AXIS_M;
        double axisY = pose.y() + Math.sin(pose.heading()) * BiobuzzShotSolver.TURRET_AXIS_M;
        double bearing = bearingDegrees(pose, alliance, tilt);
        double metres = Math.hypot(target[0] - axisX, target[1] - axisY);
        double inches = metres / INCH;
        // The pose is a double: a stance exactly on the first or last row of the table is still in it.
        if (Math.abs(inches - DISTANCES_IN[0]) < 1e-6) {
            inches = DISTANCES_IN[0];
        } else if (Math.abs(inches - DISTANCES_IN[DISTANCES_IN.length - 1]) < 1e-6) {
            inches = DISTANCES_IN[DISTANCES_IN.length - 1];
        }
        double[] aim = {target[0], target[1], BiobuzzHive.upCell(alliance, tilt).aimPoint()[2]};
        if (inches < DISTANCES_IN[0] || inches > DISTANCES_IN[DISTANCES_IN.length - 1]) {
            BiobuzzShotSolver.Reason reason = inches < DISTANCES_IN[0]
                    ? BiobuzzShotSolver.Reason.TOO_CLOSE : BiobuzzShotSolver.Reason.OUT_OF_RANGE;
            return new BiobuzzShotSolver.Solution(false, reason, bearing, metres, Double.NaN, Double.NaN, 0.0, 0.0, aim);
        }
        double hood = (HOOD.get(inches) + 1.0) / 2.0;
        double elevation = BiobuzzShotSolver.ELEVATION_BASE_DEGREES + hood * BiobuzzShotSolver.HOOD_DEGREES_PER_POSITION;
        return new BiobuzzShotSolver.Solution(true, BiobuzzShotSolver.Reason.NONE, bearing, metres, elevation, hood, TICKS.get(inches), 0.0, aim);
    }

    @Override
    public double bearingDegrees(BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt) {
        double[] target = target(alliance, tilt);
        double axisX = pose.x() + Math.cos(pose.heading()) * BiobuzzShotSolver.TURRET_AXIS_M;
        double axisY = pose.y() + Math.sin(pose.heading()) * BiobuzzShotSolver.TURRET_AXIS_M;
        double angle = Math.atan2(target[1] - axisY, target[0] - axisX) - pose.heading();
        return Math.toDegrees(Math.atan2(Math.sin(angle), Math.cos(angle)));
    }

    /** The team's table has no idle speed: the shooter is off until the button goes down. The first row is the nearest thing to one. */
    @Override
    public double nominalTicks(BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt) {
        return FLYWHEEL_TICKS[0];
    }

    /** SolversLib's {@code InterpLUT}: a monotone cubic Hermite through the points. */
    static final class Interpolation {
        private final double[] x;
        private final double[] y;
        private final double[] tangent;

        Interpolation(double[] x, double[] y) {
            this.x = x.clone();
            this.y = y.clone();
            int n = x.length;
            double[] secant = new double[n - 1];
            tangent = new double[n];
            for (int i = 0; i < n - 1; i++) {
                secant[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
            }
            tangent[0] = secant[0];
            tangent[n - 1] = secant[n - 2];
            for (int i = 1; i < n - 1; i++) {
                tangent[i] = secant[i - 1] * secant[i] <= 0.0 ? 0.0 : 0.5 * (secant[i - 1] + secant[i]);
            }
            for (int i = 0; i < n - 1; i++) {
                if (secant[i] == 0.0) {
                    tangent[i] = 0.0;
                    tangent[i + 1] = 0.0;
                } else {
                    double a = tangent[i] / secant[i];
                    double b = tangent[i + 1] / secant[i];
                    double h = Math.hypot(a, b);
                    if (h > 3.0) {
                        double t = 3.0 / h;
                        tangent[i] = t * a * secant[i];
                        tangent[i + 1] = t * b * secant[i];
                    }
                }
            }
        }

        double get(double at) {
            int i = 0;
            while (i < x.length - 2 && at > x[i + 1]) {
                i++;
            }
            double width = x[i + 1] - x[i];
            double t = (at - x[i]) / width;
            double t2 = t * t;
            double t3 = t2 * t;
            return (2 * t3 - 3 * t2 + 1) * y[i] + (t3 - 2 * t2 + t) * width * tangent[i]
                    + (-2 * t3 + 3 * t2) * y[i + 1] + (t3 - t2) * width * tangent[i + 1];
        }
    }
}
