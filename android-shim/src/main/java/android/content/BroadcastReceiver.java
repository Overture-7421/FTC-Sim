package android.content;

/**
 * Receives broadcast intents. The simulated controller has no source of broadcasts such as USB attachment, so
 * nothing ever calls a receiver; the type exists so that code which registers one can run.
 */
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
}
