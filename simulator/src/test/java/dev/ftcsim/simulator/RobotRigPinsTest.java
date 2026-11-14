package dev.ftcsim.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.HubWire;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.lynx.Datagram;
import dev.ftcsim.hardware.lynx.DekaCommand;
import dev.ftcsim.hardware.lynx.MalformedFrameException;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The digital and analog pins of a profile on the hubs of the rig built from it, read with the hub's own commands. */
class RobotRigPinsTest {

    private static final int CONTROL_HUB = 173;
    private static final int EXPANSION_HUB = 2;
    private static final long MS = 1_000_000L;

    private static final String PROFILE = """
            {"schema": 1, "name": "pins",
             "hubs": [{"address": 173, "name": "Control Hub", "parent": true, "embedded": true},
                      {"address": 2, "name": "Expansion Hub 2"}],
             "motors": {"slide": {"port": 0, "shaft": {"loadInertiaKgM2": 0.002, "frictionNmsPerRad": 0.001}}},
             "digitals": {
               "limit": {"channel": 0, "source": {"type": "encoder", "motor": "slide", "counts": 300, "closedWhen": "ABOVE"}},
               "beam": {"hub": 2, "channel": 3, "source": {"type": "constant", "level": "LOW"}},
               "idle": {"channel": 4}},
             "analogs": {
               "pot": {"channel": 1, "source": {"type": "encoder", "motor": "slide", "voltsAtZero": 0.5, "voltsPerCount": 0.0005}},
               "reference": {"hub": 2, "channel": 2, "source": {"type": "constant", "volts": 1.65}},
               "idle": {"channel": 0}}}
            """;

    private final AtomicLong clock = new AtomicLong();
    private final RobotRig rig = RobotRig.build(
            RobotProfileLoader.parse(PROFILE, "pins.json"), null, 1, 0, 0, 0, clock::get, (origin, direction, maxMeters) -> null,
            DeviceModels.standard());

    private Datagram send(int hub, DekaCommand command, int... payload) {
        byte[] bytes = new byte[payload.length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) payload[i];
        }
        HubWire.Exchange exchange = rig.bus().transmit(new Datagram(hub, 0, 1, 0, command.number(), bytes).encode());
        assertEquals(1, exchange.replies().size(), command.name());
        try {
            return Datagram.parse(exchange.replies().get(0));
        } catch (MalformedFrameException malformed) {
            throw new AssertionError(malformed);
        }
    }

    private ByteBuffer ask(int hub, DekaCommand command, int... payload) {
        Datagram reply = send(hub, command, payload);
        assertEquals(0x8000 | command.number(), reply.packetId(), command + " was not answered");
        return ByteBuffer.wrap(reply.payload()).order(ByteOrder.LITTLE_ENDIAN);
    }

    private int digitalLevels(int hub) {
        return ask(hub, DekaCommand.GET_ALL_DIO_INPUTS).get() & 0xFF;
    }

    private boolean digitalLevel(int hub, int pin) {
        return ask(hub, DekaCommand.GET_SINGLE_DIO_INPUT, pin).get() != 0;
    }

    private int millivolts(int hub, int channel) {
        return ask(hub, DekaCommand.GET_ADC, channel, 0).getShort();
    }

    /** Powers the slide's motor at half power through the hub and runs the 1 ms ticks until its encoder passes {@code counts}. */
    private void driveSlideBeyond(long counts) {
        assertEquals(0x7F01, send(CONTROL_HUB, DekaCommand.SET_MOTOR_CHANNEL_ENABLE, 0, 1).packetId(), "acknowledged");
        assertEquals(0x7F01, send(CONTROL_HUB, DekaCommand.SET_MOTOR_CONSTANT_POWER, 0, 0x00, 0x40).packetId(), "acknowledged");
        for (int i = 0; rig.plant("slide").encoderCounts() <= counts; i++) {
            assertTrue(i < 5000, "the slide never got to " + counts);
            clock.addAndGet(MS);
            rig.tick(clock.get());
        }
    }

    @Test
    void eachPinReadsWhatItsSourceSaysAndAPinNobodyListedIsOpenAndReadsHigh() {
        assertEquals(0xFF, digitalLevels(CONTROL_HUB), "the switch is open at the start, so every pin is pulled high");
        assertEquals(0xF7, digitalLevels(EXPANSION_HUB), "the beam on the expansion hub's pin 3 is held low");
        assertEquals(500, millivolts(CONTROL_HUB, 1), "the potentiometer at zero counts");
        assertEquals(1650, millivolts(EXPANSION_HUB, 2));
        assertEquals(0, millivolts(CONTROL_HUB, 0));
        assertEquals(0, millivolts(CONTROL_HUB, 3));
    }

    @Test
    void aSourceFollowingAMotorReadsTheMotorsEncoderAtTheMomentTheHubIsAsked() {
        driveSlideBeyond(300);

        long counts = rig.plant("slide").encoderCounts();
        assertEquals(0xFE, digitalLevels(CONTROL_HUB), "the switch has closed, which pulls pin 0 low");
        assertEquals((int) Math.round((0.5 + 0.0005 * counts) * 1000.0), millivolts(CONTROL_HUB, 1));
        assertEquals(1650, millivolts(EXPANSION_HUB, 2), "a constant does not follow anything");
    }

    @Test
    void aPinThatIsListedIsNeverReportedUnboundEvenWithoutASourceAndOneThatIsNotIsReportedWhenRead() {
        digitalLevel(CONTROL_HUB, 0);
        digitalLevel(CONTROL_HUB, 4);
        digitalLevel(EXPANSION_HUB, 3);
        millivolts(CONTROL_HUB, 0);
        millivolts(CONTROL_HUB, 1);
        millivolts(EXPANSION_HUB, 2);
        assertEquals(List.of(), rig.hub(CONTROL_HUB).unboundDigitalPorts());
        assertEquals(List.of(), rig.hub(CONTROL_HUB).unboundAnalogPorts());

        digitalLevel(CONTROL_HUB, 6);
        millivolts(CONTROL_HUB, 3);

        assertEquals(List.of(6), rig.hub(CONTROL_HUB).unboundDigitalPorts());
        assertEquals(List.of(3), rig.hub(CONTROL_HUB).unboundAnalogPorts());
        assertEquals(List.of(), rig.hub(EXPANSION_HUB).unboundDigitalPorts());
    }

    @Test
    void robotCodeFindsEachPinWhereTheProfilePutItAndAnyOtherNameGetsTheNextFreeChannelAndIsReported() {
        assertEquals(new HardwareDirectory.DigitalWiring(CONTROL_HUB, 0, true), rig.wiring().digital("limit"));
        assertEquals(new HardwareDirectory.DigitalWiring(EXPANSION_HUB, 3, true), rig.wiring().digital("beam"));
        assertEquals(new HardwareDirectory.AnalogWiring(CONTROL_HUB, 1, true), rig.wiring().analog("pot"));
        assertEquals(new HardwareDirectory.AnalogWiring(EXPANSION_HUB, 2, true), rig.wiring().analog("reference"));
        assertEquals(List.of(), rig.wiring().unmappedNames());

        assertEquals(new HardwareDirectory.DigitalWiring(CONTROL_HUB, 1, false), rig.wiring().digital("extra"));
        assertEquals(new HardwareDirectory.AnalogWiring(CONTROL_HUB, 2, false), rig.wiring().analog("extra"));
        assertEquals(List.of("extra", "extra"), rig.wiring().unmappedNames());
    }

    @Test
    void aProfileWithoutPinsMakesARigWithoutPinsAndTheOlderConstructorStillMakesOne() {
        RobotProfile bare = new RobotProfile(1, "bare", null, null, null, null, null, null, null, null);

        assertTrue(bare.digitals().isEmpty() && bare.analogs().isEmpty());
        RobotRig plain = RobotRig.build(bare, null, 1, 0, 0, 0, clock::get, (origin, direction, maxMeters) -> null, DeviceModels.standard());
        assertEquals(List.of(), plain.wiring().unmappedNames());
    }
}
