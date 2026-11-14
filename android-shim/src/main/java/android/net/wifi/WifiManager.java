package android.net.wifi;

/**
 * The Wi-Fi service. The simulated controller has no radio, so there is nothing to scan or connect; what the SDK
 * holds on to is a lock that keeps the radio at full performance, whose bookkeeping is all it has.
 */
public class WifiManager {
    public static final int WIFI_MODE_FULL = 1;
    public static final int WIFI_MODE_SCAN_ONLY = 2;
    public static final int WIFI_MODE_FULL_HIGH_PERF = 3;
    public static final int WIFI_MODE_FULL_LOW_LATENCY = 4;

    public WifiLock createWifiLock(int lockType, String tag) {
        return new WifiLock(tag);
    }

    public static class WifiLock {
        private final String tag;
        private boolean referenceCounted = true;
        private int holds;

        private WifiLock(String tag) {
            this.tag = tag;
        }

        public synchronized void setReferenceCounted(boolean referenceCounted) {
            this.referenceCounted = referenceCounted;
        }

        public synchronized void acquire() {
            holds = referenceCounted ? holds + 1 : 1;
        }

        public synchronized void release() {
            if (referenceCounted && holds == 0) {
                throw new RuntimeException("WifiLock under-locked " + tag);
            }
            holds = referenceCounted ? holds - 1 : 0;
        }

        public synchronized boolean isHeld() {
            return holds > 0;
        }
    }
}
