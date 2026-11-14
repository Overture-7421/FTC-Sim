package dev.ftcsim.ui.glfw;

import static org.lwjgl.glfw.GLFW.GLFW_CONNECTED;
import static org.lwjgl.glfw.GLFW.GLFW_DISCONNECTED;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_LAST;
import static org.lwjgl.glfw.GLFW.glfwGetGamepadState;
import static org.lwjgl.glfw.GLFW.glfwGetJoystickGUID;
import static org.lwjgl.glfw.GLFW.glfwGetJoystickName;
import static org.lwjgl.glfw.GLFW.glfwJoystickIsGamepad;
import static org.lwjgl.glfw.GLFW.glfwJoystickPresent;
import static org.lwjgl.glfw.GLFW.glfwSetJoystickCallback;

import dev.ftcsim.ui.input.PadReading;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.glfw.GLFWJoystickCallback;

/**
 * Polls GLFW's joystick and gamepad API from the {@link GlfwThread} without any window, so controller input runs
 * next to a Compose UI that owns the only windows. Every connected controller is read on every pass of the GLFW
 * loop and the readings go to a listener on the same thread; the hot-plug callback only counts connections, since
 * the next pass sees the change anyway.
 */
public final class GamepadPoller implements AutoCloseable {
    private static final int GAMEPAD_AXES = 6;
    private static final int GAMEPAD_BUTTONS = 15;

    private final GlfwThread glfw;
    private final Consumer<List<PadReading>> listener;
    private final Runnable pollTask = this::poll;
    private final GLFWGamepadState state = GLFWGamepadState.calloc();
    private final AtomicLong polls = new AtomicLong();
    private final AtomicLong connects = new AtomicLong();
    private final AtomicLong disconnects = new AtomicLong();
    private final GLFWJoystickCallback callback = GLFWJoystickCallback.create((id, event) -> {
        if (event == GLFW_CONNECTED) {
            connects.incrementAndGet();
        } else if (event == GLFW_DISCONNECTED) {
            disconnects.incrementAndGet();
        }
    });
    private volatile long worstPollNanos;

    /** @param listener called on the GLFW thread after every poll with the controllers that are connected */
    public GamepadPoller(GlfwThread glfw, Consumer<List<PadReading>> listener) throws Exception {
        this.glfw = glfw;
        this.listener = listener;
        glfw.call(() -> {
            glfwSetJoystickCallback(callback);
            return null;
        });
        glfw.addPoller(pollTask);
    }

    public long polls() {
        return polls.get();
    }

    public long connectEvents() {
        return connects.get();
    }

    public long disconnectEvents() {
        return disconnects.get();
    }

    public long worstPollNanos() {
        return worstPollNanos;
    }

    private void poll() {
        long start = System.nanoTime();
        List<PadReading> found = new ArrayList<>(0);
        for (int id = GLFW_JOYSTICK_1; id <= GLFW_JOYSTICK_LAST; id++) {
            if (!glfwJoystickPresent(id)) {
                continue;
            }
            boolean mapped = glfwJoystickIsGamepad(id) && glfwGetGamepadState(id, state);
            float[] axes = new float[mapped ? GAMEPAD_AXES : 0];
            boolean[] buttons = new boolean[mapped ? GAMEPAD_BUTTONS : 0];
            for (int i = 0; i < axes.length; i++) {
                axes[i] = state.axes(i);
            }
            for (int i = 0; i < buttons.length; i++) {
                buttons[i] = state.buttons(i) != 0;
            }
            String guid = glfwGetJoystickGUID(id);
            found.add(new PadReading(id, glfwGetJoystickName(id), guid == null ? "" : guid, mapped, axes, buttons));
        }
        polls.incrementAndGet();
        listener.accept(found);
        worstPollNanos = Math.max(worstPollNanos, System.nanoTime() - start);
    }

    /** Detaches the hot-plug callback and the polling; the {@link GlfwThread} itself stays up. */
    @Override
    public void close() {
        glfw.removePoller(pollTask);
        try {
            glfw.call(() -> {
                glfwSetJoystickCallback(null);
                callback.free();
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException("could not detach the joystick callback", e);
        }
        state.free();
    }
}
