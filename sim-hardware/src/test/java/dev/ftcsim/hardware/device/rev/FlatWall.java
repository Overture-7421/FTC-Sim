package dev.ftcsim.hardware.device.rev;

import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;

/** An infinite flat wall of one colour, which a test moves to where it wants the sensor's reading taken. */
final class FlatWall implements RayView {

    private Vec3 point;
    private final Vec3 normal;
    private int rgb;

    FlatWall(Vec3 point, Vec3 normal, int rgb) {
        this.point = point;
        this.normal = normal;
        this.rgb = rgb;
    }

    void moveTo(Vec3 point) {
        this.point = point;
    }

    void paint(int rgb) {
        this.rgb = rgb;
    }

    @Override
    public SurfaceHit cast(Vec3 origin, Vec3 direction, double maxMeters) {
        double facing = normal.dot(direction);
        if (Math.abs(facing) < 1e-9) {
            return null;
        }
        double distance = normal.dot(point.minus(origin)) / facing;
        return distance < 0 || distance > maxMeters ? null : new SurfaceHit(distance, SurfaceHit.SCENERY, -1, rgb);
    }
}
