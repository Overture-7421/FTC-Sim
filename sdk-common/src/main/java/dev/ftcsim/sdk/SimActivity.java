package dev.ftcsim.sdk;

import android.app.Activity;
import android.content.Context;

/** The activity the SDK's OpMode manager holds on to as its context; it has no screen and defers to the application. */
final class SimActivity extends Activity {
    SimActivity(Context application) {
        attachBaseContext(application);
    }
}
