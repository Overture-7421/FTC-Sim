package android.view;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** A view that contains other views, in the order they were added. */
public abstract class ViewGroup extends View {
    private final List<View> children = new ArrayList<>();

    protected ViewGroup(Context context) {
        super(context);
    }

    public synchronized int getChildCount() {
        return children.size();
    }

    /** The child at the index, or null when there is none, as on a device. */
    public synchronized View getChildAt(int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }

    public void addView(View child) {
        addView(child, -1);
    }

    public void addView(View child, LayoutParams params) {
        child.setLayoutParams(params);
        addView(child, -1);
    }

    /** Adds the child at the index, or at the end for -1. */
    public synchronized void addView(View child, int index) {
        if (child.getParent() != null) {
            throw new IllegalStateException(
                    "The specified child already has a parent. You must call removeView() on the child's parent first.");
        }
        children.add(index < 0 ? children.size() : index, child);
        child.setParent(this);
    }

    public synchronized void removeView(View view) {
        if (children.remove(view)) {
            view.setParent(null);
        }
    }

    /** How a view asks its parent to size it. */
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width;
        public int height;

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /** Layout parameters that also carry the space around the view. */
    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin;
        public int topMargin;
        public int rightMargin;
        public int bottomMargin;

        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }

        public void setMargins(int left, int top, int right, int bottom) {
            leftMargin = left;
            topMargin = top;
            rightMargin = right;
            bottomMargin = bottom;
        }
    }
}
