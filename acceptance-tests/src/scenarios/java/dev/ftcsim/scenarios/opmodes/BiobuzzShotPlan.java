package dev.ftcsim.scenarios.opmodes;

/**
 * How a BIOBUZZ program decides where to aim and how hard to shoot: the one thing the shooter sequence ({@link BiobuzzHoldShooter})
 * takes from outside. {@link BiobuzzShotSolver#PLAN} works the shot out from the CELL's geometry; {@link BiobuzzTeamTables} reads it
 * from the team's own tables of flywheel speed and hood position against distance.
 *
 * <p>Written for Java 8, like the other files a robot project takes.
 */
public interface BiobuzzShotPlan {
    /**
     * Where to aim and how hard to shoot from {@code pose} at the CELL of {@code alliance}'s HIVE that faces up.
     *
     * @param previousHood the hood position in use, or NaN
     */
    BiobuzzShotSolver.Solution solve(
            BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt, double previousHood);

    /** Degrees left of the robot's front that the turret must point from {@code pose}. */
    double bearingDegrees(BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt);

    /** A flywheel speed to idle at when there is no shot from {@code pose}, in ticks a second; NaN if there is none. */
    double nominalTicks(BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt);
}
