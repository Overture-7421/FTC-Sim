package android.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntentTest {
    @Test
    void aCopyCarriesTheActionAndExtrasButNotLaterChangesToTheOriginal() {
        Intent original = new Intent("alive").putExtra("timeout", 20).putExtra("owner", "rc");

        Intent copy = new Intent(original);
        original.putExtra("timeout", 99);

        assertEquals("alive", copy.getAction());
        assertEquals(20, copy.getIntExtra("timeout", -1));
        assertEquals("rc", copy.getStringExtra("owner"));
    }

    @Test
    void extrasOfAnotherTypeOrThatAreAbsentReadAsTheDefault() {
        Intent intent = new Intent().putExtra("count", 3);

        assertTrue(intent.hasExtra("count"));
        assertEquals(-1L, intent.getLongExtra("count", -1L));
        assertEquals(7, intent.getIntExtra("missing", 7));
        assertNull(intent.getStringExtra("count"));
        assertNull(new Intent().getExtras());
    }
}
