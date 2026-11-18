package dev.ftcsim.contracts.field;

/**
 * One solid of something that is drawn: its shape, where it sits relative to the thing it belongs to, and its
 * colour as sRGB 0xRRGGBB. Colour is also what a colour sensor sees; alpha affects drawing only.
 *
 * @param alpha opacity from 0 (invisible) to 1 (opaque)
 */
public record FieldPart(Shape shape, Pose3 local, int rgb, float alpha) {
    public FieldPart {
        if (!Float.isFinite(alpha) || alpha < 0f || alpha > 1f) {
            throw new IllegalArgumentException("field part alpha must be between 0 and 1");
        }
    }

    /** An opaque part. */
    public FieldPart(Shape shape, Pose3 local, int rgb) {
        this(shape, local, rgb, 1f);
    }
}
