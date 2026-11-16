package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.FieldRun;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.field.support.PieceReserve;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Pivot;
import dev.ftcsim.physics.ShapePart;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * One run of the BIOBUZZ field in a physics world: the bodies of {@link BiobuzzField} placed as staged for a match,
 * and the rules of the game applied to them after every step. See {@code docs/fields/biobuzz.md}.
 */
public final class BiobuzzRun implements FieldRun {

    /** A HIVE that has moved from one stable state to the other, as section 10.5.1 of the manual defines a TIP. */
    public record TipEvent(int sequence, Alliance hive, long stepIndex, long timeNanos, MatchPhase phase) {}

    private static final long AUTO_NANOS = 30_000_000_000L;
    private static final long TELEOP_NANOS = 120_000_000_000L;
    private static final long ONE_MINUTE_LEFT_NANOS = TELEOP_NANOS - 60_000_000_000L;
    /** After the buzzer the run waits for everything to come to rest, but not for longer than this. */
    private static final long SETTLE_LIMIT_NANOS = 10_000_000_000L;
    private static final int QUIET_STEPS = 60;
    private static final double QUIET_MOVE_M = 0.0002;
    private static final double QUIET_TURN_RAD = 0.0001;
    /** A piece whose centre is this far outside the perimeter has left the field. */
    private static final double OUTSIDE_M = FieldGeometry.HALF_M + FieldGeometry.WALL_THICKNESS_M + 0.03;
    private static final double[] LOAD_OFFSETS_INCHES = {0, -6, 6, -3, 3};
    private static final int RED = 0;
    private static final int BLUE = 1;

    private final PhysicsWorld world;
    private final Alliance alliance;
    private final boolean red;
    private final Pivot[] hives = new Pivot[2];
    private final PieceReserve preloads = new PieceReserve();
    private final PieceReserve nectarWithHumans = new PieceReserve();
    private final PieceReserve pollenOutside = new PieceReserve();
    private final Map<Integer, double[]> whereItLeft = new TreeMap<>();
    private final List<TipEvent> tips = new ArrayList<>();
    private final int[] autoTips = new int[2];
    private final int[] teleopTips = new int[2];
    private final int[] settledSide = {-1, 1};
    private final int[] nectarEnteredForTips = new int[2];
    private final int[] nectarReturned = new int[2];
    private final int[] nectarLoads = new int[2];
    private final double[] lastHiveAngle = new double[2];
    private final Map<Integer, float[]> lastPosition = new TreeMap<>();

    private MatchPhase phase = MatchPhase.PRACTICE;
    private long autoStartNanos = -1;
    private long teleopStartNanos = -1;
    private long endNanos = -1;
    private long nowNanos;
    private long stepIndex;
    private boolean autoAssessed;
    private boolean leftWallAtEndOfAuto;
    private boolean parkedAtEndOfAuto;
    private final double robotLength;
    private final double robotWidth;
    private int quietSteps;
    private Score finalScore;

    BiobuzzRun(PhysicsWorld world, FieldSetup setup) {
        this.world = world;
        this.alliance = setup.alliance();
        this.robotLength = setup.robotLengthMeters();
        this.robotWidth = setup.robotWidthMeters();
        this.red = alliance == Alliance.RED;
        for (FieldGeometry.Group group : FieldGeometry.colliderGroups()) {
            List<ShapePart> parts = new ArrayList<>();
            group.solids().stream().filter(Solid::collides).map(Solid::part).forEach(parts::add);
            world.addStaticShapes(parts, group.friction(), group.restitution());
        }
        hives[RED] = world.addPivot(BiobuzzIds.HIVE_RED, BiobuzzIds.KIND_HIVE_RED, HiveModel.spec(true, true));
        hives[BLUE] = world.addPivot(BiobuzzIds.HIVE_BLUE, BiobuzzIds.KIND_HIVE_BLUE, HiveModel.spec(false, false));
        lastHiveAngle[RED] = -HiveModel.STOP_RADIANS;
        lastHiveAngle[BLUE] = HiveModel.STOP_RADIANS;

        Staging staging = new Staging(alliance, setup.seed());
        for (Staging.Placement p : staging.inWorld) {
            world.spawnPiece(p.id(), p.kind(), PieceModel.specOf(p.kind()), new Vector3(p.x(), p.y(), p.z()), Vector3.ZERO);
        }
        staging.robotPollen.forEach(id -> preloads.add(BiobuzzIds.KIND_POLLEN, id));
        staging.redNectarWithHuman.forEach(id -> nectarWithHumans.add(BiobuzzIds.KIND_NECTAR_RED, id));
        staging.blueNectarWithHuman.forEach(id -> nectarWithHumans.add(BiobuzzIds.KIND_NECTAR_BLUE, id));
    }

    /** The TIPS completed so far, in the order they happened. */
    public List<TipEvent> tips() {
        return List.copyOf(tips);
    }

    public MatchPhase phase() {
        return phase;
    }

    /** True once the match is over and everything has come to rest, so that the score no longer changes. */
    public boolean isFinal() {
        return finalScore != null;
    }

    /** True from sixty seconds before the end of a match, or throughout practice: NECTAR may be entered freely. */
    public boolean oneMinuteLeft() {
        return phase == MatchPhase.PRACTICE || phase == MatchPhase.ENDED
                || (teleopStartNanos >= 0 && nowNanos - teleopStartNanos >= ONE_MINUTE_LEFT_NANOS);
    }

    /** How many pieces of a kind wait with the humans or the robot's preloads, out of the world. */
    public int piecesInReserve(int kind) {
        return kind == BiobuzzIds.KIND_POLLEN ? preloads.count(kind) : nectarWithHumans.count(kind);
    }

    /** How many POLLEN have left the field and wait for the field staff to put them back. */
    public int pollenOutsideField() {
        return pollenOutside.count(BiobuzzIds.KIND_POLLEN);
    }

    @Override
    public void afterStep(WorldSnapshot snapshot) {
        nowNanos = snapshot.simTimeNanos();
        stepIndex = snapshot.stepIndex();
        if (phase == MatchPhase.AUTONOMOUS && !autoAssessed && nowNanos - autoStartNanos >= AUTO_NANOS) {
            assessEndOfAuto(snapshot);
        }
        if (phase == MatchPhase.TELEOP && nowNanos - teleopStartNanos >= TELEOP_NANOS) {
            enterPhase(MatchPhase.ENDED, teleopStartNanos + TELEOP_NANOS);
        }
        boolean moved = false;
        for (BodyState body : snapshot.bodies()) {
            if (Assessment.isPiece(body.kind())) {
                if (leftTheField(body)) {
                    sendOutside(body);
                } else {
                    moved |= stepDistance(body) > QUIET_MOVE_M;
                }
            } else if (body.entityId() == BiobuzzIds.HIVE_RED) {
                moved |= trackHive(body, RED);
            } else if (body.entityId() == BiobuzzIds.HIVE_BLUE) {
                moved |= trackHive(body, BLUE);
            }
        }
        if (phase == MatchPhase.ENDED && finalScore == null) {
            quietSteps = moved ? 0 : quietSteps + 1;
            if (quietSteps >= QUIET_STEPS || nowNanos - endNanos >= SETTLE_LIMIT_NANOS) {
                finalScore = assemble(snapshot);
            }
        }
    }

    private boolean leftTheField(BodyState body) {
        return Math.abs(body.x()) > OUTSIDE_M || Math.abs(body.y()) > OUTSIDE_M || body.z() < -0.15;
    }

    /** Takes a piece that left the field out of the world: POLLEN wait for the staff, NECTAR go back to its humans. */
    private void sendOutside(BodyState body) {
        world.removePiece(body.entityId());
        lastPosition.remove(body.entityId());
        if (body.kind() == BiobuzzIds.KIND_POLLEN) {
            pollenOutside.add(body.kind(), body.entityId());
            whereItLeft.put(body.entityId(), new double[] {body.x(), body.y()});
        } else {
            nectarWithHumans.add(body.kind(), body.entityId());
            nectarReturned[body.kind() == BiobuzzIds.KIND_NECTAR_RED ? RED : BLUE]++;
        }
    }

    /** How far the piece moved in the last step; unbounded the first time it is seen. */
    private double stepDistance(BodyState body) {
        float[] before = lastPosition.get(body.entityId());
        double distance = Double.POSITIVE_INFINITY;
        if (before != null) {
            double dx = body.x() - before[0];
            double dy = body.y() - before[1];
            double dz = body.z() - before[2];
            distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        } else {
            before = new float[3];
            lastPosition.put(body.entityId(), before);
        }
        before[0] = body.x();
        before[1] = body.y();
        before[2] = body.z();
        return distance;
    }

    /** Follows one HIVE and records a TIP when its damper reaches the frame on the side it was not resting on. */
    private boolean trackHive(BodyState body, int which) {
        double angle = Assessment.hiveAngle(body);
        double contact = HiveModel.STOP_RADIANS - HiveModel.DAMPER_ZONE_RADIANS;
        if (settledSide[which] < 0 && angle >= contact || settledSide[which] > 0 && angle <= -contact) {
            settledSide[which] = -settledSide[which];
            boolean auto = phase == MatchPhase.AUTONOMOUS;
            (auto ? autoTips : teleopTips)[which]++;
            tips.add(new TipEvent(tips.size() + 1, which == RED ? Alliance.RED : Alliance.BLUE, stepIndex, nowNanos, phase));
        }
        boolean moved = Math.abs(angle - lastHiveAngle[which]) > QUIET_TURN_RAD;
        lastHiveAngle[which] = angle;
        return moved;
    }

    @Override
    public void enterPhase(MatchPhase next, long timeNanos) {
        switch (next) {
            case PRACTICE -> phase = MatchPhase.PRACTICE;
            case AUTONOMOUS -> {
                phase = MatchPhase.AUTONOMOUS;
                autoStartNanos = timeNanos;
                autoAssessed = false;
            }
            case TELEOP -> {
                assessEndOfAuto(world.snapshot());
                phase = MatchPhase.TELEOP;
                teleopStartNanos = timeNanos;
            }
            case ENDED -> {
                assessEndOfAuto(world.snapshot());
                if (phase != MatchPhase.ENDED) {
                    phase = MatchPhase.ENDED;
                    endNanos = timeNanos;
                    quietSteps = 0;
                }
            }
        }
    }

    /** LEAVE and PARK in AUTO are judged once, at the end of AUTO, on the robot as it stands then. */
    private void assessEndOfAuto(WorldSnapshot snapshot) {
        if (autoAssessed || autoStartNanos < 0) {
            return;
        }
        autoAssessed = true;
        Assessment now = Assessment.of(snapshot, red, robotLength, robotWidth);
        leftWallAtEndOfAuto = now.leftWall();
        parkedAtEndOfAuto = now.parked();
    }

    @Override
    public void perform(String humanActionId) {
        switch (humanActionId) {
            case BiobuzzField.LOAD_NECTAR -> loadNectar(red ? RED : BLUE);
            case BiobuzzField.LOAD_OPPONENT_NECTAR -> loadNectar(red ? BLUE : RED);
            case BiobuzzField.RETURN_POLLEN -> returnPollen();
            default -> throw new IllegalArgumentException("BIOBUZZ has no human action " + humanActionId);
        }
    }

    /**
     * A human enters one NECTAR of an alliance into its LOADING ZONE: once for each TIP of that alliance's HIVE, and
     * freely once the last minute starts; one that left the field may always come back. Nothing happens when none of
     * that holds or none is left.
     */
    private void loadNectar(int colour) {
        if (phase == MatchPhase.ENDED) {
            return;
        }
        boolean freely = oneMinuteLeft() || nectarReturned[colour] > 0;
        int tipsOfColour = autoTips[colour] + teleopTips[colour];
        if (!freely && tipsOfColour - nectarEnteredForTips[colour] <= 0) {
            return;
        }
        int kind = colour == RED ? BiobuzzIds.KIND_NECTAR_RED : BiobuzzIds.KIND_NECTAR_BLUE;
        OptionalInt id = nectarWithHumans.take(kind);
        if (id.isEmpty()) {
            return;
        }
        if (nectarReturned[colour] > 0) {
            nectarReturned[colour]--;
        } else if (!freely) {
            nectarEnteredForTips[colour]++;
        }
        double side = colour == RED ? -1 : 1;
        double x = side * 35.255 + LOAD_OFFSETS_INCHES[nectarLoads[colour]++ % LOAD_OFFSETS_INCHES.length];
        world.spawnPiece(id.getAsInt(), kind, PieceModel.NECTAR, new Vector3(in(x), side * in(64.8), 0.2), Vector3.ZERO);
    }

    /** Field staff put the POLLEN that has been outside longest back in, just inside the wall where it left. */
    private void returnPollen() {
        if (phase == MatchPhase.ENDED) {
            return;
        }
        OptionalInt id = pollenOutside.take(BiobuzzIds.KIND_POLLEN);
        if (id.isEmpty()) {
            return;
        }
        double[] left = whereItLeft.remove(id.getAsInt());
        double limit = FieldGeometry.HALF_M - 0.20;
        double x = Math.max(-limit, Math.min(limit, left[0]));
        double y = Math.max(-limit, Math.min(limit, left[1]));
        world.spawnPiece(id.getAsInt(), BiobuzzIds.KIND_POLLEN, PieceModel.POLLEN, new Vector3(x, y, 0.25), Vector3.ZERO);
    }

    /** Hands out one of the robot's four preload POLLEN; NECTAR is never handed out, it only enters through the humans. */
    @Override
    public OptionalInt takeFromReserve(int kind) {
        return kind == BiobuzzIds.KIND_POLLEN ? preloads.take(kind) : OptionalInt.empty();
    }

    /** Three of the robot's four preload POLLEN, which is what its magazine holds. */
    @Override
    public int[] robotPreloads() {
        return new int[] {BiobuzzIds.KIND_POLLEN, BiobuzzIds.KIND_POLLEN, BiobuzzIds.KIND_POLLEN};
    }

    @Override
    public Score score() {
        return finalScore != null ? finalScore : assemble(world.snapshot());
    }

    private Score assemble(WorldSnapshot snapshot) {
        Assessment now = Assessment.of(snapshot, red, robotLength, robotWidth);
        int mine = red ? RED : BLUE;
        boolean live = phase == MatchPhase.PRACTICE;
        int leave = (live ? now.leftWall() : autoAssessed && leftWallAtEndOfAuto) ? 1 : 0;
        int autoPark = (!live && autoAssessed && parkedAtEndOfAuto) ? 1 : 0;
        int teleopPark = now.parked() ? 1 : 0;
        return new Score(List.of(
                new Score.Line("LEAVE", leave, 3 * leave),
                new Score.Line("AUTO PARK", autoPark, 5 * autoPark),
                new Score.Line("HIVE TIP in AUTO", autoTips[mine], 20 * autoTips[mine]),
                new Score.Line("HIVE TIP in TELEOP", teleopTips[mine], 20 * teleopTips[mine]),
                new Score.Line("POLLEN and NECTAR in CELL", now.cellPieces(), 2 * now.cellPieces()),
                new Score.Line("FLOWER bottom NECTAR bonus", now.bottomNectar(), 5 * now.bottomNectar()),
                new Score.Line("POLLEN and NECTAR in owned FLOWER", now.ownedFlowerPieces(), 2 * now.ownedFlowerPieces()),
                new Score.Line("POLLEN and NECTAR in GARDEN", now.gardenPieces(), now.gardenPieces()),
                new Score.Line("TELEOP PARK", teleopPark, 5 * teleopPark)));
    }
}
