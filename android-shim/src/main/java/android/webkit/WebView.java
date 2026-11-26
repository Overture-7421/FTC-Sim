package android.webkit;

import android.Unsupported;
import android.content.Context;
import android.view.ViewGroup;

/** Placeholder for Android's {@code WebView} so SDK classes referencing WebKit (such as BlocksOpMode) can link. */
public class WebView extends ViewGroup {
    public WebView(Context context) {
        super(context);
    }

    public WebSettings getSettings() {
        throw Unsupported.member("android.webkit.WebView", "getSettings()");
    }

    public void setWebChromeClient(WebChromeClient client) {
        throw Unsupported.member("android.webkit.WebView", "setWebChromeClient(WebChromeClient)");
    }
}
