package android.content;

import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;

/** A {@link Context} that delegates to another one. */
public class ContextWrapper extends Context {
    private Context base;

    public ContextWrapper(Context base) {
        this.base = base;
    }

    protected void attachBaseContext(Context base) {
        this.base = base;
    }

    public Context getBaseContext() {
        return base;
    }

    @Override
    public Object getSystemService(String name) {
        return base != null ? base.getSystemService(name) : super.getSystemService(name);
    }

    @Override
    public String getPackageName() {
        return base != null ? base.getPackageName() : super.getPackageName();
    }

    @Override
    public String getPackageCodePath() {
        return base != null ? base.getPackageCodePath() : super.getPackageCodePath();
    }

    @Override
    public PackageManager getPackageManager() {
        return base != null ? base.getPackageManager() : super.getPackageManager();
    }

    @Override
    public Resources getResources() {
        return base != null ? base.getResources() : super.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return base != null ? base.getAssets() : super.getAssets();
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return base != null ? base.getSharedPreferences(name, mode) : super.getSharedPreferences(name, mode);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return base != null ? base.registerReceiver(receiver, filter) : super.registerReceiver(receiver, filter);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        if (base != null) {
            base.unregisterReceiver(receiver);
        }
    }
}
