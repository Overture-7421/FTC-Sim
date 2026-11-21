package dev.ftcsim.worker;

import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SessionConfig;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Turns what a person asked for, which is names and paths so that it can cross a process boundary, into what the
 * simulator needs: the built-in robot of the field, the prepared program, the field and where the robot starts.
 */
final class SessionFactory {

    private final List<ProgramResolver> programs;
    private final FieldRegistry fields;
    private final Path nativeDirectory;
    private final Consumer<String> log;

    /**
     * @param programs        the layouts a prepared program may have, tried in order
     * @param nativeDirectory where the physics engine's native library is unpacked
     * @param log             receives every log line of the SDK and of the robot program
     */
    SessionFactory(List<ProgramResolver> programs, FieldRegistry fields, Path nativeDirectory, Consumer<String> log) {
        this.programs = List.copyOf(programs);
        this.fields = fields;
        this.nativeDirectory = nativeDirectory;
        this.log = log;
    }

    /** What a request may name: the fields with the start poses each offers. */
    Catalogue catalogue() {
        return new Catalogue(fields.choices());
    }

    /**
     * @param storageRoot an empty directory for the simulated robot controller's external storage
     * @throws IOException if the program or the field cannot be had; the message says which and why
     */
    SessionConfig config(SessionRequest request, Path storageRoot) throws IOException {
        RobotSpec robot = robot(request.field());
        RobotProgram program = program(request.program());
        Optional<Field> field;
        try {
            field = fields.create(request.field());
        } catch (IllegalArgumentException unknown) {
            throw new IOException(unknown.getMessage(), unknown);
        }
        return new SessionConfig(
                robot, program, storageRoot, nativeDirectory, field.orElse(null), new FieldSetup(request.seed(), request.alliance()),
                request.startX(), request.startY(), request.startHeading(), log);
    }

    /** The robot the game of this field fixes: BIOBUZZ's on its field, Hornet's on any other. */
    RobotSpec robot(String fieldId) {
        return RobotDesign.forField(fieldId).spec();
    }

    RobotProgram program(String reference) throws IOException {
        if (reference.isBlank()) {
            throw new IOException("the request names no robot program");
        }
        Path folder = path("robot program", reference);
        if (!Files.exists(folder)) {
            throw new IOException("the robot program " + folder.toAbsolutePath() + " does not exist");
        }
        for (ProgramResolver resolver : programs) {
            Optional<RobotProgram> found = resolver.resolve(folder);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IOException(folder.toAbsolutePath() + " is not a prepared robot program; expected "
                + programs.stream().map(ProgramResolver::layout).collect(Collectors.joining(" or ")));
    }

    /**
     * What is known about the robot before anything runs. A robot without a chassis has no body in the world, which
     * is told by a box with no size. I2C devices are not mapped, so none are listed.
     */
    static RobotInfo robotInfo(RobotSpec robot) {
        MecanumConfig box = robot.chassis();
        return new RobotInfo(
                robot.name(), box == null ? 0 : box.lengthM(), box == null ? 0 : box.widthM(), box == null ? 0 : box.heightM(),
                List.copyOf(robot.motors().keySet()), List.copyOf(robot.servos().keySet()), List.of(),
                robot.turret() == null ? "" : RobotInfo.BIOBUZZ_MODEL);
    }

    private static Path path(String what, String reference) throws IOException {
        try {
            return Path.of(reference);
        } catch (InvalidPathException invalid) {
            throw new IOException("the " + what + " '" + reference + "' is not a valid path: " + invalid.getMessage(), invalid);
        }
    }
}
