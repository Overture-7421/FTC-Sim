package dev.ftcsim.contracts.field;

/**
 * An AprilTag on the field.
 *
 * @param id           the tag's id in the tag family of the game
 * @param sizeMeters   side length of the printed square
 * @param hostEntityId the body the tag is stuck on, or -1 when it is fixed to the field
 * @param local        where the tag is, relative to the host body or the field frame; the tag's +Z points out of
 *                     its face
 */
public record FieldTag(int id, double sizeMeters, int hostEntityId, Pose3 local) {}
