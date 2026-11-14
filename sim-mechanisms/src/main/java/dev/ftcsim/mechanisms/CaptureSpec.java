package dev.ftcsim.mechanisms;

import java.util.List;

/**
 * An intake: while every condition holds, a free piece whose centre is inside the volume and whose size the intake
 * can grip is taken into the storage if the storage has room at its entrance.
 *
 * @param storage       the name of the {@link StorageSpec} the pieces go to
 * @param volume        where a piece must be, in the robot frame
 * @param active        what the motors must be doing for the zone to take anything in
 * @param minDiameterM  the smallest piece the intake grips, in metres; a smaller piece slips through
 * @param maxDiameterM  the largest piece that fits the intake, in metres; a larger piece does not go in
 */
public record CaptureSpec(
        String name, String storage, Volume volume, List<Condition> active, double minDiameterM, double maxDiameterM) {

    public CaptureSpec {
        active = List.copyOf(active);
        if (!(minDiameterM >= 0.0) || !(maxDiameterM > minDiameterM)) {
            throw new IllegalArgumentException(
                    "intake '" + name + "': the piece diameters it takes must run from at least 0 to more than the smallest, not "
                            + minDiameterM + " to " + maxDiameterM);
        }
    }

    /** An intake that takes a piece of any size. */
    public CaptureSpec(String name, String storage, Volume volume, List<Condition> active) {
        this(name, storage, volume, active, 0.0, Double.POSITIVE_INFINITY);
    }

    boolean takes(double diameterM) {
        return diameterM >= minDiameterM && diameterM <= maxDiameterM;
    }
}
