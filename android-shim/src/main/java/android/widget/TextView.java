package android.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;

/** A view that shows text; the simulated controller keeps the text, the colour and the typeface and draws none of them. */
public class TextView extends View {
    private CharSequence text = "";
    private int textColor = 0xff000000;
    private float textSize = 14f;
    private Typeface typeface = Typeface.DEFAULT;

    public TextView(Context context) {
        super(context);
    }

    public CharSequence getText() {
        return text;
    }

    public void setText(CharSequence text) {
        this.text = text == null ? "" : text;
    }

    public int getCurrentTextColor() {
        return textColor;
    }

    public void setTextColor(int color) {
        this.textColor = color;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextSize(float size) {
        this.textSize = size;
    }

    public Typeface getTypeface() {
        return typeface;
    }

    public void setTypeface(Typeface typeface) {
        this.typeface = typeface;
    }
}
