package dev.ftcsim.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.Vector3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Zones, storage and launch with a real physics world and motors that the test turns by hand. */
class MechanismsTest {
    private static final double DT = 1.0 / PhysicsWorld.STEPS_PER_SECOND;
    private static final int PIECE_KIND = 100;
    private static final float RADIUS = 0.0635f;
    private static final PieceSpec PIECE = new PieceSpec(RADIUS, 0.07f, 0.5f, 0.2f, 0.02f);

    /** Motors the test spins by setting their speed; launching takes energy from them like a real flywheel. */
    private static final class FakePlants implements PlantView {
        final Map<String, Double> speeds = new HashMap<>();
        final Map<String, Double> energyTaken = new HashMap<>();

        @Override
        public boolean hasShaft(String motor) {
            return List.of("intake", "up", "shooterLeft", "shooterRight").contains(motor);
        }

        @Override
        public double shaftSpeedRadPerSec(String motor) {
            return speeds.getOrDefault(motor, 0.0);
        }

        @Override
        public void extractEnergy(String motor, double joules) {
            energyTaken.merge(motor, joules, Double::sum);
        }
    }

    private static final Vec3 ENTRANCE = new Vec3(0.30, 0, RADIUS);
    private static final Vec3 STAGE = new Vec3(-0.05, 0, 0.30);

    private static StorageSpec magazine() {
        return new StorageSpec(
                "magazine",
                3,
                List.of(ENTRANCE, STAGE),
                List.of(new StorageSpec.Drive("intake", 0.025, 1, 0.0, 0.25), new StorageSpec.Drive("up", 0.02, 1, 0.1, 1.0)));
    }

    private static CaptureSpec intakeZone() {
        return new CaptureSpec(
                "intake", "magazine", new Volume.Box(new Vec3(0.30, 0, RADIUS), new Vec3(0.05, 0.20, 0.10)),
                List.of(Condition.ShaftSpeed.atLeast("intake", 10.0)));
    }

    private static LaunchSpec shooter() {
        // Staging runs the conveyor slowly; only the faster feed pushes a piece into the wheels.
        return new LaunchSpec(
                "shooter", "magazine", List.of(Condition.ShaftSpeed.atLeast("up", 20.0)),
                List.of(new LaunchSpec.Wheel("shooterLeft", 0.05, 1), new LaunchSpec.Wheel("shooterRight", 0.05, -1)),
                0.8, new Vec3(-0.6, 0, 0.8), 0.3);
    }

    private static final double STAGING = 10.0;
    private static final double FEEDING = 30.0;

    private final BodyPose robot = BodyPose.planar(0, 0, 0, 0, 0, 0, 0);
    private FakePlants plants;
    private PhysicsWorld world;
    private Mechanisms mechanisms;

    @BeforeEach
    void setUp() throws IOException {
        plants = new FakePlants();
        world = PhysicsWorld.create(Files.createDirectories(Path.of(System.getProperty("ftcsim.nativeDirectory"))));
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(5, 5, 0.5));
        mechanisms = new Mechanisms(List.of(magazine()), List.of(intakeZone()), List.of(shooter()), plants, Map.of(PIECE_KIND, PIECE));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void step(int count) {
        for (int i = 0; i < count; i++) {
            world.step();
            mechanisms.step(DT, robot, world.snapshot(), world);
        }
    }

    private void drop(int id, double x, double y) {
        world.spawnPiece(id, PIECE_KIND, PIECE, new Vector3(x, y, RADIUS), Vector3.ZERO);
    }

    @Test
    void anIntakeTakesInAPieceInsideItsVolumeOnlyWhileItsRollerSpinsFastEnough() {
        drop(1000, 0.30, 0.0);

        step(20);
        assertTrue(world.hasPiece(1000), "a stopped roller takes nothing in");

        plants.speeds.put("intake", 40.0);
        step(2);

        assertFalse(world.hasPiece(1000), "the piece left the world when the roller took it");
        assertEquals(List.of(1000), mechanisms.contents("magazine"));
        assertEquals(1, mechanisms.carried().size());
    }

    @Test
    void aPieceOutsideTheVolumeStaysWhereItIs() {
        plants.speeds.put("intake", 40.0);
        drop(1000, 0.60, 0.0);
        drop(1001, 0.30, 0.45);

        step(10);

        assertTrue(world.hasPiece(1000) && world.hasPiece(1001));
        assertEquals(List.of(), mechanisms.contents("magazine"));
    }

    @Test
    void piecesRideTheConveyorAndWaitOneDiameterApartAtTheEndOfThePath() {
        plants.speeds.put("intake", 40.0);
        plants.speeds.put("up", STAGING);
        double length = magazine().lengthMeters();

        for (int id = 1000; id < 1003; id++) {
            drop(id, 0.30, 0.0);
            step(60);
        }
        step(900);

        assertEquals(List.of(1000, 1001, 1002), mechanisms.contents("magazine"), "the first piece in is at the front");
        List<Mechanisms.Carried> carried = mechanisms.carried();
        Vec3 stage = robot.pointToField(STAGE);
        Vec3 first = carried.get(0).center();
        assertEquals(0.0, first.minus(stage).length(), 0.01, "the front piece sits at the end of the path");
        double spacing = carried.get(0).center().minus(carried.get(1).center()).length();
        assertEquals(2 * RADIUS, spacing, 0.005, "pieces wait one diameter apart");
        assertTrue(length > 4 * RADIUS, "the path is long enough for the three of them");
    }

    @Test
    void aFullStorageLeavesTheNextPieceInTheWorld() {
        plants.speeds.put("intake", 40.0);
        plants.speeds.put("up", STAGING);
        for (int id = 1000; id < 1003; id++) {
            drop(id, 0.30, 0.0);
            step(100);
        }
        drop(1003, 0.30, 0.0);

        step(100);

        assertEquals(3, mechanisms.contents("magazine").size());
        assertTrue(world.hasPiece(1003), "the fourth piece has nowhere to go");
    }

    @Test
    void aLauncherReleasesTheFrontPieceAtTheSpeedOfItsWheelsAndTheFlywheelsPayForIt() {
        plants.speeds.put("intake", 40.0);
        plants.speeds.put("up", STAGING);
        drop(1000, 0.30, 0.0);
        step(600);
        assertEquals(List.of(1000), mechanisms.contents("magazine"));

        // 100 rad/s on a 5 cm wheel is 5 m/s at the rim; the piece leaves with 80 percent of that.
        plants.speeds.put("shooterLeft", 100.0);
        plants.speeds.put("shooterRight", -100.0);
        plants.speeds.put("up", FEEDING);
        world.step();
        mechanisms.step(DT, robot, world.snapshot(), world);

        assertTrue(world.hasPiece(1000), "the piece is back in the world");
        assertEquals(List.of(), mechanisms.contents("magazine"));
        BodyPose launched = world.bodyPose(1000);
        double speed = launched.velocity().length();
        assertEquals(4.0, speed, 0.1);
        assertEquals(-0.6 * 4.0, launched.vx(), 0.1, "it leaves along the launch direction, which points back and up");
        assertEquals(0.8 * 4.0, launched.vz(), 0.2);
        double expectedEnergy = 0.5 * 0.07 * 4.0 * 4.0 / 2.0;
        assertEquals(expectedEnergy, plants.energyTaken.get("shooterLeft"), 1e-6);
        assertEquals(expectedEnergy, plants.energyTaken.get("shooterRight"), 1e-6);
    }

    @Test
    void aLauncherWaitsForItsFeedAndAMovingRobotAddsItsOwnVelocityToTheShot() {
        plants.speeds.put("intake", 40.0);
        plants.speeds.put("up", STAGING);
        drop(1000, 0.30, 0.0);
        step(600);
        plants.speeds.put("shooterLeft", 100.0);
        plants.speeds.put("shooterRight", -100.0);
        step(10);
        assertEquals(List.of(1000), mechanisms.contents("magazine"), "spinning wheels and a slow conveyor launch nothing");

        BodyPose moving = BodyPose.planar(0, 0, 0, 0, 2.0, 0.0, 0.0);
        plants.speeds.put("up", FEEDING);
        world.step();
        mechanisms.step(DT, moving, world.snapshot(), world);

        assertEquals(2.0 - 0.6 * 4.0, world.bodyPose(1000).vx(), 0.1);
    }

    @Test
    void aConveyorRunBackwardsPushesTheRearPieceOutOfTheEntranceUnderItsOwnId() {
        plants.speeds.put("intake", 40.0);
        drop(1000, 0.30, 0.0);
        step(30);
        assertEquals(List.of(1000), mechanisms.contents("magazine"));

        plants.speeds.put("intake", -40.0);
        step(120);

        assertTrue(world.hasPiece(1000), "the piece came out again");
        assertEquals(List.of(), mechanisms.contents("magazine"));
    }

    @Test
    void piecesLoadedByHandFillTheStorageFromTheFrontAndTheStorageRefusesMoreThanItHolds() {
        assertTrue(mechanisms.load("magazine", 2000, PIECE_KIND));
        assertTrue(mechanisms.load("magazine", 2001, PIECE_KIND));
        assertTrue(mechanisms.load("magazine", 2002, PIECE_KIND));
        assertFalse(mechanisms.load("magazine", 2003, PIECE_KIND), "the magazine holds three");

        assertEquals(List.of(2000, 2001, 2002), mechanisms.contents("magazine"));
        step(1);
        Vec3 stage = robot.pointToField(STAGE);
        assertEquals(0.0, mechanisms.carried().get(0).center().minus(stage).length(), 0.01);
        assertThrows(IllegalArgumentException.class, () -> mechanisms.load("magazine", 2004, 999));
    }

    @Test
    void anIntakeLeavesAlonePiecesOfASizeItIsNotBuiltFor() {
        int smallKind = 101;
        PieceSpec small = new PieceSpec(0.0355f, 0.02f, 0.5f, 0.2f, 0.02f);
        CaptureSpec forBigPieces = new CaptureSpec(
                "intake", "magazine", new Volume.Box(new Vec3(0.30, 0, RADIUS), new Vec3(0.05, 0.20, 0.10)),
                List.of(Condition.ShaftSpeed.atLeast("intake", 10.0)), 0.10, 0.15);
        mechanisms = new Mechanisms(
                List.of(magazine()), List.of(forBigPieces), List.of(), plants, Map.of(PIECE_KIND, PIECE, smallKind, small));
        world.spawnPiece(1001, smallKind, small, new Vector3(0.30, 0.10, 0.0355), Vector3.ZERO);
        drop(1000, 0.30, -0.10);
        plants.speeds.put("intake", 40.0);

        step(60);

        assertFalse(world.hasPiece(1000), "a piece of the size the intake is built for is taken in");
        assertTrue(world.hasPiece(1001), "a smaller piece in the same volume is left in the world");
        assertEquals(List.of(1000), mechanisms.contents("magazine"));
        assertThrows(IllegalArgumentException.class, () -> new CaptureSpec(
                "bad", "magazine", new Volume.Box(Vec3.ZERO, new Vec3(1, 1, 1)), List.of(), 0.15, 0.10));
    }

    @Test
    void mechanismsThatNameWhatDoesNotExistAreRefusedWhenTheyAreBuilt() {
        StorageSpec lonely = magazine();
        assertThrows(IllegalArgumentException.class, () -> new Mechanisms(
                List.of(lonely), List.of(new CaptureSpec("c", "nowhere", new Volume.Box(Vec3.ZERO, new Vec3(1, 1, 1)), List.of())),
                List.of(), plants, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Mechanisms(
                List.of(new StorageSpec("s", 1, List.of(Vec3.ZERO, new Vec3(1, 0, 0)), List.of(new StorageSpec.Drive("ghost", 0.02, 1, 0, 1)))),
                List.of(), List.of(), plants, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Mechanisms(
                List.of(lonely, lonely), List.of(), List.of(), plants, Map.of()));
    }

    @Test
    void theSameRunGivesTheSameWorldAndTheSameCarriedPieces() throws IOException {
        String firstHash = null;
        List<Mechanisms.Carried> firstCarried = null;
        for (int run = 0; run < 2; run++) {
            if (run == 1) {
                tearDown();
                setUp();
            }
            plants.speeds.put("intake", 40.0);
            plants.speeds.put("up", STAGING);
            drop(1000, 0.30, 0.01);
            drop(1001, 0.31, -0.02);
            step(200);
            plants.speeds.put("shooterLeft", 90.0);
            plants.speeds.put("shooterRight", -90.0);
            plants.speeds.put("up", FEEDING);
            step(100);
            if (firstHash == null) {
                firstHash = world.stateHash();
                firstCarried = mechanisms.carried();
            } else {
                assertEquals(firstHash, world.stateHash());
                assertEquals(firstCarried, mechanisms.carried());
            }
        }
    }
}
