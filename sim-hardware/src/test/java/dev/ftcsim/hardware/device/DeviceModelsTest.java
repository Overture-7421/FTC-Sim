package dev.ftcsim.hardware.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeviceModelsTest {

    private final DeviceHost host = new DeviceHost(() -> BodyPose.atOrigin(0), (origin, direction, maxMeters) -> null, () -> 0);

    /** A device that does nothing but remember what it was built from. */
    private static final class Probe implements I2cPeripheral {
        final double gain;

        Probe(double gain) {
            this.gain = gain;
        }

        @Override
        public int address() {
            return 0x10;
        }

        @Override
        public void write(byte[] bytes, long nowNanos) {}

        @Override
        public byte[] read(int count, long nowNanos) {
            return new byte[count];
        }
    }

    private static I2cPeripheral probe(DeviceConfig config, DeviceContext context) {
        return new Probe(config.number("gain", 1));
    }

    @Test
    void theStandardRegistryHasEveryDeviceTheSimulatorModels() {
        assertEquals(List.of("gobilda-pinpoint", "rev-color-sensor-v3", "digitalchickenlabs-octoquad", "bno055-imu"), DeviceModels.standard().typeIds());
    }

    @Test
    void aRegisteredFactoryBuildsTheDeviceFromItsSettings() {
        DeviceModels models = DeviceModels.empty().register("probe", DeviceModelsTest::probe);

        I2cPeripheral device = models.create("probe", "thing", Map.of("gain", 4), host);

        assertEquals(4.0, ((Probe) device).gain);
    }

    @Test
    void anUnknownTypeNamesTheDeviceAndListsTheTypesThereAre() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DeviceModels.standard().create("octoquad", "encoders", Map.of(), host));

        assertTrue(failure.getMessage().contains("\"encoders\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("\"octoquad\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("gobilda-pinpoint"), failure.getMessage());
    }

    @Test
    void aMisspeltSettingFailsWhenTheDeviceIsBuiltInsteadOfBeingIgnored() {
        DeviceModels models = DeviceModels.empty().register("probe", DeviceModelsTest::probe);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> models.create("probe", "thing", Map.of("gian", 4), host));

        assertTrue(failure.getMessage().contains("\"gian\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("gain"), "the message says what the device does read: " + failure.getMessage());
    }

    @Test
    void aTypeCannotBeRegisteredTwice() {
        DeviceModels models = DeviceModels.empty().register("probe", DeviceModelsTest::probe);

        assertThrows(IllegalStateException.class, () -> models.register("probe", DeviceModelsTest::probe));
    }

    @Test
    void theHostRunsListenersInTheOrderTheyWereAdded() {
        List<String> calls = new ArrayList<>();
        host.onStep(nanos -> calls.add("first " + nanos));
        host.onStep(nanos -> calls.add("second " + nanos));

        host.step(4_166_666L);

        assertArrayEquals(new String[] {"first 4166666", "second 4166666"}, calls.toArray());
    }
}
