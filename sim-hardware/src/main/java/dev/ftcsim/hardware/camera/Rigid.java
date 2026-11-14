package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.Vec3;

/** A rigid transform: {@code p -> rotation * p + translation}. */
record Rigid(Mat3 rotation, Vec3 translation) {

    static final Rigid IDENTITY = new Rigid(Mat3.IDENTITY, Vec3.ZERO);

    Vec3 apply(Vec3 point) {
        return rotation.times(point).plus(translation);
    }

    /** The transform that applies {@code inner} first and this one second. */
    Rigid compose(Rigid inner) {
        return new Rigid(rotation.times(inner.rotation), rotation.times(inner.translation).plus(translation));
    }

    Rigid inverse() {
        Mat3 back = rotation.transpose();
        return new Rigid(back, back.times(translation).times(-1.0));
    }
}
