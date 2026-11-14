package dev.ftcsim.contracts.field;

/** Fixed field geometry that never moves: a wall, a ramp, a goal; drawn and collided with as given. */
public record Scenery(String name, FieldPart part, Pose3 pose) {}
