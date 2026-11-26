package android.webkit;

/** Placeholder for Android's {@code WebChromeClient} so SDK classes extending it (such as BlocksOpMode) can link. */
public class WebChromeClient {
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        return false;
    }
}
