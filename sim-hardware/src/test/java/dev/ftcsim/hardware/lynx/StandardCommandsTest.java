package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.StandardCommand.DEBUG_LOG_LEVEL;
import static dev.ftcsim.hardware.lynx.StandardCommand.DOWNLOAD_CHUNK;
import static dev.ftcsim.hardware.lynx.StandardCommand.GET_MODULE_LED_COLOR;
import static dev.ftcsim.hardware.lynx.StandardCommand.GET_MODULE_LED_PATTERN;
import static dev.ftcsim.hardware.lynx.StandardCommand.GET_MODULE_STATUS;
import static dev.ftcsim.hardware.lynx.StandardCommand.KEEP_ALIVE;
import static dev.ftcsim.hardware.lynx.StandardCommand.QUERY_INTERFACE;
import static dev.ftcsim.hardware.lynx.StandardCommand.SET_MODULE_LED_COLOR;
import static dev.ftcsim.hardware.lynx.StandardCommand.SET_MODULE_LED_PATTERN;
import static dev.ftcsim.hardware.lynx.StandardCommand.SET_NEW_MODULE_ADDRESS;
import static dev.ftcsim.hardware.lynx.StandardCommand.START_DOWNLOAD;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StandardCommandsTest {

    private static final int DEVICE_RESET = 0x02;
    private static final int PARAM0 = 0;
    private static final int PARAM1 = 1;

    private final Rig rig = new Rig();
    private final RhspClient client = rig.client;

    @Test
    void repliesComeFromTheHubAndNameTheRequestTheyAnswer() {
        RhspClient.Reply first = client.standard(KEEP_ALIVE, Bytes.none());
        RhspClient.Reply second = client.standard(KEEP_ALIVE, Bytes.none());

        assertTrue(first.isAck());
        assertEquals(173, first.source());
        assertEquals(1, first.referenceNumber());
        assertEquals(2, second.referenceNumber());
        assertEquals(1, first.messageNumber());
        assertEquals(2, second.messageNumber());
    }

    @Test
    void theHubsOwnMessageNumbersWrapPastZero() {
        Set<Integer> seen = new HashSet<>();
        int previous = 0;
        for (int i = 0; i < 600; i++) {
            int number = client.standard(KEEP_ALIVE, Bytes.none()).messageNumber();
            seen.add(number);
            assertEquals(previous == 255 ? 1 : previous + 1, number);
            previous = number;
        }

        assertFalse(seen.contains(0));
        assertEquals(255, seen.size());
    }

    @Test
    void aFreshHubReportsItsResetUntilTheHostClearsIt() {
        assertTrue(client.standard(KEEP_ALIVE, Bytes.none()).attentionRequired());

        ByteBuffer peek = client.standard(GET_MODULE_STATUS, Bytes.of().u8(0).build()).data();
        assertEquals(DEVICE_RESET, peek.get() & 0xFF);
        assertEquals(0, peek.get());
        assertEquals(DEVICE_RESET, statusAndClear());

        assertEquals(0, statusAndClear());
        assertFalse(client.standard(KEEP_ALIVE, Bytes.none()).attentionRequired());
    }

    @Test
    void statusResponsesCarryTheStatusCommandsNumberWithTheResponseBit() {
        assertEquals(0xFF03, client.standard(GET_MODULE_STATUS, Bytes.of().u8(0).build()).packetId());
    }

    @Test
    void aHubConfiguredNotToSignalAttentionNeverAsksTheSdkToFetchItsStatus() {
        Rig quiet = new Rig(HubConfig.controlHub().withKeepAliveSupervision(false).withAttentionSignalling(false));

        assertFalse(quiet.client.standard(KEEP_ALIVE, Bytes.none()).attentionRequired());
        assertEquals(
                DEVICE_RESET,
                quiet.client.standard(GET_MODULE_STATUS, Bytes.of().u8(0).build()).data().get() & 0xFF);
    }

    @Test
    void lowBatteryShowsInTheStatusWhileItLastsAndSurvivesAClear() {
        statusAndClear();
        rig.batteryVolts[0] = 6.5;

        assertEquals(0x10, statusAndClear());
        assertEquals(0x10, statusAndClear());
        rig.batteryVolts[0] = 12.0;
        assertEquals(0, statusAndClear());
    }

    @Test
    void failSafeIsAcknowledgedAndReportedAsAnEventUntilCleared() {
        statusAndClear();

        assertTrue(client.standard(StandardCommand.FAIL_SAFE, Bytes.none()).isAck());

        assertEquals(0x04, statusAndClear());
        assertEquals(0, statusAndClear());
    }

    @Test
    void aNewModuleAddressTakesEffectAtOnceAndTheAcknowledgementComesFromIt() {
        RhspClient.Reply ack = client.standard(SET_NEW_MODULE_ADDRESS, Bytes.of().u8(5).build());

        assertTrue(ack.isAck());
        assertEquals(5, ack.source());
        assertEquals(5, rig.hub.address());
        assertTrue(rig.bus.transmit(RhspClient.frame(173, 1, 0, 0x7F04, Bytes.none())).replies().isEmpty());
        assertTrue(new RhspClient(rig.bus, 5).standard(KEEP_ALIVE, Bytes.none()).isAck());
    }

    @Test
    void reservedAndTakenModuleAddressesAreRefused() {
        rig.bus.attach(new LynxHub(HubConfig.expansionHub(2, false, "child"), () -> 12.0, 0));

        for (int address : new int[] {0, 255, 2}) {
            RhspClient.Reply reply = client.standard(SET_NEW_MODULE_ADDRESS, Bytes.of().u8(address).build());
            assertEquals(PARAM0, reply.nackReason(), "address " + address);
        }
        assertEquals(173, rig.hub.address());
    }

    @Test
    void queryInterfaceReportsTheDekaInterfaceAtTheRealBaseWithAllItsCommands() {
        for (byte[] name : new byte[][] {text("DEKA\0"), text("DEKA")}) {
            RhspClient.Reply reply = client.standard(QUERY_INTERFACE, name);
            assertEquals(0xFF07, reply.packetId());
            ByteBuffer data = reply.data();
            assertEquals(0x1000, data.getShort() & 0xFFFF);
            assertEquals(54, data.getShort() & 0xFFFF);
        }
    }

    @Test
    void queryInterfaceRefusesInterfacesThisHubDoesNotHave() {
        for (String name : new String[] {"SERVO_HUB\0", "deka\0", "\0", ""}) {
            assertEquals(PARAM0, client.standard(QUERY_INTERFACE, text(name)).nackReason(), name);
        }
        assertTrue(rig.hub.unsupported().isEmpty());
    }

    @Test
    void theLedColourAndPatternAreStoredAsSent() {
        assertTrue(client.standard(SET_MODULE_LED_COLOR, Bytes.of().u8(1).u8(2).u8(255).build()).isAck());
        assertArrayEquals(new byte[] {1, 2, (byte) 255}, client.standard(GET_MODULE_LED_COLOR, Bytes.none()).payload());

        byte[] pattern = {10, 0, (byte) 255, 0, 5, 0, 0, 0, 0, 0, 0, 0};
        assertTrue(client.standard(SET_MODULE_LED_PATTERN, pattern).isAck());
        RhspClient.Reply stored = client.standard(GET_MODULE_LED_PATTERN, Bytes.none());
        assertEquals(0xFF0D, stored.packetId());
        assertArrayEquals(pattern, stored.payload());
    }

    @Test
    void ledPatternsMustBeWholeStepsOfAtMostSixteen() {
        for (int length : new int[] {0, 3, 5, 68}) {
            assertEquals(
                    PARAM0,
                    client.standard(SET_MODULE_LED_PATTERN, new byte[length]).nackReason(),
                    "length " + length);
        }
        assertTrue(client.standard(SET_MODULE_LED_PATTERN, new byte[64]).isAck());
    }

    @Test
    void debugLogLevelChecksItsGroupAndVerbosity() {
        assertTrue(client.standard(DEBUG_LOG_LEVEL, Bytes.of().u8(6).u8(3).build()).isAck());
        assertEquals(PARAM0, client.standard(DEBUG_LOG_LEVEL, Bytes.of().u8(13).u8(0).build()).nackReason());
        assertEquals(PARAM1, client.standard(DEBUG_LOG_LEVEL, Bytes.of().u8(1).u8(4).build()).nackReason());
    }

    @Test
    void commandsRefuseParametersTheyDoNotDefine() {
        assertEquals(PARAM0, client.standard(KEEP_ALIVE, new byte[] {1}).nackReason());
        assertEquals(PARAM0, client.standard(GET_MODULE_STATUS, Bytes.none()).nackReason());
    }

    @Test
    void firmwareDownloadIsNackedAsUnfinishedAndRecordedAsUnsupported() {
        assertEquals(NackReason.COMMAND_IMPL_PENDING, client.standard(START_DOWNLOAD, Bytes.none()).nackReason());
        assertEquals(NackReason.COMMAND_IMPL_PENDING, client.standard(DOWNLOAD_CHUNK, Bytes.none()).nackReason());
        client.standard(START_DOWNLOAD, Bytes.none());

        List<UnsupportedUse> unsupported = rig.hub.unsupported();
        assertEquals(2, unsupported.size());
        assertEquals(START_DOWNLOAD.number(), unsupported.get(0).packetId());
        assertEquals(2, unsupported.get(0).count());
        assertEquals(DOWNLOAD_CHUNK.number(), unsupported.get(1).packetId());
    }

    @Test
    void unknownPacketIdsAreNackedAsUnknownAndRecordedWhenTheyFirstAppeared() {
        rig.advanceTo(7 * Rig.MS);

        for (int packetId : new int[] {0x0001, 0x1000 + 54, 0x7F10, 0x8000 + 0x1000, 0x7F01, 0x7F02}) {
            assertEquals(NackReason.PACKET_TYPE_ID_UNKNOWN, client.send(packetId, Bytes.none()).nackReason());
        }

        assertEquals(6, rig.hub.unsupported().size());
        assertEquals(7 * Rig.MS, rig.hub.unsupported().get(0).firstSeenNanos());
    }

    private int statusAndClear() {
        RhspClient.Reply reply = client.standard(GET_MODULE_STATUS, Bytes.of().u8(1).build());
        int status = reply.data().get() & 0xFF;
        assertEquals(0, reply.data().get(1), "motor alerts");
        return status;
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
