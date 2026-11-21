package dev.ftcsim.link.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WireCodecTest {
    /** The dashboard address that ends a status: its length and its text. */
    private static final int DASHBOARD_FIELD_BYTES = 4 + "http://127.0.0.1:8001/".length();

    private static Command roundTrip(Command command) throws WireException {
        return WireCodec.decodeCommand(WireCodec.encode(command));
    }

    private static Event roundTrip(Event event) throws WireException {
        return WireCodec.decodeEvent(WireCodec.encode(event));
    }

    @Test
    void everyCommandComesBackEqualAndTheSameCommandAlwaysEncodesToTheSameBytes() throws WireException {
        List<Command> commands = List.of(
                new Command.Open(Samples.request()),
                new Command.Init("Zażółć gęślą jaźń ✓ \uD83D\uDE00"),
                new Command.Start(),
                new Command.Stop(),
                new Command.SetGamepad(1, Samples.gamepad()),
                new Command.SetGamepad(2, GamepadState.NEUTRAL),
                new Command.PerformHumanAction("feed"),
                new Command.SetPaused(true),
                new Command.SetPaused(false),
                new Command.SetSpeed(0.25),
                new Command.Reset(),
                new Command.ArmMatch("Zażółć Auto ✓", "Counting"),
                new Command.ArmMatch("", "Counting"),
                new Command.StartMatch(),
                new Command.Close());

        for (Command command : commands) {
            assertEquals(command, roundTrip(command));
            assertArrayEquals(WireCodec.encode(command), WireCodec.encode(roundTrip(command)), command.toString());
        }
    }

    @Test
    void everyEventComesBackEqual() throws WireException {
        List<Event> events = List.of(
                new Event.Opened(Samples.info(true)),
                new Event.Opened(Samples.info(false)),
                new Event.Snapshot(Samples.snapshot()),
                new Event.Snapshot(new WorldSnapshot(0, 0, List.of())),
                new Event.Status(Samples.status()),
                new Event.Failed("the profile 'x' does not exist"),
                new Event.Failed(""),
                new Event.Refused("A match is already armed. Start it, or press Stop to give it up."),
                new Event.Refused(""),
                new Event.Log(List.of("I/System.out: hello", "E/Robot: ")),
                new Event.Log(List.of()),
                new Event.Catalogue(Samples.catalogue()),
                new Event.Catalogue(new Catalogue(List.of())));

        for (Event event : events) {
            assertEquals(event, roundTrip(event));
        }
    }

    @Test
    void everyStateOfAMatchSurvivesInAStatusWithItsTimes() throws WireException {
        for (MatchState state : MatchState.values()) {
            for (MatchStatus match : List.of(new MatchStatus(state, 0, 0), new MatchStatus(state, 7_123_456_789L, 120_000_000_000L))) {
                Event.Status back = assertInstanceOf(Event.Status.class, roundTrip(new Event.Status(Samples.status(match))));
                assertEquals(match, back.status().match());
                assertEquals(Samples.status(match), back.status());
            }
        }
    }

    @Test
    void aSnapshotComesBackBitForBitEvenForNumbersThatCompareEqualToOthers() throws WireException {
        WorldSnapshot snapshot = Samples.snapshot();

        WorldSnapshot back = assertInstanceOf(Event.Snapshot.class, roundTrip(new Event.Snapshot(snapshot))).snapshot();

        for (int i = 0; i < snapshot.bodies().size(); i++) {
            BodyState sent = snapshot.bodies().get(i);
            BodyState got = back.bodies().get(i);
            assertEquals(floatBits(sent), floatBits(got), "body " + sent.entityId());
        }
        BodyState special = back.bodies().get(0);
        assertEquals(0x7FC01234, Float.floatToRawIntBits(special.qx()), "the payload of a NaN is kept");
        assertEquals(0x80000000, Float.floatToRawIntBits(special.x()), "negative zero stays negative");
        assertEquals(Float.floatToRawIntBits(Float.MIN_VALUE), Float.floatToRawIntBits(special.y()));
    }

    private static List<Integer> floatBits(BodyState body) {
        return List.of(
                body.entityId(), body.kind(), Float.floatToRawIntBits(body.x()), Float.floatToRawIntBits(body.y()),
                Float.floatToRawIntBits(body.z()), Float.floatToRawIntBits(body.qx()), Float.floatToRawIntBits(body.qy()),
                Float.floatToRawIntBits(body.qz()), Float.floatToRawIntBits(body.qw()));
    }

    @Test
    void aGamepadKeepsEveryStickBitAndEveryButton() throws WireException {
        for (GamepadState pad : List.of(Samples.gamepad(), Samples.everyButtonDown(), GamepadState.NEUTRAL)) {
            Command.SetGamepad back = assertInstanceOf(Command.SetGamepad.class, roundTrip(new Command.SetGamepad(2, pad)));
            assertEquals(pad, back.state());
        }
        Command.SetGamepad back = assertInstanceOf(Command.SetGamepad.class, roundTrip(new Command.SetGamepad(1, Samples.gamepad())));
        assertEquals(0x7FC01234, Float.floatToRawIntBits(back.state().rightStickX()));
        assertEquals(0x80000000, Float.floatToRawIntBits(back.state().rightStickY()));
    }

    @Test
    void textBeyondTheBoundIsCutAtACharacterBoundaryAndEndsInAnEllipsisInsteadOfFailingTheMessage() throws WireException {
        String threeByteCharacters = "€".repeat(Protocol.MAX_STRING_BYTES / 3 + 10);

        Event.Failed back = assertInstanceOf(Event.Failed.class, roundTrip(new Event.Failed(threeByteCharacters)));

        assertTrue(back.message().endsWith("…"));
        assertTrue(back.message().getBytes(StandardCharsets.UTF_8).length <= Protocol.MAX_STRING_BYTES);
        assertTrue(back.message().startsWith("€€€€"));
    }

    @Test
    void aListBeyondTheBoundCannotBeWritten() {
        Event.Snapshot tooMany = new Event.Snapshot(new WorldSnapshot(0, 0, bodies(Protocol.MAX_LIST + 1)));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> WireCodec.encode(tooMany));

        assertTrue(refused.getMessage().contains("the bodies"), refused.getMessage());
    }

    private static List<BodyState> bodies(int count) {
        BodyState[] bodies = new BodyState[count];
        for (int i = 0; i < count; i++) {
            bodies[i] = new BodyState(i, 1, 0, 0, 0, 0, 0, 0, 1);
        }
        return Arrays.asList(bodies);
    }

    // Malformed input ---------------------------------------------------------------------------------------------

    @Test
    void anUnknownKindIsAnErrorAndNeverSkipped() {
        assertThrows(WireException.class, () -> WireCodec.decodeCommand(new byte[] {0x7F}));
        assertThrows(WireException.class, () -> WireCodec.decodeEvent(new byte[] {0x7F}));
        assertThrows(WireException.class, () -> WireCodec.decodeCommand(new byte[] {0x00}));
        assertThrows(WireException.class, () -> WireCodec.decodeCommand(new byte[0]));
    }

    @Test
    void aEventIsNotACommandAndACommandIsNotAnEvent() {
        byte[] event = WireCodec.encode(new Event.Failed("x"));
        byte[] command = WireCodec.encode(new Command.Start());

        assertThrows(WireException.class, () -> WireCodec.decodeCommand(event));
        assertThrows(WireException.class, () -> WireCodec.decodeEvent(command));
    }

    @Test
    void bytesThatFollowTheEndOfAMessageAreAnError() {
        byte[] start = WireCodec.encode(new Command.Start());
        byte[] longer = Arrays.copyOf(start, start.length + 1);

        WireException error = assertThrows(WireException.class, () -> WireCodec.decodeCommand(longer));

        assertTrue(error.getMessage().contains("unexpected bytes"), error.getMessage());
    }

    @Test
    void everyMessageCutShortAnywhereIsAWireExceptionAndNothingElse() {
        List<byte[]> commands = List.of(
                WireCodec.encode(new Command.Open(Samples.request())), WireCodec.encode(new Command.SetGamepad(1, Samples.gamepad())),
                WireCodec.encode(new Command.Init("Mecanum Drive")), WireCodec.encode(new Command.SetSpeed(2.0)),
                WireCodec.encode(new Command.ArmMatch("Linear Auto", "Counting")));
        List<byte[]> events = List.of(
                WireCodec.encode(new Event.Opened(Samples.info(true))), WireCodec.encode(new Event.Snapshot(Samples.snapshot())),
                WireCodec.encode(new Event.Status(Samples.status())), WireCodec.encode(new Event.Failed("boom")),
                WireCodec.encode(new Event.Refused("not now")), WireCodec.encode(new Event.Catalogue(Samples.catalogue())));

        for (byte[] whole : commands) {
            for (int length = 0; length < whole.length; length++) {
                byte[] cut = Arrays.copyOf(whole, length);
                assertThrows(WireException.class, () -> WireCodec.decodeCommand(cut), "command cut to " + length + " of " + whole.length);
            }
        }
        for (byte[] whole : events) {
            for (int length = 0; length < whole.length; length++) {
                byte[] cut = Arrays.copyOf(whole, length);
                assertThrows(WireException.class, () -> WireCodec.decodeEvent(cut), "event cut to " + length + " of " + whole.length);
            }
        }
    }

    @Test
    void aListThatClaimsMoreEntriesThanTheMessageHasBytesForIsRefusedBeforeAnythingIsAllocatedForIt() {
        for (int claimed : new int[] {Protocol.MAX_LIST, 1, Integer.MAX_VALUE, Protocol.MAX_LIST + 1, -1, Integer.MIN_VALUE}) {
            WireWriter w = new WireWriter(32);
            w.unsignedByte(Protocol.SNAPSHOT);
            w.longInteger(1);
            w.longInteger(2);
            w.integer(claimed);

            WireException error = assertThrows(WireException.class, () -> WireCodec.decodeEvent(w.finish()), "claimed " + claimed);

            assertTrue(error.getMessage().contains("the bodies"), error.getMessage());
        }
    }

    @Test
    void textThatClaimsMoreBytesThanThereAreOrMoreThanTheBoundIsRefused() {
        for (int claimed : new int[] {5, Protocol.MAX_STRING_BYTES, Protocol.MAX_STRING_BYTES + 1, Integer.MAX_VALUE, -1}) {
            WireWriter w = new WireWriter(16);
            w.unsignedByte(Protocol.FAILED);
            w.integer(claimed);
            w.unsignedByte('a');

            assertThrows(WireException.class, () -> WireCodec.decodeEvent(w.finish()), "claimed " + claimed);
        }
    }

    @Test
    void textThatIsNotValidUtf8IsRefused() {
        WireWriter w = new WireWriter(16);
        w.unsignedByte(Protocol.FAILED);
        w.integer(2);
        w.unsignedByte(0xC3);
        w.unsignedByte(0x28);

        WireException error = assertThrows(WireException.class, () -> WireCodec.decodeEvent(w.finish()));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void aFlagThatIsNeitherZeroNorOneIsRefused() {
        WireWriter w = new WireWriter(4);
        w.unsignedByte(Protocol.SET_PAUSED);
        w.unsignedByte(2);

        assertThrows(WireException.class, () -> WireCodec.decodeCommand(w.finish()));
    }

    @Test
    void aMatchStateOfNoSuchCodeAndPeriodTimesThatCannotBeAreRefused() {
        byte[] status = WireCodec.encode(new Event.Status(Samples.status(new MatchStatus(MatchState.TELEOP, 5, 10))));
        int unplaced = 4 + Samples.status().unmapped().stream().mapToInt(device -> 1 + 4 + device.name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum()
                + DASHBOARD_FIELD_BYTES;
        int stateAt = status.length - unplaced - 17;
        byte[] unknown = status.clone();
        unknown[stateAt] = 9;
        byte[] negative = status.clone();
        negative[stateAt + 1] = (byte) 0x80;

        WireException error = assertThrows(WireException.class, () -> WireCodec.decodeEvent(unknown));

        assertTrue(error.getMessage().contains("match state"), error.getMessage());
        assertThrows(WireException.class, () -> WireCodec.decodeEvent(negative));
    }

    @Test
    void theNamesThatTheProfileDoesNotPlaceKeepTheirKindsAndAKindOfNoSuchCodeIsRefused() throws WireException {
        Event.Status back = assertInstanceOf(Event.Status.class, roundTrip(new Event.Status(Samples.status())));
        assertEquals(Samples.status().unmapped(), back.status().unmapped());
        assertEquals("http://127.0.0.1:8001/", back.status().dashboardUrl());

        byte[] status = WireCodec.encode(new Event.Status(Samples.status()));
        int firstKindAt = status.length - DASHBOARD_FIELD_BYTES - (1 + 4 + "upSensor".length()) - (1 + 4 + "shooterLeft".length());
        byte[] unknown = status.clone();
        unknown[firstKindAt] = 9;

        WireException error = assertThrows(WireException.class, () -> WireCodec.decodeEvent(unknown));

        assertTrue(error.getMessage().contains("unplaced device kind"), error.getMessage());
    }

    @Test
    void aStatusThatClaimsMoreUnplacedNamesThanItHasBytesForIsRefusedBeforeAnythingIsAllocatedForIt() {
        byte[] status = WireCodec.encode(new Event.Status(Samples.status()));
        int countAt = status.length - DASHBOARD_FIELD_BYTES - (1 + 4 + "upSensor".length()) - (1 + 4 + "shooterLeft".length()) - 4;
        byte[] greedy = status.clone();
        greedy[countAt] = 0x00;
        greedy[countAt + 1] = 0x01;
        greedy[countAt + 2] = 0x00;
        greedy[countAt + 3] = 0x00;

        WireException error = assertThrows(WireException.class, () -> WireCodec.decodeEvent(greedy));

        assertTrue(error.getMessage().contains("the unplaced names"), error.getMessage());
    }

    @Test
    void aCatalogueThatClaimsMoreFieldsThanItHasBytesForIsRefusedBeforeAnythingIsAllocatedForIt() {
        WireWriter w = new WireWriter(8);
        w.unsignedByte(Protocol.CATALOGUE);
        w.integer(Protocol.MAX_LIST);

        WireException error = assertThrows(WireException.class, () -> WireCodec.decodeEvent(w.finish()));

        assertTrue(error.getMessage().contains("the fields"), error.getMessage());
    }

    @Test
    void aSnapshotWhoseBodiesAreNotInOrderIsRefusedWithAWireExceptionAndNotAnUncheckedOne() {
        WireWriter w = new WireWriter(100);
        w.unsignedByte(Protocol.SNAPSHOT);
        w.longInteger(1);
        w.longInteger(2);
        w.integer(2);
        for (int id : new int[] {5, 3}) {
            w.integer(id);
            w.integer(1);
            for (int i = 0; i < 7; i++) {
                w.floatBits(0f);
            }
        }

        w.integer(0);
        WireException error = assertThrows(WireException.class, () -> WireCodec.decodeEvent(w.finish()));

        assertTrue(error.getMessage().contains("strictly ordered"), error.getMessage());
    }

    @Test
    void aGamepadNumberIsNotTheDecodersToJudgeSoTheWorkerCanRefuseItInsteadOfDroppingTheConnection() throws WireException {
        for (int number : new int[] {0, 3, 255}) {
            WireWriter pad = new WireWriter(40);
            pad.unsignedByte(Protocol.SET_GAMEPAD);
            pad.unsignedByte(number);
            for (int i = 0; i < 6; i++) {
                pad.floatBits(0f);
            }
            pad.unsignedShort(0);

            Command.SetGamepad decoded = assertInstanceOf(Command.SetGamepad.class, WireCodec.decodeCommand(pad.finish()));

            assertEquals(number, decoded.gamepad());
        }
    }

    @Test
    void valuesThatNoCommandCanHaveAreRefused() {
        WireWriter extraButton = new WireWriter(40);
        extraButton.unsignedByte(Protocol.SET_GAMEPAD);
        extraButton.unsignedByte(1);
        for (int i = 0; i < 6; i++) {
            extraButton.floatBits(0f);
        }
        extraButton.unsignedShort(0x8000);
        assertThrows(WireException.class, () -> WireCodec.decodeCommand(extraButton.finish()), "a sixteenth button");

        for (double speed : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            WireWriter w = new WireWriter(16);
            w.unsignedByte(Protocol.SET_SPEED);
            w.doubleBits(speed);
            assertThrows(WireException.class, () -> WireCodec.decodeCommand(w.finish()), "speed " + speed);
        }

        WireWriter alliance = new WireWriter(100);
        alliance.unsignedByte(Protocol.OPEN);
        alliance.string("robot");
        alliance.integer(0);
        alliance.doubleBits(11.3);
        alliance.doubleBits(96);
        alliance.string("goBILDA-bare-6000");
        alliance.doubleBits(1);
        alliance.string("UP");
        alliance.string("FORWARD");
        for (int i = 0; i < 6; i++) {
            alliance.doubleBits(0);
        }
        for (String text : new String[] {"q", "none"}) {
            alliance.string(text);
        }
        alliance.unsignedByte(9);
        alliance.longInteger(0);
        for (int i = 0; i < 4; i++) {
            alliance.doubleBits(0);
        }
        assertThrows(WireException.class, () -> WireCodec.decodeCommand(alliance.finish()), "alliance 9");
    }
}
