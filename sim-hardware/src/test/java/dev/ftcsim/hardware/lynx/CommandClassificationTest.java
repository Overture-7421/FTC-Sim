package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.MUTATING;
import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CommandClassificationTest {

    private static final List<byte[]> PAYLOADS = List.of(new byte[0], new byte[] {0}, new byte[] {1}, new byte[16]);

    private final LynxHub hub = new LynxHub(HubConfig.controlHub(), () -> 12.0, 0);

    @Test
    void everyCommandOfTheProtocolIsRegisteredAndSaysWhatItDoes() {
        Set<String> expected = new TreeSet<>();
        Arrays.stream(DekaCommand.values()).forEach(command -> expected.add(command.name()));
        Arrays.stream(StandardCommand.values())
                .filter(command -> command != StandardCommand.ACK && command != StandardCommand.NACK)
                .forEach(command -> expected.add(command.name()));

        Set<String> registered = new TreeSet<>();
        for (CommandTable.Entry entry : hub.commandTable().entries()) {
            registered.add(entry.name());
            for (byte[] payload : PAYLOADS) {
                assertNotNull(entry.effect().of(payload), entry.name());
            }
        }

        assertEquals(expected, registered);
    }

    @Test
    void theCatalogOfQueriesHoldsExactlyTheQueriesOfTheTableAndAsksForNothingElse() {
        Set<String> queries = new TreeSet<>();
        for (CommandTable.Entry entry : hub.commandTable().entries()) {
            if (entry.effect().of(new byte[0]) == QUERY) {
                queries.add(entry.name());
            }
        }
        Set<String> catalog = new TreeSet<>();
        for (QueryCatalog.Request request : QueryCatalog.requests()) {
            catalog.add(request.name());
            CommandTable.Entry entry = hub.commandTable().find(request.packetId());
            assertEquals(QUERY, entry.effect().of(request.payload()), request.name());
        }

        assertEquals(queries, catalog);
    }

    @Test
    void whatStartsSomethingChangesSomethingOrTakesAResultAwayFromTheHostIsNotAQuery() {
        for (DekaCommand command : new DekaCommand[] {
            DekaCommand.I2C_WRITE_READ_MULTIPLE_BYTES,
            DekaCommand.I2C_READ_SINGLE_BYTE,
            DekaCommand.I2C_READ_STATUS_QUERY,
            DekaCommand.I2C_WRITE_STATUS_QUERY,
            DekaCommand.I2C_CONFIGURE_CHANNEL,
            DekaCommand.RESET_MOTOR_ENCODER,
            DekaCommand.SET_MOTOR_CONSTANT_POWER,
            DekaCommand.SET_MOTOR_PIDF_COEFFICIENTS,
            DekaCommand.SET_SERVO_PULSE_WIDTH,
            DekaCommand.SET_SINGLE_DIO_OUTPUT,
            DekaCommand.INJECT_DATA_LOG_HINT,
        }) {
            assertEquals(MUTATING, effectOf(command.number(), new byte[0]), command.name());
        }
        for (StandardCommand command : new StandardCommand[] {
            StandardCommand.KEEP_ALIVE,
            StandardCommand.FAIL_SAFE,
            StandardCommand.SET_NEW_MODULE_ADDRESS,
            StandardCommand.SET_MODULE_LED_COLOR,
            StandardCommand.SET_MODULE_LED_PATTERN,
            StandardCommand.DEBUG_LOG_LEVEL,
        }) {
            assertEquals(MUTATING, effectOf(command.number(), new byte[0]), command.name());
        }
        assertEquals(QUERY, effectOf(DekaCommand.I2C_CONFIGURE_QUERY.number(), new byte[0]));
    }

    @Test
    void readingTheModuleStatusIsAQueryUnlessItAsksForTheEventsToBeCleared() {
        int status = StandardCommand.GET_MODULE_STATUS.number();

        assertEquals(QUERY, effectOf(status, Bytes.of().u8(0).build()));
        assertEquals(MUTATING, effectOf(status, Bytes.of().u8(1).build()));
    }

    @Test
    void aQueryFromAThreadTheSimulationDoesNotScheduleChangesNothingTheHostCanObserve() {
        Rig rig = new Rig();
        rig.plugGoBilda(0);
        HubCalls host = new HubCalls(rig.client);
        host.setMode(0, HubCalls.VELOCITY, false);
        host.setVelocity(0, 800);
        host.enable(0, true);
        rig.client.ack(DekaCommand.SET_SERVO_PULSE_WIDTH, Bytes.of().u8(0).u16(1700).build());
        rig.runMillis(20);
        List<String> before = observe(rig, false);

        List<String> unscheduled = observe(rig, true);
        for (int round = 0; round < 24; round++) {
            observe(rig, true);
        }

        assertEquals(before, unscheduled, "what a query returns does not depend on who asks");
        assertEquals(before, observe(rig, false), "and asking leaves everything as it was");
    }

    private CommandTable.Effect effectOf(int packetId, byte[] payload) {
        return hub.commandTable().find(packetId).effect().of(payload);
    }

    /** What every query answers, through the scheduled side or not. */
    private static List<String> observe(Rig rig, boolean unscheduled) {
        List<String> lines = new ArrayList<>();
        for (QueryCatalog.Request request : QueryCatalog.requests()) {
            RhspClient host =
                    new RhspClient(rig.bus, QueryCatalog.isBroadcast(request) ? Datagram.BROADCAST : rig.hub.address());
            RhspClient.Reply reply = (unscheduled ? host.unscheduled() : host).send(request.packetId(), request.payload());
            lines.add(request.name() + " " + Integer.toHexString(reply.packetId()) + " "
                    + HexFormat.of().formatHex(reply.payload()));
        }
        return lines;
    }
}
