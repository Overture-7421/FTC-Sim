package android.content;

import android.Unsupported;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Interface to global information about an application environment. Only what the hosted SDK uses is provided. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String USB_SERVICE = "usb";
    public static final String WIFI_SERVICE = "wifi";
    public static final String ALARM_SERVICE = "alarm";
    public static final String UI_MODE_SERVICE = "uimode";
    public static final String LOCATION_SERVICE = "location";
    public static final String WIFI_P2P_SERVICE = "wifip2p";

    public Object getSystemService(String name) {
        throw Unsupported.member("android.content.Context", "getSystemService(" + name + ")");
    }

    public String getPackageName() {
        return "dev.ftcsim.host";
    }

    /** Full path of the application's code: on a device its APK, in the simulator the robot program's class path. */
    public String getPackageCodePath() {
        throw Unsupported.member("android.content.Context", "getPackageCodePath()");
    }

    public PackageManager getPackageManager() {
        throw Unsupported.member("android.content.Context", "getPackageManager()");
    }

    public Context getApplicationContext() {
        return this;
    }

    public Resources getResources() {
        return ApplicationResources.INSTANCE;
    }

    public AssetManager getAssets() {
        return ApplicationResources.ASSETS;
    }

    public String getString(int resourceId) {
        return getResources().getString(resourceId);
    }

    public String getString(int resourceId, Object... formatArguments) {
        return getResources().getString(resourceId, formatArguments);
    }

    public int getColor(int resourceId) {
        return getResources().getColor(resourceId);
    }

    /** The same name gives the same preferences, as on a device; the mode is ignored because no other app exists. */
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return ApplicationResources.PREFERENCES.computeIfAbsent(name, key -> new InMemorySharedPreferences());
    }

    /**
     * Registering a receiver succeeds and nothing is ever delivered to it, because the simulated controller has
     * no source of broadcasts such as USB attachment or battery changes. The result is the sticky broadcast
     * that matches, of which there is none.
     */
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return null;
    }

    public void unregisterReceiver(BroadcastReceiver receiver) {}

    /** One resource table and one set of assets serve the whole application, however many contexts wrap it. */
    private static final class ApplicationResources {
        static final Resources INSTANCE = new Resources();
        static final AssetManager ASSETS = new AssetManager();
        static final Map<String, SharedPreferences> PREFERENCES = new ConcurrentHashMap<>();
    }
}
