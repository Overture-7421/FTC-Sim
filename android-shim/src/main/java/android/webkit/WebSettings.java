package android.webkit;

import android.Unsupported;

/** Placeholder for Android's {@code WebSettings} so SDK classes referencing WebKit can link on desktop. */
public abstract class WebSettings {
    public void setJavaScriptEnabled(boolean flag) {
        throw Unsupported.member("android.webkit.WebSettings", "setJavaScriptEnabled(boolean)");
    }
}
