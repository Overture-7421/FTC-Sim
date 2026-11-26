package android.view;

import android.content.Context;

/**
 * The base of everything the robot controller app draws. The simulated controller has no screen, so a view is a
 * node of an in-memory tree that remembers what it was given and draws nothing. The activity starts with no views
 * at all, which is why a library that looks for one of the app's views by its id finds none.
 */
public class View {
    public static final int NO_ID = -1;

    private final Context context;
    private int id = NO_ID;
    private ViewGroup parent;
    private ViewGroup.LayoutParams layoutParams;

    public View(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public ViewGroup getParent() {
        return parent;
    }

    void setParent(ViewGroup parent) {
        this.parent = parent;
    }

    public ViewGroup.LayoutParams getLayoutParams() {
        return layoutParams;
    }

    public void setLayoutParams(ViewGroup.LayoutParams params) {
        if (params == null) {
            throw new NullPointerException("Layout parameters cannot be null");
        }
        this.layoutParams = params;
    }

    /** Returns this view when its id matches {@code id}, or null otherwise. */
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) {
        return id != NO_ID && this.id == id ? (T) this : null;
    }

    /** Called when a view has been clicked. */
    public interface OnClickListener {
        void onClick(View v);
    }
}
