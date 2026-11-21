package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LoopbackPortsTest {
    @Test
    void onlyTheConfiguredPortsListenOnTheLoopbackAddress() throws Exception {
        try (KernelFixture fixture = new KernelFixture(config -> config.withLoopbackPorts(Set.of(8001, 8002)))) {
            assertTrue(fixture.kernel.hooks().listenOnLoopback(8001));
            assertTrue(fixture.kernel.hooks().listenOnLoopback(8002));
            assertFalse(fixture.kernel.hooks().listenOnLoopback(8003));
        }
    }

    @Test
    void noPortDoesByDefault() throws Exception {
        try (KernelFixture fixture = new KernelFixture(config -> config)) {
            assertFalse(fixture.kernel.hooks().listenOnLoopback(8001));
        }
    }
}
