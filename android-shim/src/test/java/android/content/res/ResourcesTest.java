package android.content.res;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourcesTest {
    private final Resources resources = new Resources();

    @Test
    void stringsComeFromTheTableWithTheirEscapesApplied() {
        assertEquals("Hello\nWorld\\ \t!", resources.getString(0x7f0f0001));
    }

    @Test
    void formattedStringsTakeTheirArguments() {
        assertEquals("robot has 7", resources.getString(0x7f0f0002, "robot", 7));
    }

    @Test
    void integersBooleansAndColorsAreReadInTheirOwnTypes() {
        assertEquals(42, resources.getInteger(0x7f060001));
        assertEquals(42, resources.getInteger(0x7f060002));
        assertTrue(resources.getBoolean(0x7f050001));
        assertEquals(0xffff0000, resources.getColor(0x7f070001));
        assertEquals(0x80ff0000, resources.getColor(0x7f070002));
        assertEquals(0xff00ff00, resources.getColor(0x7f070003));
    }

    @Test
    void anIdWithoutAValueOfThatTypeIsNotFound() {
        Resources.NotFoundException missing = assertThrows(Resources.NotFoundException.class, () -> resources.getString(0x7f0f00ff));
        assertEquals("String resource ID #0x7f0f00ff", missing.getMessage());
        assertThrows(Resources.NotFoundException.class, () -> resources.getInteger(0x7f0f0001));
    }

    @Test
    void dimensionsAreReadInPixelsOfA160DotsPerInchScreen() {
        assertEquals(16f, resources.getDimension(0x7f030001));
        assertEquals(80f, resources.getDimension(0x7f030002));
        assertEquals(80f, resources.getDimension(0x7f030004));
        assertEquals(16, resources.getDimensionPixelSize(0x7f030001));
        assertEquals(16, resources.getDimensionPixelOffset(0x7f030001));
    }

    @Test
    void aFractionIsCutOffForAnOffsetAndRoundedForASizeThatIsNeverRoundedAwayToNothing() {
        assertEquals(0, resources.getDimensionPixelOffset(0x7f030003));
        assertEquals(1, resources.getDimensionPixelSize(0x7f030003));
        assertEquals(-2, resources.getDimensionPixelOffset(0x7f030005));
        assertEquals(-3, resources.getDimensionPixelSize(0x7f030005));
    }

    @Test
    void aDimensionIsFoundByNameAndOnlyAsADimension() {
        assertEquals(0x7f030001, resources.getIdentifier("margin", "dimen", "any.package"));
        assertEquals(0, resources.getIdentifier("margin", "string", "any.package"));
        assertThrows(Resources.NotFoundException.class, () -> resources.getDimension(0x7f0f0001));
        assertThrows(Resources.NotFoundException.class, () -> resources.getDimension(0));
    }

    @Test
    void xmlResourcesParseAsEmptyDocumentsAndFailLoudlyOnUnsupportedCalls() throws Exception {
        try (XmlResourceParser parser = resources.getXml(0x7f090001)) {
            assertEquals(org.xmlpull.v1.XmlPullParser.END_DOCUMENT, parser.getEventType());
            assertEquals(org.xmlpull.v1.XmlPullParser.END_DOCUMENT, parser.next());
            assertThrows(UnsupportedOperationException.class, parser::getName);
        }
    }
}
