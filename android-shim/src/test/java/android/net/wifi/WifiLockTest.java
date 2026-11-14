package android.net.wifi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WifiLockTest {
    private final WifiManager.WifiLock lock = new WifiManager().createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "robot");

    @Test
    void aReferenceCountedLockStaysHeldUntilEveryAcquireIsReleased() {
        lock.acquire();
        lock.acquire();
        lock.release();
        assertTrue(lock.isHeld());
        lock.release();
        assertFalse(lock.isHeld());
        assertThrows(RuntimeException.class, lock::release);
    }

    @Test
    void aLockThatIsNotReferenceCountedIsReleasedByASingleRelease() {
        lock.setReferenceCounted(false);
        lock.acquire();
        lock.acquire();
        lock.release();
        assertFalse(lock.isHeld());
    }
}
