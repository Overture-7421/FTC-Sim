package android.widget;

import android.content.Context;
import android.util.Log;
import android.view.View;

/**
 * A transient on-screen message. The simulated controller has no screen, so {@link #show()} writes the message to the
 * log; {@link #getView()} returns a layout containing a {@link TextView} with {@code android.R.id.message}
 * ({@code 0x0102000b}) so SDK callers that style the toast's text view before showing it succeed.
 */
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    /** {@code android.R.id.message}, which the SDK's {@code AppUtil.showToast} looks up on the toast view. */
    private static final int MESSAGE_VIEW_ID = 0x0102000b;

    private final TextView messageView;
    private View view;
    private int duration;

    public Toast(Context context) {
        this.messageView = new TextView(context);
        this.messageView.setId(MESSAGE_VIEW_ID);
        LinearLayout container = new LinearLayout(context);
        container.addView(messageView);
        this.view = container;
    }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        Toast toast = new Toast(context);
        toast.messageView.setText(text);
        toast.duration = duration;
        return toast;
    }

    public View getView() {
        return view;
    }

    public void setView(View view) {
        this.view = view;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public void show() {
        CharSequence text = messageView.getText();
        if (text != null && !text.isEmpty()) {
            Log.i("Toast", text.toString());
        }
    }

    public void cancel() {}
}
