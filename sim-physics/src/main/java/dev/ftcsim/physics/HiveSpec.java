package dev.ftcsim.physics;

/**
 * Geometry and motion of a seesaw tray that rotates about the world Y axis through {@code pivot}.
 *
 * @param lengthM                length along X when level; the tray has end lips at both ends
 * @param widthM                 width along Y; the tray has side rails
 * @param limitRadians           tilt of either end stop; the travel between the stops is twice this
 * @param angularSpeedRadPerSec  speed of the scripted tip between the stops
 */
public record HiveSpec(
        Vector3 pivot, double lengthM, double widthM, double lipHeightM, double limitRadians, double angularSpeedRadPerSec) {}
