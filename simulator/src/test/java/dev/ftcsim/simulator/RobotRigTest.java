package dev.ftcsim.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.HubWire;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.CameraMount;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.hardware.device.bosch.Bno055Model;
import dev.ftcsim.hardware.device.gobilda.GoBildaPinpointModel;
import dev.ftcsim.hardware.device.rev.RevColorSensorV3Model;
import dev.ftcsim.hardware.lynx.Datagram;
import dev.ftcsim.hardware.lynx.DekaCommand;
import dev.ftcsim.hardware.lynx.MalformedFrameException;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.ServoCatalog;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The rig of a robot without wheels (so that no physics world is needed): its sensors, camera and idle pins, found as robot code would. */
class RobotRigTest {

    private static final int CONTROL_HUB = 173;
    private static final int EXPANSION_HUB = 2;
    private static final long STEP_NANOS = 4_166_667L;

    /** An upright tag two metres ahead of a robot at the origin, its face turned back at the robot. */
    private static final FieldTag TAG = new FieldTag(20, 0.1651, -1, new Pose3(2.0, 0.0, 0.45, -0.5, -0.5, 0.5, 0.5));

    private final AtomicLong clock = new AtomicLong();

    private RobotRig rig(RobotMapping mapping, long seed) {
        return RobotRig.build(
                RobotDesign.decode().spec(mapping), null, 1, 0, 0, 0, clock::get, (origin, direction, maxMeters) -> null,
                new RobotRig.Vision(List.of(TAG), id -> {
                    throw new IllegalArgumentException("no bodies");
                }, seed));
    }

    private RobotRig rig() {
        return rig(RobotMapping.empty("sensors").withMotors(List.of(new MotorMapping("slide", Slot.CONVEYOR, "", false))), 1);
    }

    private void runFor(RobotRig rig, long nanos) {
        long end = clock.get() + nanos;
        while (clock.get() < end) {
            clock.addAndGet(STEP_NANOS);
            rig.stepDevices(clock.get());
        }
    }

    private static String results(RobotRig rig, String camera, long now) {
        CameraWire.Reply reply = rig.cameras().get(camera, "/results", now);
        assertTrue(reply.ok(), "GET /results answered " + reply.status());
        return reply.body();
    }

    private static int numberAfter(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        assertTrue(matcher.find(), key + " is in " + json);
        return Integer.parseInt(matcher.group(1));
    }

    private Datagram send(RobotRig rig, int hub, DekaCommand command, int... payload) {
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

    private ByteBuffer ask(RobotRig rig, int hub, DekaCommand command, int... payload) {
        Datagram reply = send(rig, hub, command, payload);
        assertEquals(0x8000 | command.number(), reply.packetId(), command + " was not answered");
        return ByteBuffer.wrap(reply.payload()).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void everyRobotHasTheControlHubsImuThePinpointAndTheColourSensorOnTheBusesTheirKindsHave() {
        RobotRig rig = rig();

        assertTrue(rig.hub(CONTROL_HUB).answersAt(HardwareDirectory.EMBEDDED_IMU_BUS, Bno055Model.ADDRESS));
        assertTrue(rig.hub(CONTROL_HUB).answersAt(HardwareDirectory.Sensor.PINPOINT.bus(), GoBildaPinpointModel.ADDRESS));
        assertTrue(rig.hub(CONTROL_HUB).answersAt(HardwareDirectory.Sensor.COLOR_SENSOR.bus(), RevColorSensorV3Model.ADDRESS));
        assertFalse(rig.hub(EXPANSION_HUB).answersAt(HardwareDirectory.EMBEDDED_IMU_BUS, Bno055Model.ADDRESS), "the Expansion Hub has no IMU of its own here");
    }

    @Test
    void aTurretServoTurnsTheTurretWhileTheHubSendsItAPulseAndStopsWhenTheOutputIsSwitchedOff() {
        RobotMapping base = RobotMapping.empty("servo");
        RobotMapping mapping = new RobotMapping(
                base.name(), base.motors(), base.massKg(), base.wheelDiameterMm(), base.driveMotor(), 2.75, base.hubLogo(), base.hubUsb(),
                base.camera(), new RobotMapping.TurretParts(new RobotMapping.ServoMapping("turretServo", "", false), List.of(), 0, 0, false));
        RobotRig rig = RobotRig.build(
                RobotDesign.biobuzz().spec(mapping), null, 1, 0, 0, 0, clock::get, (origin, direction, maxMeters) -> null, RobotRig.Vision.NONE);
        assertEquals(new HardwareDirectory.ServoWiring(CONTROL_HUB, 0, true), rig.wiring().servo("turretServo"));
        assertEquals(0.0, rig.turretYawRadians());

        send(rig, CONTROL_HUB, DekaCommand.SET_SERVO_CONFIGURATION, 0, 0x20, 0x4E);
        send(rig, CONTROL_HUB, DekaCommand.SET_SERVO_PULSE_WIDTH, 0, 0x08, 0x07);
        send(rig, CONTROL_HUB, DekaCommand.SET_SERVO_ENABLE, 0, 1);
        tickFor(rig, 1.0);

        double settled = 0.3 * ServoCatalog.noLoadRadPerSec("axon-mini-plus");
        assertEquals(settled * (1.0 - 0.03) / 2.75, rig.turretYawRadians(), 0.002, "a pulse of 1800 us is 0.3 of full speed, 2.75 servo turns to a turret turn");
        assertEquals(settled, rig.plantView().shaftSpeedRadPerSec("turretServo"), 1e-3);

        send(rig, CONTROL_HUB, DekaCommand.SET_SERVO_ENABLE, 0, 0);
        tickFor(rig, 0.5);
        double stopped = rig.turretYawRadians();
        tickFor(rig, 0.5);
        assertEquals(stopped, rig.turretYawRadians(), 1e-6, "with its output off the servo holds still");
    }

    private void tickFor(RobotRig rig, double seconds) {
        for (int i = 0; i < Math.round(seconds * 1000); i++) {
            clock.addAndGet(1_000_000L);
            rig.tick(clock.get());
        }
    }

    @Test
    void theSensorsAreFoundByKindUnderAnyNameAndAreNotReportedAsUnmappedButASecondNameIsRefused() {
        RobotRig rig = rig();

        assertEquals(new HardwareDirectory.I2cWiring(CONTROL_HUB, 1, true), rig.wiring().sensor("odopods", HardwareDirectory.Sensor.PINPOINT));
        assertEquals(new HardwareDirectory.I2cWiring(CONTROL_HUB, 2, true), rig.wiring().sensor("whatever", HardwareDirectory.Sensor.COLOR_SENSOR));
        assertEquals(new HardwareDirectory.I2cWiring(CONTROL_HUB, 0, true), rig.wiring().imu("gyro"));
        assertEquals(List.of(), rig.wiring().unmappedNames());
        assertThrows(IllegalStateException.class, () -> rig.wiring().sensor("second", HardwareDirectory.Sensor.PINPOINT));
    }

    @Test
    void theLimelightIsPluggedInUnderTheFirstNameThatAsksAndSeesTheFieldsTagsThroughTheMappedMount() {
        RobotRig rig = rig();

        assertTrue(rig.environment().cameras().connect("limelight"));
        runFor(rig, 300_000_000L);
        String json = results(rig, "limelight", clock.get());

        assertEquals(1, numberAfter(json, "v"));
        assertEquals(20, numberAfter(json, "fID"));
        assertEquals(1, numberAfter(json, "botpose_tagcount"));
        assertEquals(List.of(), rig.cameras().problems());

        assertFalse(rig.environment().cameras().connect("webcam"));
        assertEquals(1, rig.cameras().problems().size());
        assertTrue(rig.cameras().problems().get(0).contains("\"webcam\"") && rig.cameras().problems().get(0).contains("\"limelight\""));
    }

    @Test
    void aLimelightMountedTurnedAwayFromTheTagDoesNotSeeIt() {
        RobotMapping base = RobotMapping.empty("turned");
        RobotMapping turned = new RobotMapping(
                base.name(), base.motors(), base.massKg(), base.wheelDiameterMm(), base.driveMotor(), base.turretRatio(),
                base.hubLogo(), base.hubUsb(), new CameraMount(0.16, 0.0, 0.25, 90.0, 20.0, 0.0));
        RobotRig rig = rig(turned, 1);
        rig.environment().cameras().connect("limelight");

        runFor(rig, 300_000_000L);

        assertEquals(0, numberAfter(results(rig, "limelight", clock.get()), "v"), "yawed 90 degrees left, it looks at the wall, not at the tag");
    }

    @Test
    void theFieldSeedFixesTheNoiseOfTheImage() {
        String[] replies = new String[3];
        long[] seeds = {7, 7, 8};
        for (int i = 0; i < seeds.length; i++) {
            clock.set(0);
            RobotRig rig = rig(RobotMapping.empty("noise"), seeds[i]);
            rig.environment().cameras().connect("limelight");
            runFor(rig, 300_000_000L);
            replies[i] = results(rig, "limelight", clock.get());
        }

        assertEquals(replies[0], replies[1], "the same seed gives the same bytes");
        assertNotEquals(replies[0], replies[2], "another seed gives other noise");
    }

    @Test
    void pinsNothingIsConnectedToReadIdleAndAreReportedOnceRead() {
        RobotRig rig = rig();

        assertEquals(0xFF, ask(rig, CONTROL_HUB, DekaCommand.GET_ALL_DIO_INPUTS).get() & 0xFF, "open pins are pulled high");
        assertEquals(0, ask(rig, CONTROL_HUB, DekaCommand.GET_ADC, 1, 0).getShort(), "an unconnected analog pin reads 0 V");
        assertEquals(0xFF, ask(rig, EXPANSION_HUB, DekaCommand.GET_ALL_DIO_INPUTS).get() & 0xFF);
        ask(rig, CONTROL_HUB, DekaCommand.GET_SINGLE_DIO_INPUT, 6);

        assertTrue(rig.hub(CONTROL_HUB).unboundDigitalPorts().contains(6));
        assertTrue(rig.hub(CONTROL_HUB).unboundAnalogPorts().contains(1));
    }

    @Test
    void aPinNameRobotCodeAsksForGetsTheNextFreeChannelAndIsReportedAsUnmapped() {
        RobotRig rig = rig();

        assertEquals(new HardwareDirectory.DigitalWiring(CONTROL_HUB, 0, false), rig.wiring().digital("limit"));
        assertEquals(new HardwareDirectory.DigitalWiring(CONTROL_HUB, 1, false), rig.wiring().digital("beam"));
        assertEquals(new HardwareDirectory.AnalogWiring(CONTROL_HUB, 0, false), rig.wiring().analog("pot"));
        assertEquals(List.of("limit", "beam", "pot"), rig.wiring().unmappedNames());
    }
}
