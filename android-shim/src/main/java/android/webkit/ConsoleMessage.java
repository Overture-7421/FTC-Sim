package android.webkit;

import android.Unsupported;

/** Placeholder for Android's {@code ConsoleMessage} so SDK classes referencing WebKit can link on desktop. */
public class ConsoleMessage {
    public String message() {
        throw Unsupported.member("android.webkit.ConsoleMessage", "message()");
    }
}
