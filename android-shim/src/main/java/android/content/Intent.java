package android.content;

import android.os.Bundle;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A description of an operation to perform: an action, its extras and flags. The simulated controller has
 * no other apps to deliver an intent to, so this is the plain data holder; nothing resolves a component.
 */
public class Intent {
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";

    private String action;
    private String packageName;
    private int flags;
    private final Set<String> categories = new LinkedHashSet<>();
    private Bundle extras;

    public Intent() {}

    public Intent(String action) {
        this.action = action;
    }

    public Intent(Intent other) {
        action = other.action;
        packageName = other.packageName;
        flags = other.flags;
        categories.addAll(other.categories);
        extras = other.extras == null ? null : new Bundle(other.extras);
    }

    public String getAction() {
        return action;
    }

    public Intent setAction(String action) {
        this.action = action;
        return this;
    }

    public String getPackage() {
        return packageName;
    }

    public Intent setPackage(String packageName) {
        this.packageName = packageName;
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public Intent setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public Intent addFlags(int flags) {
        this.flags |= flags;
        return this;
    }

    public Intent addCategory(String category) {
        categories.add(category);
        return this;
    }

    public boolean hasCategory(String category) {
        return categories.contains(category);
    }

    public Bundle getExtras() {
        return extras == null ? null : new Bundle(extras);
    }

    public boolean hasExtra(String name) {
        return extras != null && extras.containsKey(name);
    }

    public Intent putExtra(String name, String value) {
        extras().putString(name, value);
        return this;
    }

    public Intent putExtra(String name, int value) {
        extras().putInt(name, value);
        return this;
    }

    public Intent putExtra(String name, long value) {
        extras().putLong(name, value);
        return this;
    }

    public Intent putExtra(String name, boolean value) {
        extras().putBoolean(name, value);
        return this;
    }

    public String getStringExtra(String name) {
        return extras == null ? null : extras.getString(name);
    }

    public int getIntExtra(String name, int defaultValue) {
        return extras == null ? defaultValue : extras.getInt(name, defaultValue);
    }

    public long getLongExtra(String name, long defaultValue) {
        return extras == null ? defaultValue : extras.getLong(name, defaultValue);
    }

    public boolean getBooleanExtra(String name, boolean defaultValue) {
        return extras == null ? defaultValue : extras.getBoolean(name, defaultValue);
    }

    private Bundle extras() {
        if (extras == null) {
            extras = new Bundle();
        }
        return extras;
    }
}
