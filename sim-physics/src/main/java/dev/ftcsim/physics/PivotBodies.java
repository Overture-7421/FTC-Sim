package dev.ftcsim.physics;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.HingeConstraint;
import com.github.stephengold.joltjni.HingeConstraintSettings;
import com.github.stephengold.joltjni.Mat44;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.OffsetCenterOfMassShape;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import java.util.TreeMap;

/** The bodies of {@link PhysicsWorld#addPivot}: the engine body and hinge of each, and their dampers. */
final class PivotBodies {

    private record PivotBody(int bodyId, HingeConstraint hinge, PivotSpec spec, Vector3 axis) {}

    private final PhysicsSystem system;
    private final BodyInterface bodies;
    private final TreeMap<Integer, PivotBody> byEntity = new TreeMap<>();
    private final Vec3 scratchVelocity = new Vec3();

    PivotBodies(PhysicsSystem system, BodyInterface bodies) {
        this.system = system;
        this.bodies = bodies;
    }

    /** Creates the body and its hinge and returns the engine's id of the body. */
    int create(int entityId, PivotSpec spec, int layer) {
        Vector3 axis = spec.axis().normalized();
        try (ShapeResult compound = Shapes.compoundOf(spec.parts());
                ShapeRefC compoundShape = compound.get()) {
            Vec3 natural = compoundShape.getCenterOfMass();
            Vec3 offset = new Vec3(
                    (float) spec.centerOfMass().x() - natural.getX(),
                    (float) spec.centerOfMass().y() - natural.getY(),
                    (float) spec.centerOfMass().z() - natural.getZ());
            try (OffsetCenterOfMassShape shape = new OffsetCenterOfMassShape(compoundShape, offset);
                    MassProperties mass = new MassProperties();
                    BodyCreationSettings settings = new BodyCreationSettings(
                            shape, rvec(spec.pivot()), quat(Quaternion.axisAngle(axis, spec.initialAngleRadians())),
                            EMotionType.Dynamic, layer)) {
                mass.setMass((float) spec.massKg());
                Mat44 inertia = Mat44.sIdentity();
                inertia.setDiagonal3(vec(spec.inertiaKgM2()));
                mass.setInertia(inertia);
                settings.setOverrideMassProperties(EOverrideMassProperties.MassAndInertiaProvided)
                        .setMassPropertiesOverride(mass)
                        .setFriction(spec.friction())
                        .setRestitution(spec.restitution())
                        .setLinearDamping(0f)
                        .setAngularDamping(0f)
                        .setAllowSleeping(false);
                Body body = bodies.createBody(settings);
                bodies.addBody(body, EActivation.Activate);
                HingeConstraint hinge = addHinge(body, spec, axis);
                byEntity.put(entityId, new PivotBody(body.getId(), hinge, spec, axis));
                return body.getId();
            }
        }
    }

    private HingeConstraint addHinge(Body body, PivotSpec spec, Vector3 axis) {
        Vector3 normal = perpendicularTo(axis);
        Vector3 turnedNormal = Quaternion.axisAngle(axis, spec.initialAngleRadians()).rotate(normal);
        try (HingeConstraintSettings hinge = new HingeConstraintSettings()) {
            hinge.setSpace(EConstraintSpace.WorldSpace);
            hinge.setPoint1(spec.pivot().x(), spec.pivot().y(), spec.pivot().z());
            hinge.setPoint2(spec.pivot().x(), spec.pivot().y(), spec.pivot().z());
            hinge.setHingeAxis1(vec(axis));
            hinge.setHingeAxis2(vec(axis));
            hinge.setNormalAxis1(vec(normal));
            hinge.setNormalAxis2(vec(turnedNormal));
            hinge.setLimitsMin((float) spec.minAngleRadians());
            hinge.setLimitsMax((float) spec.maxAngleRadians());
            hinge.setMaxFrictionTorque((float) spec.bearingFrictionNm());
            TwoBodyConstraint constraint = hinge.create(Body.sFixedToWorld(), body);
            system.addConstraint(constraint);
            return (HingeConstraint) constraint;
        }
    }

    private static Vector3 perpendicularTo(Vector3 axis) {
        Vector3 reference = Math.abs(axis.x()) < 0.9 ? Vector3.X : Vector3.Y;
        return reference.minus(axis.times(reference.dot(axis))).normalized();
    }

    double angle(int entityId) {
        return byEntity.get(entityId).hinge().getCurrentAngle();
    }

    double angularVelocity(int entityId) {
        PivotBody pivot = byEntity.get(entityId);
        bodies.getAngularVelocity(pivot.bodyId(), scratchVelocity);
        return along(scratchVelocity, pivot.axis());
    }

    /** Called once before each step: a damper resists a body that is approaching a stop within the damper zone. */
    void applyDampers() {
        for (PivotBody pivot : byEntity.values()) {
            PivotSpec spec = pivot.spec();
            double zone = spec.damperZoneRadians();
            if (!(zone > 0) || !(spec.damperNmsPerRad() > 0)) {
                continue;
            }
            double angle = pivot.hinge().getCurrentAngle();
            bodies.getAngularVelocity(pivot.bodyId(), scratchVelocity);
            double omega = along(scratchVelocity, pivot.axis());
            double toUpper = spec.maxAngleRadians() - angle;
            double toLower = angle - spec.minAngleRadians();
            double depth = 0.0;
            if (toUpper < zone && omega > 0) {
                depth = 1.0 - Math.max(toUpper, 0.0) / zone;
            } else if (toLower < zone && omega < 0) {
                depth = 1.0 - Math.max(toLower, 0.0) / zone;
            }
            if (depth > 0.0) {
                double torque = -spec.damperNmsPerRad() * depth * omega;
                bodies.addTorque(pivot.bodyId(), vec(pivot.axis().times(torque)));
            }
        }
    }

    /** Releases the hinges, which the engine does not free together with the system. */
    void close() {
        for (PivotBody pivot : byEntity.values()) {
            system.removeConstraint(pivot.hinge());
            pivot.hinge().close();
        }
        byEntity.clear();
    }

    private static double along(Vec3 v, Vector3 axis) {
        return v.getX() * axis.x() + v.getY() * axis.y() + v.getZ() * axis.z();
    }

    private static Vec3 vec(Vector3 v) {
        return new Vec3((float) v.x(), (float) v.y(), (float) v.z());
    }

    private static RVec3 rvec(Vector3 v) {
        return new RVec3(v.x(), v.y(), v.z());
    }

    private static Quat quat(Quaternion q) {
        return new Quat((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
    }
}
