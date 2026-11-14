package dev.ftcsim.contracts.field;

/**
 * One solid of something that is drawn: its shape, where it sits relative to the thing it belongs to, and its
 * colour as sRGB 0xRRGGBB. Colour is also what a colour sensor sees.
 */
public record FieldPart(Shape shape, Pose3 local, int rgb) {}
