package dev.ftcsim.hardware.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeviceConfigTest {

    private static DeviceConfig config(Map<String, ?> settings) {
        return DeviceConfig.of("gobilda-pinpoint", "odo", settings);
    }

    @Test
    void numbersOfAnyKindAndTextAreReadBack() {
        DeviceConfig config = config(Map.of("a", 1, "b", 2.5, "c", 3L, "d", "text"));

        assertEquals(1.0, config.number("a"));
        assertEquals(2.5, config.number("b"));
        assertEquals(3, config.integer("c"));
        assertEquals("text", config.text("d"));
        assertEquals(7.0, config.number("missing", 7.0));
        assertEquals("fallback", config.text("missing", "fallback"));
        assertTrue(config.has("a"));
        assertFalse(config.has("missing"));
    }

    @Test
    void aMissingSettingNamesTheDeviceItsTypeAndTheSetting() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> config(Map.of()).number("xPod.x"));

        assertTrue(failure.getMessage().contains("\"odo\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("gobilda-pinpoint"), failure.getMessage());
        assertTrue(failure.getMessage().contains("\"xPod.x\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("missing"), failure.getMessage());
    }

    @Test
    void aSettingOfTheWrongKindSaysWhatItShouldBe() {
        DeviceConfig config = config(Map.of("number", 1.5, "text", "abc"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> config.number("text")).getMessage().contains("must be a number"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> config.text("number")).getMessage().contains("must be text"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> config.integer("number")).getMessage().contains("whole number"));
    }

    @Test
    void valuesThatAreNeitherNumbersNorTextAreRefusedAtOnce() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("flag", Boolean.TRUE);
        Map<String, Object> notFinite = new HashMap<>();
        notFinite.put("gain", Double.NaN);

        assertTrue(assertThrows(IllegalArgumentException.class, () -> config(bad)).getMessage().contains("\"flag\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> config(notFinite)).getMessage().contains("finite"));
    }

    @Test
    void aGroupIsPresentWhenAnySettingStartsWithItsName() {
        DeviceConfig config = config(Map.of("xPod.x", 0.1, "yPodExtra", 1));

        assertTrue(config.hasGroup("xPod"));
        assertFalse(config.hasGroup("yPod"));
    }

    @Test
    void theSettingsAreCopiedSoLaterChangesToTheMapDoNotReachTheDevice() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("a", 1);
        DeviceConfig config = config(settings);
        settings.put("a", 2);

        assertEquals(1.0, config.number("a"));
    }
}
