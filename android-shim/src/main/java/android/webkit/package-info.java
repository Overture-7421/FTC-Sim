/**
 * Minimal WebKit placeholders so SDK classes that reference {@code WebView} or subclass {@code WebChromeClient}
 * (such as {@code BlocksOpMode}) can link on a desktop JVM when reflected upon. Calling their operations fails
 * loudly with {@link android.Unsupported}.
 */
package android.webkit;
