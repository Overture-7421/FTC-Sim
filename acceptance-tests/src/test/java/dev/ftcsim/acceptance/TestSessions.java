package dev.ftcsim.acceptance;

import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SessionConfig;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.profile.RobotProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Opens simulation sessions on the fixture robot program, and cleans up after them. */
final class TestSessions {

    /** A session together with the storage directory it uses. */
    static final class Handle implements AutoCloseable {
        final SimulationSession session;
        final List<String> log;
        private final Path storage;

        private Handle(SimulationSession session, List<String> log, Path storage) {
            this.session = session;
            this.log = log;
            this.storage = storage;
        }

        @Override
        public void close() throws IOException {
            try {
                session.close();
            } finally {
                try (Stream<Path> paths = Files.walk(storage)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private TestSessions() {}

    static RobotProgram program() {
        return new RobotProgram(
                RobotWorld.classpath("ftcsim.robotClasspath"), RobotWorld.classpath("ftcsim.programClasspath"), "acceptance scenarios");
    }

    /** A session on a bare floor with the robot at {@code (x, y)} facing {@code heading}. */
    static Handle open(RobotProfile profile, double x, double y, double heading) throws IOException {
        return open(profile, null, x, y, heading);
    }

    /** A session on the given field with the robot at {@code (x, y)} facing {@code heading}. */
    static Handle open(RobotProfile profile, Field field, double x, double y, double heading) throws IOException {
        return open(profile, program(), field, field == null ? null : TestField.setup(), x, y, heading);
    }

    /** A session of a robot program other than the fixture OpModes, on a field set up as the caller says. */
    static Handle open(
            RobotProfile profile, RobotProgram program, Field field, FieldSetup setup, double x, double y, double heading)
            throws IOException {
        Path storage = Files.createTempDirectory("ftcsim-sdcard");
        List<String> log = Collections.synchronizedList(new java.util.ArrayList<>());
        try {
            SessionConfig config = new SessionConfig(
                    profile, program, storage, Path.of(System.getProperty("ftcsim.nativeDirectory")), field, setup, x, y, heading,
                    log::add);
            return new Handle(SimulationSession.open(config), log, storage);
        } catch (Throwable failure) {
            Files.deleteIfExists(storage);
            throw failure;
        }
    }
}
