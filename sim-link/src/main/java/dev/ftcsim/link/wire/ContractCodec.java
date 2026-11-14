package dev.ftcsim.link.wire;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.contracts.session.Readout;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How each record of {@code sim-contracts} that crosses the port is laid out. Fields are written in declaration
 * order; enumerations get an explicit code, so that reordering a constant never changes the wire; every list is
 * bounded by {@link Protocol#MAX_LIST} and by the bytes that remain.
 */
final class ContractCodec {

    private static final int GAMEPAD_FLAGS = 15;
    private static final int POSE_BYTES = 7 * 8;
    private static final int STRING_BYTES = 4;
    private static final int PART_BYTES = 1 + 8 + POSE_BYTES + 4;

    private ContractCodec() {}

    // Requests and sessions -----------------------------------------------------------------------------------

    static void writeRequest(WireWriter w, SessionRequest request) {
        w.string(request.profile());
        w.string(request.program());
        w.string(request.field());
        w.unsignedByte(code(request.alliance()));
        w.longInteger(request.seed());
        w.doubleBits(request.startX());
        w.doubleBits(request.startY());
        w.doubleBits(request.startHeading());
        w.doubleBits(request.speed());
    }

    static SessionRequest readRequest(WireReader r) throws WireException {
        String profile = r.string("the profile");
        String program = r.string("the program");
        String field = r.string("the field");
        Alliance alliance = alliance(r.unsignedByte());
        return new SessionRequest(
                profile, program, field, alliance, r.longInteger(), r.doubleBits(), r.doubleBits(), r.doubleBits(), r.doubleBits());
    }

    static void writeInfo(WireWriter w, SessionInfo info) {
        w.bool(info.field() != null);
        if (info.field() != null) {
            writeField(w, info.field());
        }
        w.count(info.opModes().size(), "the OpModes");
        for (OpModeDescriptor opMode : info.opModes()) {
            w.string(opMode.name());
            w.string(opMode.group());
            w.unsignedByte(code(opMode.kind()));
            w.string(opMode.className());
        }
        RobotInfo robot = info.robot();
        w.string(robot.name());
        w.doubleBits(robot.lengthM());
        w.doubleBits(robot.widthM());
        w.doubleBits(robot.heightM());
        writeStrings(w, robot.motors(), "the motors");
        writeStrings(w, robot.servos(), "the servos");
        writeStrings(w, robot.devices(), "the devices");
    }

    static SessionInfo readInfo(WireReader r) throws WireException {
        FieldDescription field = r.bool() ? readField(r) : null;
        int count = r.count(3 * STRING_BYTES + 1, "the OpModes");
        List<OpModeDescriptor> opModes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = r.string("an OpMode name");
            String group = r.string("an OpMode group");
            OpModeKind kind = opModeKind(r.unsignedByte());
            opModes.add(new OpModeDescriptor(name, group, kind, r.string("an OpMode class name")));
        }
        RobotInfo robot = new RobotInfo(
                r.string("the robot name"), r.doubleBits(), r.doubleBits(), r.doubleBits(), readStrings(r, "the motors"),
                readStrings(r, "the servos"), readStrings(r, "the devices"));
        return new SessionInfo(field, opModes, robot);
    }

    // Fields --------------------------------------------------------------------------------------------------

    static void writeField(WireWriter w, FieldDescription field) {
        w.string(field.id());
        w.string(field.displayName());
        w.count(field.scenery().size(), "the scenery");
        for (Scenery scenery : field.scenery()) {
            w.string(scenery.name());
            writePart(w, scenery.part());
            writePose(w, scenery.pose());
        }
        w.count(field.bodyKinds().size(), "the body kinds");
        for (BodyKind kind : field.bodyKinds()) {
            w.integer(kind.id());
            w.string(kind.name());
            w.count(kind.parts().size(), "the parts of body kind " + kind.name());
            for (FieldPart part : kind.parts()) {
                writePart(w, part);
            }
        }
        w.count(field.tags().size(), "the tags");
        for (FieldTag tag : field.tags()) {
            w.integer(tag.id());
            w.doubleBits(tag.sizeMeters());
            w.integer(tag.hostEntityId());
            writePose(w, tag.local());
        }
        writeStartPoses(w, field.startPoses());
        writeHumanActions(w, field.humanActions());
    }

    static FieldDescription readField(WireReader r) throws WireException {
        String id = r.string("the field id");
        String displayName = r.string("the field name");
        int sceneryCount = r.count(STRING_BYTES + PART_BYTES + POSE_BYTES, "the scenery");
        List<Scenery> scenery = new ArrayList<>(sceneryCount);
        for (int i = 0; i < sceneryCount; i++) {
            String name = r.string("a scenery name");
            FieldPart part = readPart(r);
            scenery.add(new Scenery(name, part, readPose(r)));
        }
        int kindCount = r.count(4 + STRING_BYTES + 4, "the body kinds");
        List<BodyKind> kinds = new ArrayList<>(kindCount);
        for (int i = 0; i < kindCount; i++) {
            int kindId = r.integer();
            String name = r.string("a body kind name");
            int partCount = r.count(PART_BYTES, "the parts of body kind " + name);
            List<FieldPart> parts = new ArrayList<>(partCount);
            for (int j = 0; j < partCount; j++) {
                parts.add(readPart(r));
            }
            kinds.add(new BodyKind(kindId, name, parts));
        }
        int tagCount = r.count(4 + 8 + 4 + POSE_BYTES, "the tags");
        List<FieldTag> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            tags.add(new FieldTag(r.integer(), r.doubleBits(), r.integer(), readPose(r)));
        }
        List<StartPose> starts = readStartPoses(r);
        List<HumanAction> actions = readHumanActions(r);
        return new FieldDescription(id, displayName, scenery, kinds, tags, starts, actions);
    }

    private static void writeStartPoses(WireWriter w, List<StartPose> poses) {
        w.count(poses.size(), "the start poses");
        for (StartPose pose : poses) {
            w.string(pose.name());
            w.doubleBits(pose.x());
            w.doubleBits(pose.y());
            w.doubleBits(pose.headingRadians());
        }
    }

    private static List<StartPose> readStartPoses(WireReader r) throws WireException {
        int count = r.count(STRING_BYTES + 3 * 8, "the start poses");
        List<StartPose> poses = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            poses.add(new StartPose(r.string("a start pose name"), r.doubleBits(), r.doubleBits(), r.doubleBits()));
        }
        return poses;
    }

    private static void writeHumanActions(WireWriter w, List<HumanAction> actions) {
        w.count(actions.size(), "the human actions");
        for (HumanAction action : actions) {
            w.string(action.id());
            w.string(action.label());
        }
    }

    private static List<HumanAction> readHumanActions(WireReader r) throws WireException {
        int count = r.count(2 * STRING_BYTES, "the human actions");
        List<HumanAction> actions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            actions.add(new HumanAction(r.string("a human action id"), r.string("a human action label")));
        }
        return actions;
    }

    // What can be asked for -------------------------------------------------------------------------------------

    static void writeCatalogue(WireWriter w, Catalogue catalogue) {
        w.count(catalogue.fields().size(), "the fields");
        for (FieldChoice field : catalogue.fields()) {
            w.string(field.id());
            w.string(field.displayName());
            writeStartPoses(w, field.startPoses());
            writeHumanActions(w, field.humanActions());
        }
        writeStrings(w, catalogue.profiles(), "the profiles");
    }

    static Catalogue readCatalogue(WireReader r) throws WireException {
        int count = r.count(4 * STRING_BYTES, "the fields");
        List<FieldChoice> fields = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = r.string("a field id");
            String displayName = r.string("a field name");
            List<StartPose> starts = readStartPoses(r);
            fields.add(new FieldChoice(id, displayName, starts, readHumanActions(r)));
        }
        return new Catalogue(fields, readStrings(r, "the profiles"));
    }

    private static void writePart(WireWriter w, FieldPart part) {
        switch (part.shape()) {
            case Shape.Box box -> {
                w.unsignedByte(0);
                w.doubleBits(box.halfX());
                w.doubleBits(box.halfY());
                w.doubleBits(box.halfZ());
            }
            case Shape.Sphere sphere -> {
                w.unsignedByte(1);
                w.doubleBits(sphere.radius());
            }
            case Shape.Cylinder cylinder -> {
                w.unsignedByte(2);
                w.doubleBits(cylinder.radius());
                w.doubleBits(cylinder.halfHeight());
            }
        }
        writePose(w, part.local());
        w.integer(part.rgb());
    }

    private static FieldPart readPart(WireReader r) throws WireException {
        int tag = r.unsignedByte();
        Shape shape =
                switch (tag) {
                    case 0 -> new Shape.Box(r.doubleBits(), r.doubleBits(), r.doubleBits());
                    case 1 -> new Shape.Sphere(r.doubleBits());
                    case 2 -> new Shape.Cylinder(r.doubleBits(), r.doubleBits());
                    default -> throw new WireException("unknown shape code " + tag);
                };
        Pose3 local = readPose(r);
        return new FieldPart(shape, local, r.integer());
    }

    private static void writePose(WireWriter w, Pose3 pose) {
        w.doubleBits(pose.x());
        w.doubleBits(pose.y());
        w.doubleBits(pose.z());
        w.doubleBits(pose.qw());
        w.doubleBits(pose.qx());
        w.doubleBits(pose.qy());
        w.doubleBits(pose.qz());
    }

    private static Pose3 readPose(WireReader r) throws WireException {
        return new Pose3(r.doubleBits(), r.doubleBits(), r.doubleBits(), r.doubleBits(), r.doubleBits(), r.doubleBits(), r.doubleBits());
    }

    // The world and its status -----------------------------------------------------------------------------------

    static void writeSnapshot(WireWriter w, WorldSnapshot snapshot) {
        w.longInteger(snapshot.stepIndex());
        w.longInteger(snapshot.simTimeNanos());
        w.count(snapshot.bodies().size(), "the bodies");
        for (BodyState body : snapshot.bodies()) {
            w.integer(body.entityId());
            w.integer(body.kind());
            w.floatBits(body.x());
            w.floatBits(body.y());
            w.floatBits(body.z());
            w.floatBits(body.qx());
            w.floatBits(body.qy());
            w.floatBits(body.qz());
            w.floatBits(body.qw());
        }
    }

    static WorldSnapshot readSnapshot(WireReader r) throws WireException {
        long stepIndex = r.longInteger();
        long simTimeNanos = r.longInteger();
        int count = r.count(2 * 4 + 7 * 4, "the bodies");
        List<BodyState> bodies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bodies.add(new BodyState(
                    r.integer(), r.integer(), r.floatBits(), r.floatBits(), r.floatBits(), r.floatBits(), r.floatBits(),
                    r.floatBits(), r.floatBits()));
        }
        return new WorldSnapshot(stepIndex, simTimeNanos, bodies);
    }

    static void writeStatus(WireWriter w, SessionStatus status) {
        w.longInteger(status.simTimeNanos());
        w.unsignedByte(code(status.phase()));
        TelemetrySnapshot telemetry = Objects.requireNonNull(status.telemetry(), "telemetry");
        writeStrings(w, telemetry.lines(), "the telemetry lines");
        w.string(telemetry.warnings());
        w.string(telemetry.error());
        Score score = Objects.requireNonNull(status.score(), "score");
        w.count(score.lines().size(), "the score lines");
        for (Score.Line line : score.lines()) {
            w.string(line.label());
            w.integer(line.count());
            w.integer(line.points());
        }
        writeStrings(w, status.problems(), "the problems");
        w.count(status.hardware().size(), "the hardware readouts");
        for (Readout readout : status.hardware()) {
            w.string(readout.name());
            w.string(readout.value());
        }
        w.doubleBits(status.speed());
        w.doubleBits(status.achievedSpeed());
        w.bool(status.paused());
        MatchStatus match = Objects.requireNonNull(status.match(), "match");
        w.unsignedByte(code(match.state()));
        w.longInteger(match.phaseElapsedNanos());
        w.longInteger(match.phaseDurationNanos());
    }

    static SessionStatus readStatus(WireReader r) throws WireException {
        long simTimeNanos = r.longInteger();
        RobotPhase phase = phase(r.unsignedByte());
        List<String> lines = readStrings(r, "the telemetry lines");
        TelemetrySnapshot telemetry = new TelemetrySnapshot(lines, r.string("the warnings"), r.string("the error text"));
        int scoreCount = r.count(STRING_BYTES + 8, "the score lines");
        List<Score.Line> scoreLines = new ArrayList<>(scoreCount);
        for (int i = 0; i < scoreCount; i++) {
            scoreLines.add(new Score.Line(r.string("a score label"), r.integer(), r.integer()));
        }
        List<String> problems = readStrings(r, "the problems");
        int readoutCount = r.count(2 * STRING_BYTES, "the hardware readouts");
        List<Readout> readouts = new ArrayList<>(readoutCount);
        for (int i = 0; i < readoutCount; i++) {
            readouts.add(new Readout(r.string("a readout name"), r.string("a readout value")));
        }
        double speed = r.doubleBits();
        double achievedSpeed = r.doubleBits();
        boolean paused = r.bool();
        MatchStatus match = new MatchStatus(matchState(r.unsignedByte()), r.longInteger(), r.longInteger());
        return new SessionStatus(
                simTimeNanos, phase, telemetry, new Score(scoreLines), problems, readouts, speed, achievedSpeed, paused, match);
    }

    // Gamepads ---------------------------------------------------------------------------------------------------

    static void writeGamepad(WireWriter w, GamepadState pad) {
        w.floatBits(pad.leftStickX());
        w.floatBits(pad.leftStickY());
        w.floatBits(pad.rightStickX());
        w.floatBits(pad.rightStickY());
        w.floatBits(pad.leftTrigger());
        w.floatBits(pad.rightTrigger());
        boolean[] buttons = {
            pad.dpadUp(), pad.dpadDown(), pad.dpadLeft(), pad.dpadRight(), pad.a(), pad.b(), pad.x(), pad.y(),
            pad.leftBumper(), pad.rightBumper(), pad.leftStickButton(), pad.rightStickButton(), pad.start(), pad.back(),
            pad.guide()
        };
        int flags = 0;
        for (int i = 0; i < buttons.length; i++) {
            flags |= buttons[i] ? 1 << i : 0;
        }
        w.unsignedShort(flags);
    }

    static GamepadState readGamepad(WireReader r) throws WireException {
        float leftStickX = r.floatBits();
        float leftStickY = r.floatBits();
        float rightStickX = r.floatBits();
        float rightStickY = r.floatBits();
        float leftTrigger = r.floatBits();
        float rightTrigger = r.floatBits();
        int flags = r.unsignedShort();
        if (flags >>> GAMEPAD_FLAGS != 0) {
            throw new WireException("a gamepad reports button bits beyond the " + GAMEPAD_FLAGS + " it has: 0x" + Integer.toHexString(flags));
        }
        return new GamepadState(
                leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger, bit(flags, 0), bit(flags, 1),
                bit(flags, 2), bit(flags, 3), bit(flags, 4), bit(flags, 5), bit(flags, 6), bit(flags, 7), bit(flags, 8),
                bit(flags, 9), bit(flags, 10), bit(flags, 11), bit(flags, 12), bit(flags, 13), bit(flags, 14));
    }

    private static boolean bit(int flags, int index) {
        return (flags >>> index & 1) != 0;
    }

    // Lists and enumerations -------------------------------------------------------------------------------------

    private static void writeStrings(WireWriter w, List<String> strings, String what) {
        w.count(strings.size(), what);
        for (String string : strings) {
            w.string(string);
        }
    }

    private static List<String> readStrings(WireReader r, String what) throws WireException {
        int count = r.count(STRING_BYTES, what);
        List<String> strings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            strings.add(r.string("an entry of " + what));
        }
        return strings;
    }

    private static int code(Alliance alliance) {
        return switch (Objects.requireNonNull(alliance, "alliance")) {
            case RED -> 0;
            case BLUE -> 1;
        };
    }

    private static Alliance alliance(int code) throws WireException {
        return switch (code) {
            case 0 -> Alliance.RED;
            case 1 -> Alliance.BLUE;
            default -> throw new WireException("unknown alliance code " + code);
        };
    }

    private static int code(RobotPhase phase) {
        return switch (Objects.requireNonNull(phase, "phase")) {
            case IDLE -> 0;
            case INIT -> 1;
            case RUNNING -> 2;
        };
    }

    private static RobotPhase phase(int code) throws WireException {
        return switch (code) {
            case 0 -> RobotPhase.IDLE;
            case 1 -> RobotPhase.INIT;
            case 2 -> RobotPhase.RUNNING;
            default -> throw new WireException("unknown robot phase code " + code);
        };
    }

    private static int code(OpModeKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case TELEOP -> 0;
            case AUTONOMOUS -> 1;
        };
    }

    private static OpModeKind opModeKind(int code) throws WireException {
        return switch (code) {
            case 0 -> OpModeKind.TELEOP;
            case 1 -> OpModeKind.AUTONOMOUS;
            default -> throw new WireException("unknown OpMode kind code " + code);
        };
    }

    private static int code(MatchState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case NONE -> 0;
            case ARMED -> 1;
            case AUTONOMOUS -> 2;
            case TRANSITION -> 3;
            case TELEOP -> 4;
            case ENDED -> 5;
        };
    }

    private static MatchState matchState(int code) throws WireException {
        return switch (code) {
            case 0 -> MatchState.NONE;
            case 1 -> MatchState.ARMED;
            case 2 -> MatchState.AUTONOMOUS;
            case 3 -> MatchState.TRANSITION;
            case 4 -> MatchState.TELEOP;
            case 5 -> MatchState.ENDED;
            default -> throw new WireException("unknown match state code " + code);
        };
    }
}
