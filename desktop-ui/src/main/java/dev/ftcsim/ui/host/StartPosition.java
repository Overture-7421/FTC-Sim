package dev.ftcsim.ui.host;

/**
 * A start position a team named and kept in its project's {@code ftcsim.json}, for one field: where the robot's centre
 * is in the field's frame (metres, the origin at the field's centre) and which way it faces.
 *
 * @param name           what the team calls it, unique among the positions of a field
 * @param field          the id of the field it is for
 * @param x              metres
 * @param y              metres
 * @param headingDegrees degrees, counter-clockwise from the field's +X axis
 */
public record StartPosition(String name, String field, double x, double y, double headingDegrees) {}
