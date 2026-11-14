package dev.ftcsim.ui.input;

import dev.ftcsim.contracts.robot.GamepadState;
import java.util.List;

/**
 * Decides what each of the two FTC gamepads reports, from the controllers that are connected, the keyboard and the
 * person's settings ({@link GamepadRouting} says who listens to what), and passes a gamepad's state on only when it
 * changes. Both gamepads always exist: one with nothing assigned is neutral. A controller that goes away frees its
 * place at once, so a lost controller can never leave a stick held.
 *
 * <p>Nothing is reported but neutral while input is not {@linkplain #setActive active}: the application window does not
 * have focus, a dialog is open, or the viewport is not on screen. {@link #preview} is not affected by that, so the
 * Controls screen can show a controller moving while the robot is not listening. Safe to call from any thread; the
 * sink is called with the router locked, so it must not block.
 */
public final class GamepadRouter {
    /** Where the states go. */
    public interface Sink {
        /** @param gamepad 1 or 2 */
        void gamepad(int gamepad, GamepadState state);
    }

    /**
     * What a person sees of one gamepad.
     *
     * @param resolved where it listens
     * @param state    what the robot program would be told, dead zones applied
     * @param raw      left x and y, right x and y, left and right trigger, before any dead zone
     */
    public record Preview(GamepadRouting.Resolved resolved, GamepadState state, float[] raw) {}

    private static final int GAMEPADS = 2;

    private final Sink sink;
    private final HeldKeys keys;
    private final GamepadState[] sent = new GamepadState[GAMEPADS];
    private GamepadConfig config = GamepadConfig.DEFAULT;
    private List<PadReading> pads = List.of();
    private boolean active = true;

    public GamepadRouter(Sink sink, HeldKeys keys) {
        this.sink = sink;
        this.keys = keys;
        keys.onChange(this::refresh);
    }

    /** Takes new settings; both gamepads are reported at once if they changed. */
    public void configure(GamepadConfig newConfig) {
        synchronized (this) {
            config = newConfig;
        }
        refresh();
    }

    public synchronized GamepadConfig config() {
        return config;
    }

    /** The controllers connected right now, from the polling thread. */
    public void padsPolled(List<PadReading> readings) {
        synchronized (this) {
            pads = List.copyOf(readings);
        }
        refresh();
    }

    /** Turns all input on or off; off makes both gamepads neutral. */
    public void setActive(boolean on) {
        synchronized (this) {
            active = on;
        }
        refresh();
    }

    /** The controllers connected at the last poll, with and without a gamepad mapping. */
    public synchronized List<PadReading> controllers() {
        return pads;
    }

    /** Where each gamepad listens, with what is wrong if anything. */
    public synchronized GamepadRouting.Resolved[] resolutions() {
        return GamepadRouting.resolve(config, pads);
    }

    /** What gamepad 1 or 2 reports now, whether or not the robot is listening. */
    public synchronized Preview preview(int gamepad) {
        GamepadRouting.Resolved resolved = GamepadRouting.resolve(config, pads)[gamepad - 1];
        GamepadState state = stateOf(resolved, gamepad);
        float[] raw = resolved.source() == GamepadRouting.Source.CONTROLLER
                ? PadMapping.rawAxes(find(resolved.padId()))
                : new float[] {
                    state.leftStickX(), state.leftStickY(), state.rightStickX(), state.rightStickY(),
                    state.leftTrigger(), state.rightTrigger()
                };
        return new Preview(resolved, state, raw);
    }

    public synchronized void refresh() {
        GamepadRouting.Resolved[] resolved = GamepadRouting.resolve(config, pads);
        for (int slot = 0; slot < GAMEPADS; slot++) {
            GamepadState state = active ? stateOf(resolved[slot], slot + 1) : GamepadState.NEUTRAL;
            if (!state.equals(sent[slot])) {
                sent[slot] = state;
                sink.gamepad(slot + 1, state);
            }
        }
    }

    private GamepadState stateOf(GamepadRouting.Resolved resolved, int gamepad) {
        return switch (resolved.source()) {
            case CONTROLLER -> {
                GamepadConfig.Pad settings = config.pad(gamepad);
                yield PadMapping.toState(find(resolved.padId()), settings.stickDeadzone(), settings.triggerDeadzone());
            }
            case KEYBOARD -> config.keyboard().state(keys.held());
            case NONE -> GamepadState.NEUTRAL;
        };
    }

    private PadReading find(int id) {
        for (PadReading pad : pads) {
            if (pad.id() == id) {
                return pad;
            }
        }
        return null;
    }
}
