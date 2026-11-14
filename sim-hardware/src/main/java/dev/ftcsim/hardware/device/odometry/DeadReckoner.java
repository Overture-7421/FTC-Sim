package dev.ftcsim.hardware.device.odometry;

/**
 * Two-wheel dead reckoning as an odometry chip does it: the distances rolled by a forward-measuring pod and a
 * sideways-measuring pod, and the change of heading from the chip's gyro, move a tracking point over the floor. The
 * move is the pose exponential, which follows the arc the robot drove instead of cutting its chord.
 *
 * <p>Units are the caller's, as long as they are consistent; the chips work in millimetres and radians. The pose is
 * that of the tracking point, which is wherever the chip was told to report, and the offsets say where the pods are
 * relative to it.
 */
public final class DeadReckoner {

    private static final double SMALL_ANGLE = 1e-4;

    private double x;
    private double y;
    private double heading;

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double heading() {
        return heading;
    }

    public void setX(double x) {
        this.x = x;
    }

    public void setY(double y) {
        this.y = y;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public void setPose(double x, double y, double heading) {
        this.x = x;
        this.y = y;
        this.heading = heading;
    }

    /**
     * Applies one update of the chip.
     *
     * @param xTravel       how far the forward pod rolled, positive when the robot moved forward
     * @param yTravel       how far the sideways pod rolled, positive when the robot moved left
     * @param headingChange how far the robot turned, positive counter-clockwise
     * @param xPodOffset    how far to the left of the tracking point the forward pod's line is (negative: right)
     * @param yPodOffset    how far in front of the tracking point the sideways pod's line is (negative: behind)
     */
    public void advance(double xTravel, double yTravel, double headingChange, double xPodOffset, double yPodOffset) {
        // A pod off the tracking point also rolls when the robot turns; what is left is the tracking point's own move.
        double forward = xTravel + headingChange * xPodOffset;
        double left = yTravel - headingChange * yPodOffset;
        double sine;
        double bulge;
        if (Math.abs(headingChange) < SMALL_ANGLE) {
            sine = 1.0 - headingChange * headingChange / 6.0;
            bulge = headingChange / 2.0;
        } else {
            sine = StrictMath.sin(headingChange) / headingChange;
            bulge = (1.0 - StrictMath.cos(headingChange)) / headingChange;
        }
        double alongHeading = sine * forward - bulge * left;
        double acrossHeading = bulge * forward + sine * left;
        double cos = StrictMath.cos(heading);
        double sin = StrictMath.sin(heading);
        x += alongHeading * cos - acrossHeading * sin;
        y += alongHeading * sin + acrossHeading * cos;
        heading += headingChange;
    }
}
