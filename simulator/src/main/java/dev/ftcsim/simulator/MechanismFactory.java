package dev.ftcsim.simulator;

import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.mechanisms.CaptureSpec;
import dev.ftcsim.mechanisms.Condition;
import dev.ftcsim.mechanisms.LaunchSpec;
import dev.ftcsim.mechanisms.Mechanisms;
import dev.ftcsim.mechanisms.PlantView;
import dev.ftcsim.mechanisms.StorageSpec;
import dev.ftcsim.mechanisms.Volume;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.simulator.profile.ProfileException;
import dev.ftcsim.simulator.profile.RobotProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns the mechanisms section of a robot profile into running mechanisms, naming the profile entry of every mistake. */
final class MechanismFactory {

    private MechanismFactory() {}

    static Mechanisms create(RobotProfile profile, PlantView plants, Map<Integer, PieceSpec> pieceSpecs) {
        RobotProfile.Mechanisms section = profile.mechanisms();
        List<StorageSpec> storages = new ArrayList<>();
        Map<String, StorageSpec> byName = new java.util.HashMap<>();
        for (Map.Entry<String, RobotProfile.Storage> entry : section.storages().entrySet()) {
            StorageSpec spec = storage("mechanisms.storages." + entry.getKey(), entry.getKey(), entry.getValue());
            storages.add(spec);
            byName.put(entry.getKey(), spec);
        }
        List<CaptureSpec> captures = new ArrayList<>();
        for (Map.Entry<String, RobotProfile.Intake> entry : section.intakes().entrySet()) {
            String where = "mechanisms.intakes." + entry.getKey();
            RobotProfile.Intake intake = entry.getValue();
            requireStorage(where, intake.storage(), byName);
            try {
                captures.add(new CaptureSpec(
                        entry.getKey(), intake.storage(),
                        new Volume.Box(vec(where + ".center", intake.center()), vec(where + ".half", intake.half())),
                        conditions(intake.when()),
                        intake.minPieceDiameterM() == null ? 0.0 : intake.minPieceDiameterM(),
                        intake.maxPieceDiameterM() == null ? Double.POSITIVE_INFINITY : intake.maxPieceDiameterM()));
            } catch (IllegalArgumentException invalid) {
                throw new ProfileException(where + ": " + invalid.getMessage(), invalid);
            }
        }
        List<LaunchSpec> launches = new ArrayList<>();
        for (Map.Entry<String, RobotProfile.Launcher> entry : section.launchers().entrySet()) {
            String where = "mechanisms.launchers." + entry.getKey();
            RobotProfile.Launcher launcher = entry.getValue();
            requireStorage(where, launcher.storage(), byName);
            List<LaunchSpec.Wheel> wheels = new ArrayList<>();
            for (RobotProfile.Flywheel wheel : launcher.wheels()) {
                RobotProfile.Motor motor = profile.motors().get(wheel.motor());
                if (motor == null || motor.shaft() == null) {
                    throw new ProfileException(where + ": wheel '" + wheel.motor() + "' must be a motor of the profile that turns a shaft");
                }
                wheels.add(new LaunchSpec.Wheel(wheel.motor(), wheel.radiusM(), wheel.sign()));
            }
            try {
                launches.add(new LaunchSpec(
                        entry.getKey(), launcher.storage(), conditions(launcher.when()), wheels, launcher.efficiency(),
                        vec(where + ".direction", launcher.direction()), launcher.dribbleMps()));
            } catch (IllegalArgumentException invalid) {
                throw new ProfileException(where + ": " + invalid.getMessage(), invalid);
            }
        }
        try {
            return new Mechanisms(storages, captures, launches, plants, pieceSpecs);
        } catch (IllegalArgumentException invalid) {
            throw new ProfileException("mechanisms: " + invalid.getMessage(), invalid);
        }
    }

    private static StorageSpec storage(String where, String name, RobotProfile.Storage storage) {
        List<Vec3> path = new ArrayList<>();
        for (int i = 0; i < storage.path().size(); i++) {
            path.add(vec(where + ".path[" + i + "]", storage.path().get(i)));
        }
        double length;
        try {
            length = new StorageSpec(name, Math.max(1, storage.capacity()), path, List.of()).lengthMeters();
        } catch (IllegalArgumentException invalid) {
            throw new ProfileException(where + ": " + invalid.getMessage(), invalid);
        }
        if (storage.capacity() < 1) {
            throw new ProfileException(where + ": capacity must be at least 1, not " + storage.capacity());
        }
        List<StorageSpec.Drive> drives = new ArrayList<>();
        for (RobotProfile.Drive drive : storage.drives()) {
            try {
                drives.add(new StorageSpec.Drive(
                        drive.motor(), drive.radiusM(), drive.sign(),
                        drive.from() == null ? 0.0 : drive.from(), drive.to() == null ? length : drive.to()));
            } catch (IllegalArgumentException invalid) {
                throw new ProfileException(where + ".drives: " + invalid.getMessage(), invalid);
            }
        }
        return new StorageSpec(name, storage.capacity(), path, drives);
    }

    private static List<Condition> conditions(List<RobotProfile.When> when) {
        List<Condition> conditions = new ArrayList<>();
        for (RobotProfile.When condition : when) {
            conditions.add(new Condition.ShaftSpeed(
                    condition.motor(),
                    condition.minRadPerSec() == null ? Double.NEGATIVE_INFINITY : condition.minRadPerSec(),
                    condition.maxRadPerSec() == null ? Double.POSITIVE_INFINITY : condition.maxRadPerSec()));
        }
        return conditions;
    }

    private static void requireStorage(String where, String name, Map<String, StorageSpec> storages) {
        if (!storages.containsKey(name)) {
            throw new ProfileException(where + ": storage '" + name + "' is not one of mechanisms.storages " + storages.keySet());
        }
    }

    private static Vec3 vec(String where, List<Double> values) {
        if (values.size() != 3) {
            throw new ProfileException(where + ": expected [x, y, z], found " + values.size() + " numbers");
        }
        return new Vec3(values.get(0), values.get(1), values.get(2));
    }
}
