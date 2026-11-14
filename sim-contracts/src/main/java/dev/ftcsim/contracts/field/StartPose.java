package dev.ftcsim.contracts.field;

/** A place the robot may be started: the field frame, metres and radians, heading counter-clockwise from +X. */
public record StartPose(String name, double x, double y, double headingRadians) {}
