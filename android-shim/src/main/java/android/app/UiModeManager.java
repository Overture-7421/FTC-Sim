package android.app;

import android.content.res.Configuration;

/** The UI mode service. The simulated controller is an ordinary handheld-class Android device, not a car dock or a TV. */
public class UiModeManager {
    public int getCurrentModeType() {
        return Configuration.UI_MODE_TYPE_NORMAL;
    }
}
