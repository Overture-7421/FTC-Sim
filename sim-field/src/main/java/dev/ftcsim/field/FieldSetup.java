package dev.ftcsim.field;

import dev.ftcsim.contracts.field.Alliance;

/**
 * What a run of a field may depend on.
 *
 * @param seed              fixes every random choice the field makes, such as which pattern a game picks; equal seeds
 *                          give equal fields
 * @param alliance          the alliance the robot plays for
 * @param robotLengthMeters how far the robot's outline reaches along its heading, for rules that ask whether the robot
 *                          is over a line or inside a zone
 * @param robotWidthMeters  how far the robot's outline reaches across its heading
 */
public record FieldSetup(long seed, Alliance alliance, double robotLengthMeters, double robotWidthMeters) {

    /** The largest robot that fits the 18 inch starting cube of FIRST Tech Challenge. */
    public static final double MAX_START_SIZE_METERS = 0.4572;

    /** A setup for a robot of the largest starting size. */
    public FieldSetup(long seed, Alliance alliance) {
        this(seed, alliance, MAX_START_SIZE_METERS, MAX_START_SIZE_METERS);
    }
}
