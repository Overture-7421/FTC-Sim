package dev.ftcsim.contracts.session;

import dev.ftcsim.contracts.field.Alliance;

/**
 * What a person asks for when they open a run. Names, not objects, so that the request can cross a process
 * boundary; the simulator resolves them.
 *
 * @param program      the path of a prepared robot program (a practice package or an imported project)
 * @param field        {@code decode}, {@code biobuzz}, or {@code none} for a bare floor
 * @param alliance     the alliance the robot plays for
 * @param seed         fixes every random choice of the field; equal seeds give equal fields
 * @param startX       where the robot starts, field frame, metres
 * @param startY       where the robot starts, field frame, metres
 * @param startHeading which way it faces, counter-clockwise from the field's +X, radians
 * @param speed        simulated seconds per wall-clock second; 1 is real time
 */
public record SessionRequest(
        String program,
        String field,
        Alliance alliance,
        long seed,
        double startX,
        double startY,
        double startHeading,
        double speed) {}
