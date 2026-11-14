package android.app;

import android.content.ContextWrapper;
import android.view.View;

/**
 * The simulator has no robot-controller screen, but the SDK threads an Activity through its signatures
 * (OpMode manager, HardwareMap owners). This type exists so those signatures link; the host passes
 * {@code null} where the SDK tolerates it and a plain instance where it does not. The activity never has a
 * content view, so no view of the app can be found in it.
 */
public class Activity extends ContextWrapper {
    public Activity() {
        super(null);
    }

    /** Always null: the activity has no content view, as one that never called {@code setContentView} has none. */
    public <T extends View> T findViewById(int id) {
        return null;
    }

    /** There is no separate UI thread, so the action runs at once on the calling thread. */
    public void runOnUiThread(Runnable action) {
        action.run();
    }
}
