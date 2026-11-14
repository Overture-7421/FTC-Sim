package android.graphics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ColorTest {
    @Test
    void channelsPackAndUnpackAsArgb() {
        int color = Color.argb(0x12, 0x34, 0x56, 0x78);

        assertEquals(0x12345678, color);
        assertEquals(0x12, Color.alpha(color));
        assertEquals(0x34, Color.red(color));
        assertEquals(0x56, Color.green(color));
        assertEquals(0x78, Color.blue(color));
        assertEquals(0xFF102030, Color.rgb(0x10, 0x20, 0x30));
    }

    @Test
    void colorsParseFromHexAndFromNamesAndBadTextIsRefused() {
        assertEquals(0xFF336699, Color.parseColor("#336699"));
        assertEquals(0x80336699, Color.parseColor("#80336699"));
        assertEquals(Color.RED, Color.parseColor("Red"));
        assertThrows(IllegalArgumentException.class, () -> Color.parseColor("#12"));
        assertThrows(IllegalArgumentException.class, () -> Color.parseColor("chartreuse"));
    }

    @Test
    void hsvAndRgbConvertBothWaysForThePrimariesAndAnArbitraryColor() {
        float[] hsv = new float[3];

        Color.colorToHSV(Color.GREEN, hsv);
        assertArrayEquals(new float[] {120f, 1f, 1f}, hsv, 1e-4f);
        Color.colorToHSV(Color.BLUE, hsv);
        assertArrayEquals(new float[] {240f, 1f, 1f}, hsv, 1e-4f);
        Color.colorToHSV(Color.WHITE, hsv);
        assertArrayEquals(new float[] {0f, 0f, 1f}, hsv, 1e-4f);

        int color = Color.rgb(200, 100, 50);
        Color.colorToHSV(color, hsv);
        assertEquals(color, Color.HSVToColor(hsv));
        assertEquals(Color.rgb(255, 0, 0), Color.HSVToColor(new float[] {360f, 1f, 1f}));
    }

    @Test
    void luminanceRunsFromBlackToWhite() {
        assertEquals(0f, Color.luminance(Color.BLACK), 1e-6f);
        assertEquals(1f, Color.luminance(Color.WHITE), 1e-4f);
    }
}
