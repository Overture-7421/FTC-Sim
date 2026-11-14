package dev.ftcsim.importer.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceValuesTest {

    @Test
    void whitespaceCollapsesUnlessQuoted() {
        assertEquals("Hello world", ResourceValues.resolveString("  Hello \n   world  "));
        assertEquals("  keep   these  ", ResourceValues.resolveString("\"  keep   these  \""));
        assertEquals("a b", ResourceValues.resolveString("a \"\" b"));
    }

    @Test
    void backslashEscapesApply() {
        assertEquals("a\nb\tc", ResourceValues.resolveString("a\\nb\\tc"));
        assertEquals("ABC", ResourceValues.resolveString("\\u0041BC"));
        assertEquals("it's \"fine\" @home", ResourceValues.resolveString("it\\'s \\\"fine\\\" \\@home"));
        assertEquals("tail\\u00", ResourceValues.resolveString("tail\\\\u00"), "an escaped backslash is one backslash");
        assertEquals("bad u12", ResourceValues.resolveString("bad \\u12"), "a short or non-hexadecimal unicode escape is kept as text");
    }

    @Test
    void aValueThatIsOnlyAStringReferenceTakesTheReferencedText() {
        Map<String, String> strings = Map.of("other", "  the   other  ");

        assertEquals("the other", ResourceValues.resolveReference("@string/other", strings));
        assertEquals("@string/missing", ResourceValues.resolveReference("@string/missing", strings));
        assertEquals("see @string/other", ResourceValues.resolveReference("see @string/other", strings));
    }

    @Test
    void theTableEscapesWhatWouldBreakALine() {
        assertEquals("a\\\\b\\nc\\td\\re", ResourceValues.escape("a\\b\nc\td\re"));
    }
}
