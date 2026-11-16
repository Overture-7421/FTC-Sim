package dev.ftcsim.ui.host;

import dev.ftcsim.contracts.field.Alliance;

/**
 * How a run of a project starts when nobody has chosen otherwise: what its {@code ftcsim.json} says. Every part may be null.
 *
 * @param field     the field's id
 * @param alliance  the alliance
 * @param startPose the name of one of the field's start poses
 */
public record RunDefaults(String field, Alliance alliance, String startPose) {

    /** Nothing is said. */
    public static final RunDefaults NONE = new RunDefaults(null, null, null);
}
