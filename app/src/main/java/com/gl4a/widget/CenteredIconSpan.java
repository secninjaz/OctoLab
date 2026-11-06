package com.gl4a.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE way to put an icon inside a line of text (#312): drawn at the text's size and centred on
 * its middle, on every Android version. ImageSpan's ALIGN_BOTTOM sits it below the letters,
 * and its ALIGN_CENTER needs Android 10. The icon keeps its own (theme) colours.
 */
public class CenteredIconSpan extends ReplacementSpan {
    private final Drawable mIcon;

    public CenteredIconSpan(@NonNull Drawable icon) {
        mIcon = icon.mutate();
    }

    private int size(Paint paint) {
        return Math.round(paint.getTextSize());
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
            @Nullable Paint.FontMetricsInt fm) {
        if (fm != null) {
            // The line's own height, as the text around it has
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            fm.ascent = metrics.ascent;
            fm.descent = metrics.descent;
            fm.top = metrics.top;
            fm.bottom = metrics.bottom;
        }
        return size(paint);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
            int top, int y, int bottom, @NonNull Paint paint) {
        int size = size(paint);
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        // The middle of the letters: halfway between their top and bottom around the baseline
        float middle = y + (metrics.ascent + metrics.descent) / 2f;
        mIcon.setBounds(0, 0, size, size);
        canvas.save();
        canvas.translate(x, middle - size / 2f);
        mIcon.draw(canvas);
        canvas.restore();
    }
}
