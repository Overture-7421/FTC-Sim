package android.app;

import android.Unsupported;
import android.content.Context;
import android.content.Intent;

/**
 * A token that lets another party perform an intent later. The simulated controller has no other party, so a
 * pending intent can be created and handed on, as code that asks for USB permission does, but never sent.
 */
public final class PendingIntent {
    public static final int FLAG_ONE_SHOT = 1 << 30;
    public static final int FLAG_NO_CREATE = 1 << 29;
    public static final int FLAG_CANCEL_CURRENT = 1 << 28;
    public static final int FLAG_UPDATE_CURRENT = 1 << 27;
    public static final int FLAG_IMMUTABLE = 1 << 26;
    public static final int FLAG_MUTABLE = 1 << 25;

    private final Intent intent;

    private PendingIntent(Intent intent) {
        this.intent = intent;
    }

    public static PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
        return new PendingIntent(intent);
    }

    public static PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags) {
        return new PendingIntent(intent);
    }

    public void send() {
        throw Unsupported.member("android.app.PendingIntent", "send()");
    }

    @Override
    public String toString() {
        return "PendingIntent{" + intent.getAction() + "}";
    }
}
