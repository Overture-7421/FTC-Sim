package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/** A view group that lays its children out in a row or a column; the simulated controller only keeps the tree. */
public class LinearLayout extends ViewGroup {
    public LinearLayout(Context context) {
        super(context);
    }

    /** Layout parameters for a child of a linear layout. */
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
