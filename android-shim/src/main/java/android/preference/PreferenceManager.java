package android.preference;

import android.Unsupported;
import android.content.Context;
import android.content.SharedPreferences;

/** The application's default preferences; the settings screens built on top of them do not exist on the simulated controller. */
public class PreferenceManager {
    private PreferenceManager() {}

    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return context.getSharedPreferences(getDefaultSharedPreferencesName(context), Context.MODE_PRIVATE);
    }

    public static String getDefaultSharedPreferencesName(Context context) {
        return context.getPackageName() + "_preferences";
    }

    public static void setDefaultValues(Context context, int resId, boolean readAgain) {
        throw Unsupported.member("android.preference.PreferenceManager", "setDefaultValues(Context, int, boolean)");
    }
}
