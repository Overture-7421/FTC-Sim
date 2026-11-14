package dev.ftcsim.simulator;

import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.simulator.profile.RobotProfile;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Everything that makes one simulated run what it is, apart from what happens during it.
 *
 * @param profile         the robot's hardware
 * @param program         the robot code; the same program, profile, field and inputs always give the same run
 * @param storageRoot     where the simulated robot controller keeps its external storage
 * @param nativeDirectory where the physics engine's native library is unpacked
 * @param field           the game field to play on, or null for a bare floor
 * @param fieldSetup      the seed and alliance the field is built with
 * @param startX          where the robot starts on the field, metres
 * @param startY          where the robot starts on the field, metres
 * @param startHeading    which way it faces, counter-clockwise from the field's +X, radians
 * @param log             receives every log line of the SDK and of the robot program
 */
public record SessionConfig(
        RobotProfile profile,
        RobotProgram program,
        Path storageRoot,
        Path nativeDirectory,
        Field field,
        FieldSetup fieldSetup,
        double startX,
        double startY,
        double startHeading,
        Consumer<String> log) {}
