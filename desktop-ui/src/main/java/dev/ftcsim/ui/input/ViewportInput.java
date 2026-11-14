package dev.ftcsim.ui.input;

import dev.ftcsim.ui.render.OrbitCamera;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Everything the viewport accepts from the user, behind one gate. Keys are captured only while whatever takes keys
 * (the viewport, or the key test area of the Controls screen) has focus, the application window is the active one and
 * no modal (dialog or menu) is open; the moment any of the three stops being true, held keys are released. The camera
 * ignores the pointer while a modal is open. Controllers follow the same rule except for the focus of the key
 * target, plus the viewport having to be on screen, through {@link #onActiveChange}: while another screen is shown
 * the robot is not driven.
 */
public final class ViewportInput {
    private final HeldKeys keys = new HeldKeys();
    private final OrbitCamera camera;
    private boolean focused;
    private boolean windowFocused = true;
    private boolean modalOpen;
    private boolean viewportShown;
    private volatile Consumer<Boolean> activeListener = active -> {};
    private volatile IntPredicate keyboardKeys = key -> false;

    public ViewportInput(OrbitCamera camera) {
        this.camera = camera;
    }

    public HeldKeys keys() {
        return keys;
    }

    public OrbitCamera camera() {
        return camera;
    }

    /** Says which key codes belong to the keyboard gamepad; those are consumed while the key target has focus. */
    public void setKeyboardKeys(IntPredicate mapped) {
        keyboardKeys = mapped;
    }

    public boolean mapsKey(int keyCode) {
        return keyboardKeys.test(keyCode);
    }

    /**
     * Called whenever the window focus, a modal or whether the viewport is on screen changes, with whether input from
     * controllers should reach the robot: the viewport is shown, the window is active and no modal is open.
     */
    public void onActiveChange(Consumer<Boolean> listener) {
        activeListener = listener;
    }

    /** The viewport itself gained or lost keyboard focus. */
    public synchronized void focusChanged(boolean hasFocus) {
        focused = hasFocus;
        update(hasFocus ? "focus gained" : "focus lost");
    }

    /** The application window became the active window or stopped being it. */
    public synchronized void windowFocusChanged(boolean hasFocus) {
        windowFocused = hasFocus;
        update(hasFocus ? "window activated" : "window deactivated");
    }

    public synchronized void modalChanged(boolean open) {
        modalOpen = open;
        update(open ? "modal opened" : "modal closed");
    }

    /** The viewport came on screen or went off it, for instance because another destination was opened. */
    public synchronized void viewportShown(boolean shown) {
        viewportShown = shown;
        update(shown ? "viewport shown" : "viewport hidden");
    }

    public synchronized boolean focused() {
        return focused;
    }

    public synchronized boolean modalOpen() {
        return modalOpen;
    }

    public void keyPressed(int keyCode) {
        keys.press(keyCode);
    }

    public void keyReleased(int keyCode) {
        keys.release(keyCode);
    }

    public synchronized void orbit(double deltaXPixels, double deltaYPixels) {
        if (modalOpen || (deltaXPixels == 0.0 && deltaYPixels == 0.0)) {
            return;
        }
        camera.orbit(deltaXPixels, deltaYPixels);
    }

    public synchronized void zoom(double steps) {
        if (modalOpen || steps == 0.0) {
            return;
        }
        camera.zoom(steps);
    }

    private void update(String reason) {
        keys.setCaptureEnabled(focused && windowFocused && !modalOpen, reason);
        activeListener.accept(viewportShown && windowFocused && !modalOpen);
    }
}
