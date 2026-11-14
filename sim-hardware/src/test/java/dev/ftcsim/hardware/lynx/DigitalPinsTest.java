package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.GET_ALL_DIO_INPUTS;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_DIO_DIRECTION;
import static dev.ftcsim.hardware.lynx.DekaCommand.GET_SINGLE_DIO_INPUT;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_ALL_DIO_OUTPUTS;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_DIO_DIRECTION;
import static dev.ftcsim.hardware.lynx.DekaCommand.SET_SINGLE_DIO_OUTPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DigitalPinsTest {

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;

    @Test
    void pinsPowerUpAsInputsThatReadHighBecauseOfTheHubsPullUps() {
        for (int pin = 0; pin < 8; pin++) {
            assertEquals(0, client.response(GET_DIO_DIRECTION, Bytes.of().u8(pin).build()).get());
            assertEquals(1, client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(pin).build()).get());
        }
        assertEquals(0xFF, client.response(GET_ALL_DIO_INPUTS, Bytes.none()).get() & 0xFF);
    }

    @Test
    void readingAnOpenInputIsReportedButTheBulkReadOfAllPinsIsNot() {
        new HubCalls(client).bulk();
        client.response(GET_ALL_DIO_INPUTS, Bytes.none());
        assertTrue(rig.hub.unboundDigitalPorts().isEmpty());

        client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(6).build());
        client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(2).build());
        client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(6).build());

        assertEquals(List.of(2, 6), rig.hub.unboundDigitalPorts());
    }

    @Test
    void aConnectedInputFollowsItsSourceAtTheSimulatedTimeOfTheRead() {
        rig.hub.digitalPort(3).connect(nanos -> nanos >= 5 * Rig.MS);

        assertEquals(0, client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(3).build()).get());
        assertEquals(0xF7, client.response(GET_ALL_DIO_INPUTS, Bytes.none()).get() & 0xFF);
        rig.advanceTo(6 * Rig.MS);
        assertEquals(1, client.response(GET_SINGLE_DIO_INPUT, Bytes.of().u8(3).build()).get());
        assertEquals(0xFF, new HubCalls(client).bulk().digitalInputs());
        assertTrue(rig.hub.unboundDigitalPorts().isEmpty());
    }

    @Test
    void aPortTakesOneSourceAndTheHostCannotNameAPinThatDoesNotExist() {
        rig.hub.digitalPort(0).connect(nanos -> true);

        assertThrows(IllegalStateException.class, () -> rig.hub.digitalPort(0).connect(nanos -> false));
        assertThrows(IllegalArgumentException.class, () -> rig.hub.digitalPort(8));
    }

    @Test
    void anOutputStartsLowAndReadsBackWhatItDrives() {
        client.ack(SET_DIO_DIRECTION, Bytes.of().u8(1).u8(1).build());

        assertTrue(rig.hub.digitalIsOutput(1));
        assertEquals(1, client.response(GET_DIO_DIRECTION, Bytes.of().u8(1).build()).get());
        assertFalse(rig.hub.digitalOutputLevel(1));
        assertEquals(0xFD, new HubCalls(client).bulk().digitalInputs());

        client.ack(SET_SINGLE_DIO_OUTPUT, Bytes.of().u8(1).u8(1).build());
        assertTrue(rig.hub.digitalOutputLevel(1));
        assertEquals(0xFF, new HubCalls(client).bulk().digitalInputs());
    }

    @Test
    void turningAnOutputBackIntoAnInputAndAgainStartsItLow() {
        client.ack(SET_DIO_DIRECTION, Bytes.of().u8(4).u8(1).build());
        client.ack(SET_SINGLE_DIO_OUTPUT, Bytes.of().u8(4).u8(1).build());
        client.ack(SET_DIO_DIRECTION, Bytes.of().u8(4).u8(0).build());
        client.ack(SET_DIO_DIRECTION, Bytes.of().u8(4).u8(1).build());

        assertFalse(rig.hub.digitalOutputLevel(4));
    }

    @Test
    void usingAPinTheWrongWayIsRefusedWithThatPinsReasonCode() {
        client.ack(SET_DIO_DIRECTION, Bytes.of().u8(2).u8(1).build());

        assertEquals(10 + 4, client.nack(SET_SINGLE_DIO_OUTPUT, Bytes.of().u8(4).u8(1).build()));
        assertEquals(20 + 2, client.nack(GET_SINGLE_DIO_INPUT, Bytes.of().u8(2).build()));
        assertEquals(0, client.nack(SET_SINGLE_DIO_OUTPUT, Bytes.of().u8(8).u8(1).build()));
        assertEquals(0, client.nack(SET_DIO_DIRECTION, Bytes.of().u8(8).u8(1).build()));
    }

    @Test
    void settingAllOutputsOnlyTouchesTheOutputPinsAndNeedsAtLeastOne() {
        assertEquals(NackReason.GPIO_NO_OUTPUT, client.nack(SET_ALL_DIO_OUTPUTS, Bytes.of().u8(0xFF).build()));
        client.ack(SET_DIO_DIRECTION, Bytes.of().u8(0).u8(1).build());
        client.ack(SET_DIO_DIRECTION, Bytes.of().u8(5).u8(1).build());

        client.ack(SET_ALL_DIO_OUTPUTS, Bytes.of().u8(0b0010_0011).build());

        assertTrue(rig.hub.digitalOutputLevel(0));
        assertTrue(rig.hub.digitalOutputLevel(5));
        assertFalse(rig.hub.digitalIsOutput(1));
        assertEquals(0xFF, client.response(GET_ALL_DIO_INPUTS, Bytes.none()).get() & 0xFF);
        client.ack(SET_ALL_DIO_OUTPUTS, Bytes.of().u8(0b0000_0001).build());
        assertFalse(rig.hub.digitalOutputLevel(5));
    }

    @Test
    void readingAllInputsNeedsAtLeastOneInput() {
        for (int pin = 0; pin < 8; pin++) {
            client.ack(SET_DIO_DIRECTION, Bytes.of().u8(pin).u8(1).build());
        }

        assertEquals(NackReason.GPIO_NO_INPUT, client.nack(GET_ALL_DIO_INPUTS, Bytes.none()));
    }
}
