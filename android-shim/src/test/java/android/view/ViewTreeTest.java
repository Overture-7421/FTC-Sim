package android.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import android.app.Activity;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.jupiter.api.Test;

class ViewTreeTest {
    private final Activity activity = new Activity();

    @Test
    void aTextViewKeepsWhatItIsGiven() {
        TextView text = new TextView(activity);

        assertEquals("", text.getText().toString());
        text.setText("Panels is on");
        text.setTextColor(0xffff0000);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setId(7);

        assertEquals("Panels is on", text.getText().toString());
        assertEquals(0xffff0000, text.getCurrentTextColor());
        assertSame(Typeface.DEFAULT_BOLD, text.getTypeface());
        assertEquals(7, text.getId());
        assertEquals(View.NO_ID, new View(activity).getId());
        assertSame(activity, text.getContext());
    }

    @Test
    void childrenAreKeptInTheOrderTheyAreAddedAndTheyKnowTheirParent() {
        LinearLayout column = new LinearLayout(activity);
        TextView first = new TextView(activity);
        TextView second = new TextView(activity);
        TextView inserted = new TextView(activity);

        column.addView(first);
        column.addView(second);
        column.addView(inserted, 1);

        assertEquals(3, column.getChildCount());
        assertSame(first, column.getChildAt(0));
        assertSame(inserted, column.getChildAt(1));
        assertSame(second, column.getChildAt(2));
        assertSame(column, inserted.getParent());
        assertNull(column.getChildAt(3));
        assertNull(column.getChildAt(-1));

        column.removeView(inserted);

        assertEquals(2, column.getChildCount());
        assertNull(inserted.getParent());
    }

    @Test
    void aViewCanHaveOnlyOneParentAtATime() {
        LinearLayout one = new LinearLayout(activity);
        LinearLayout other = new LinearLayout(activity);
        TextView text = new TextView(activity);
        one.addView(text);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> other.addView(text));

        assertEquals(
                "The specified child already has a parent. You must call removeView() on the child's parent first.",
                refused.getMessage());
    }

    @Test
    void layoutParametersCarryTheSizeAndTheMargins() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(1, 2, 3, 4);
        TextView text = new TextView(activity);

        text.setLayoutParams(params);

        assertSame(params, text.getLayoutParams());
        assertEquals(-1, params.width);
        assertEquals(-2, params.height);
        assertEquals(1, params.leftMargin);
        assertEquals(2, params.topMargin);
        assertEquals(3, params.rightMargin);
        assertEquals(4, params.bottomMargin);
        assertThrows(NullPointerException.class, () -> text.setLayoutParams(null));
    }

    @Test
    void theActivityHasNoViewsAndRunsUiWorkAtOnce() {
        boolean[] ran = new boolean[1];

        activity.runOnUiThread(() -> ran[0] = true);

        assertNull(activity.<View>findViewById(0x7f0b0001));
        assertEquals(true, ran[0]);
    }
}
