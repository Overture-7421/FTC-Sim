package dev.ftcsim.game.decode;

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
import dev.ftcsim.physics.Vector3;
import java.util.List;
import java.util.OptionalInt;

/**
 * A DECODE match in progress: the rules of the two CLASSIFIERS, the scoring of the robot's alliance and the people at
 * the field. Everything that is physical lives in the world; this class only reads it and, where the rules say so,
 * moves a piece in or out of it.
 */
public final class DecodeRun implements FieldRun {

    private final PhysicsWorld world;
    private final FieldSetup setup;
    private final Layout layout;
    private final Classifier blueClassifier;
    private final Classifier redClassifier;
    private final Classifier own;
    private final PieceReserve reserve = new PieceReserve();
    private final PieceReserve opponentReserve = new PieceReserve();
    private final MatchClock clock = new MatchClock();
    private final ScoreBoard board = new ScoreBoard();
    private final Assessor assessor;
    private final HumanPlayer human;
    private final OutOfBounds boundary = new OutOfBounds();
    private final RestDetector piecesAtRest = new RestDetector(false);
    private final RestDetector everythingAtRest = new RestDetector(true);
    private WorldSnapshot latest;

    DecodeRun(PhysicsWorld world, FieldSetup setup, Layout layout, Pivot blueGate, Pivot redGate) {
        this.world = world;
        this.setup = setup;
        this.layout = layout;
        this.blueClassifier = new Classifier(Alliance.BLUE, world, blueGate);
        this.redClassifier = new Classifier(Alliance.RED, world, redGate);
        this.own = setup.alliance() == Alliance.BLUE ? blueClassifier : redClassifier;
        this.assessor = new Assessor(setup.alliance(), layout, setup.robotLengthMeters(), setup.robotWidthMeters());
        this.human = new HumanPlayer(setup.alliance());
        for (int id : layout.allianceArea(setup.alliance())) {
            reserve.add(layout.kindOf(id), id);
        }
        Alliance opponent = setup.alliance() == Alliance.BLUE ? Alliance.RED : Alliance.BLUE;
        for (int id : layout.allianceArea(opponent)) {
            opponentReserve.add(layout.kindOf(id), id);
        }
    }

    @Override
    public void afterStep(WorldSnapshot snapshot) {
        latest = snapshot;
        for (Classifier classifier : List.of(blueClassifier, redClassifier)) {
            for (Classifier.Scored scored : classifier.update(snapshot)) {
                if (scored.alliance() == setup.alliance()) {
                    board.record(scored.classified(), clock.scoresAsAuto());
                }
            }
        }
        for (int id : boundary.update(snapshot)) {
            giveBackToAHumanPlayer(snapshot, id);
        }
        latchWhatTheRulesReadNow(snapshot);
    }

    // Artifacts that left the field are returned to the human player nearest to where they came to rest.
    private void giveBackToAHumanPlayer(WorldSnapshot snapshot, int id) {
        BodyState piece = snapshot.bodies().stream().filter(body -> body.entityId() == id).findFirst().orElseThrow();
        Alliance nearest = piece.y() >= 0 ? Alliance.BLUE : Alliance.RED;
        world.removePiece(id);
        (nearest == setup.alliance() ? reserve : opponentReserve).add(layout.kindOf(id), id);
    }

    private void latchWhatTheRulesReadNow(WorldSnapshot snapshot) {
        boolean piecesRest = piecesAtRest.update(snapshot);
        boolean everythingRest = everythingAtRest.update(snapshot);
        long now = snapshot.simTimeNanos();
        if (clock.autoStarted() && !board.isLeaveLatched() && clock.autoIsOver(now)) {
            board.latchLeave(assessor.assess(snapshot, own).leftLaunchLines());
        }
        if (board.isLeaveLatched() && !board.isAutoPatternLatched() && piecesRest) {
            board.latchAutoPattern(assessor.assess(snapshot, own).patternMatches());
        }
        if (clock.hasEnded() && !board.isFinal() && (piecesRest && everythingRest || clock.settleTimeIsUp(now))) {
            board.latchFinal(assessor.assess(snapshot, own));
        }
    }

    @Override
    public void enterPhase(MatchPhase phase, long timeNanos) {
        clock.enter(phase, timeNanos);
        if (phase == MatchPhase.TELEOP && latest != null) {
            // TELEOP has begun, so the referee reads what AUTO left whether or not it has come to rest.
            if (clock.autoStarted() && !board.isLeaveLatched()) {
                board.latchLeave(assessor.assess(latest, own).leftLaunchLines());
            }
            if (clock.autoStarted() && !board.isAutoPatternLatched()) {
                board.latchAutoPattern(assessor.assess(latest, own).patternMatches());
            }
        }
    }

    @Override
    public void perform(String humanActionId) {
        switch (humanActionId) {
            case DecodeDescription.LOAD_PURPLE -> load(Kinds.PURPLE);
            case DecodeDescription.LOAD_GREEN -> load(Kinds.GREEN);
            default -> throw new IllegalArgumentException("unknown human action " + humanActionId);
        }
    }

    // Human players only handle artifacts in their loading zone and only in TELEOP, or while practising.
    private void load(int kind) {
        MatchPhase phase = clock.phase();
        if (phase != MatchPhase.PRACTICE && phase != MatchPhase.TELEOP) {
            return;
        }
        Vector3 slot = human.freeSlot(world.snapshot());
        if (slot == null || reserve.count(kind) == 0) {
            return;
        }
        reserve.take(kind).ifPresent(id -> world.spawnPiece(id, kind, DecodeField.ARTIFACT, slot, Vector3.ZERO));
    }

    @Override
    public OptionalInt takeFromReserve(int kind) {
        return reserve.take(kind);
    }

    @Override
    public Score score() {
        WorldSnapshot view = latest != null ? latest : world.snapshot();
        return board.score(assessor.assess(view, own), clock, clock.autoIsOver(view.simTimeNanos()));
    }

    /** True once the match is over and the pieces have come to rest or their time is up: the score does not change any more. */
    public boolean isSettled() {
        return board.isFinal();
    }

    /** The pattern the OBELISK shows in this run. */
    public String motif() {
        return layout.motif().name();
    }

    /** How far the gate arm of the alliance's classifier has turned open, in radians. */
    double gateAngleRadians(Alliance alliance) {
        return (alliance == Alliance.BLUE ? blueClassifier : redClassifier).gate().angleRadians();
    }

    /** Every artifact the field can account for, in the world or in either reserve; pieces a mechanism holds are not among them. */
    int artifactsAccountedFor(WorldSnapshot snapshot) {
        long inWorld = snapshot.bodies().stream().filter(body -> Kinds.isArtifact(body.kind())).count();
        return (int) inWorld + reserve.count(Kinds.PURPLE) + reserve.count(Kinds.GREEN)
                + opponentReserve.count(Kinds.PURPLE) + opponentReserve.count(Kinds.GREEN);
    }
}
