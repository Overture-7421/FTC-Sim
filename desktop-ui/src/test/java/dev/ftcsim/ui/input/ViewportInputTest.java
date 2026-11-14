package dev.ftcsim.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.ui.render.OrbitCamera;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Drives the input gate directly, the way the Compose modifier does, so no window, focus or operating-system input is involved. */
class ViewportInputTest {
    private final OrbitCamera camera = new OrbitCamera();
    private final ViewportInput input = new ViewportInput(camera);

    @Test
    void keysAreCapturedOnlyWhileTheViewportHasFocus() {
        input.keyPressed(KeyEvent.VK_W);
        assertEquals(Set.of(), input.keys().held());
        assertEquals(1, input.keys().ignoredPresses());

        input.focusChanged(true);
        input.keyPressed(KeyEvent.VK_W);
        assertEquals(Set.of(KeyEvent.VK_W), input.keys().held());
    }

    @Test
    void losingFocusReleasesHeldKeysAndALateKeyUpChangesNothing() {
        input.focusChanged(true);
        input.keyPressed(KeyEvent.VK_W);
        input.keyPressed(KeyEvent.VK_A);

        input.focusChanged(false);

        assertEquals(Set.of(), input.keys().held());
        assertEquals(1, input.keys().neutralizations().size());
        input.keyReleased(KeyEvent.VK_W);
        assertEquals(Set.of(), input.keys().held());
    }

    @Test
    void deactivatingTheWindowReleasesKeysEvenWhileTheViewportKeepsItsFocus() {
        input.focusChanged(true);
        input.keyPressed(KeyEvent.VK_W);

        input.windowFocusChanged(false);

        assertEquals(Set.of(), input.keys().held());
        assertTrue(input.keys().neutralizations().get(0).startsWith("window deactivated"));
        input.keyPressed(KeyEvent.VK_W);
        assertEquals(Set.of(), input.keys().held(), "an inactive window captures nothing");

        input.windowFocusChanged(true);
        input.keyPressed(KeyEvent.VK_W);
        assertEquals(Set.of(KeyEvent.VK_W), input.keys().held());
    }

    @Test
    void openingAModalReleasesKeysAndStopsPointerInput() {
        input.focusChanged(true);
        input.keyPressed(KeyEvent.VK_W);

        input.modalChanged(true);

        assertEquals(Set.of(), input.keys().held());
        assertTrue(input.keys().neutralizations().get(0).startsWith("modal opened"));
        double yaw = camera.yawDegrees();
        double distance = camera.distance();
        input.orbit(50, 0);
        input.zoom(2);
        assertEquals(yaw, camera.yawDegrees());
        assertEquals(distance, camera.distance());

        input.modalChanged(false);
        input.keyPressed(KeyEvent.VK_W);
        assertEquals(Set.of(KeyEvent.VK_W), input.keys().held(), "focus is still held, so capture resumes");
    }

    @Test
    void controllersFollowTheWindowModalsAndWhetherTheViewportIsShownButNotTheKeyTargetsFocus() {
        List<Boolean> heard = new ArrayList<>();
        input.onActiveChange(heard::add);

        input.viewportShown(true);
        input.focusChanged(true);
        input.modalChanged(true);
        input.windowFocusChanged(false);
        input.modalChanged(false);
        input.windowFocusChanged(true);
        input.viewportShown(false);

        assertEquals(List.of(true, true, false, false, false, true, false), heard);
    }

    @Test
    void theKeysThatBelongToTheKeyboardGamepadAreWhateverTheCurrentMapSays() {
        assertFalse(input.mapsKey(KeyEvent.VK_W), "nothing is mapped until the application says so");

        input.setKeyboardKeys(KeyboardMap.DEFAULT::isMapped);

        assertTrue(input.mapsKey(KeyEvent.VK_W));
        assertFalse(input.mapsKey(KeyEvent.VK_TAB));
    }

    @Test
    void draggingOrbitsByDegreesPerPixelAndZeroMovesAreIgnored() {
        long before = camera.version();
        input.orbit(0, 0);
        assertEquals(before, camera.version(), "a drag that did not move must not count as a camera change");

        double yaw = camera.yawDegrees();
        input.orbit(100, 0);
        assertEquals(yaw - 100 * OrbitCamera.DEGREES_PER_PIXEL, camera.yawDegrees(), 1e-9);
        assertEquals(before + 1, camera.version());
    }

    @Test
    void wheelZoomMovesTheCameraCloser() {
        double distance = camera.distance();

        input.zoom(3);

        assertTrue(camera.distance() < distance);
        assertFalse(input.modalOpen());
    }
}
