package dev.ftcsim.app.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void aTagIsAVersionWithOrWithoutTheLeadingV() {
        assertEquals(new Version(1, 4, 2, ""), Version.parse("v1.4.2"));
        assertEquals(new Version(1, 4, 2, ""), Version.parse("1.4.2"));
        assertEquals(new Version(2, 0, 0, ""), Version.parse("2"));
        assertEquals(new Version(1, 5, 0, "rc.1"), Version.parse(" v1.5.0-rc.1 "));
        assertEquals("1.4.2", Version.parse("v1.4.2").toString());
        assertEquals("1.5.0-rc.1", Version.parse("1.5.0-rc.1").toString());
    }

    @Test
    void textThatIsNotAVersionIsRefused() {
        for (String text : List.of("", "latest", "v", "1.2.3.4", "1.2.x", "1..2", "-1.2.3", "1.2.3-")) {
            assertThrows(IllegalArgumentException.class, () -> Version.parse(text), text);
        }
    }

    @Test
    void versionsAreOrderedByTheirNumbersNotAsText() {
        assertTrue(Version.parse("1.2.10").compareTo(Version.parse("1.2.9")) > 0, "10 is more than 9");
        assertTrue(Version.parse("1.10.0").compareTo(Version.parse("1.9.9")) > 0);
        assertTrue(Version.parse("2.0.0").compareTo(Version.parse("1.99.99")) > 0);
        assertEquals(0, Version.parse("v1.2.3").compareTo(Version.parse("1.2.3")));
        assertTrue(Version.parse("1.2").compareTo(Version.parse("1.2.0")) == 0, "missing numbers are 0");
    }

    @Test
    void aPreReleaseIsOlderThanItsRelease() {
        assertTrue(Version.parse("1.5.0-rc.1").compareTo(Version.parse("1.5.0")) < 0);
        assertTrue(Version.parse("1.5.0").compareTo(Version.parse("1.5.0-rc.1")) > 0);
        assertTrue(Version.parse("1.5.0-rc.2").compareTo(Version.parse("1.5.0-rc.1")) > 0);
        assertTrue(Version.parse("1.5.0-rc.10").compareTo(Version.parse("1.5.0-rc.9")) > 0, "numbers between dots are numbers");
        assertTrue(Version.parse("1.5.0-rc").compareTo(Version.parse("1.5.0-rc.1")) < 0, "fewer identifiers are older");
        assertTrue(Version.parse("1.4.9").compareTo(Version.parse("1.5.0-rc.1")) < 0, "the numbers come first");
    }

    @Test
    void aBuildWithoutAVersionIsADevelopmentBuildThatNeverLooksForUpdates() throws Exception {
        assertEquals("1.2.3", AppVersion.read(new java.io.ByteArrayInputStream("version=1.2.3\n".getBytes())));
        assertEquals(Version.DEVELOPMENT, AppVersion.read(new java.io.ByteArrayInputStream("version=${appVersion}\n".getBytes())),
                "an unexpanded file, as an IDE run has it");
        assertEquals(Version.DEVELOPMENT, AppVersion.read(new java.io.ByteArrayInputStream("".getBytes())));
        assertFalse(AppVersion.isRelease(), "the tests run on a build made without -PappVersion");
        assertEquals(Version.DEVELOPMENT, AppVersion.current());
    }
}
