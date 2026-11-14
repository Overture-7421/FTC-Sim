package android.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Base class for the application the host presents to the SDK; the simulator supplies the concrete subclass. */
public class Application extends ContextWrapper {

    /** Observer of activity lifecycle events; the simulated robot controller has one activity, which it brings up itself. */
    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, Bundle savedInstanceState);

        void onActivityStarted(Activity activity);

        void onActivityResumed(Activity activity);

        void onActivityPaused(Activity activity);

        void onActivityStopped(Activity activity);

        void onActivitySaveInstanceState(Activity activity, Bundle outState);

        void onActivityDestroyed(Activity activity);
    }

    private final List<ActivityLifecycleCallbacks> lifecycleCallbacks = new CopyOnWriteArrayList<>();

    public Application() {
        super(null);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        lifecycleCallbacks.add(callback);
    }

    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        lifecycleCallbacks.remove(callback);
    }

    /** Tells the observers that the activity was created and is now in the foreground, as Android does when it starts one. */
    public void bringToForeground(Activity activity) {
        for (ActivityLifecycleCallbacks callback : lifecycleCallbacks) {
            callback.onActivityCreated(activity, null);
            callback.onActivityStarted(activity);
            callback.onActivityResumed(activity);
        }
    }
}
