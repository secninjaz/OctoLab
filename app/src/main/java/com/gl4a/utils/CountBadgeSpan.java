package com.gl4a.utils;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE unread count drawn into a text (#280): the number, white and bold, on a rounded badge of
 * the given colour, e.g. after an account's name in the account list. Part of the text itself, so
 * it can't show on another row as a separate view did when the drawer reused its rows.
 */
public final class CountBadgeSpan extends ReplacementSpan {
    private static final float TEXT_SCALE = 0.8f;
    private final @ColorInt int mBackground;
    private final float mDensity;

    public CountBadgeSpan(@ColorInt int background, float density) {
        mBackground = background;
        mDensity = density;
    }

    /** {@code text} followed by {@code count} as a badge; just {@code text} when 0. */
    public static CharSequence append(CharSequence text, int count, @ColorInt int background,
            float density) {
        if (count <= 0) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text).append("  ");
        int start = sb.length();
        sb.append(count > 999 ? "999+" : String.valueOf(count));
        sb.setSpan(new CountBadgeSpan(background, density), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private Paint badgePaint(Paint paint) {
        Paint p = new Paint(paint);
        p.setTextSize(paint.getTextSize() * TEXT_SCALE);
        p.setFakeBoldText(true);
        return p;
    }

    private float padding() {
        return 5 * mDensity;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
            @Nullable Paint.FontMetricsInt fm) {
        Paint p = badgePaint(paint);
        Paint.FontMetrics m = p.getFontMetrics();
        float height = m.descent - m.ascent;
        float width = Math.max(p.measureText(text, start, end) + 2 * padding(), height);
        return Math.round(width);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
            int top, int y, int bottom, @NonNull Paint paint) {
        Paint p = badgePaint(paint);
        Paint.FontMetrics m = p.getFontMetrics();
        float height = m.descent - m.ascent;
        float width = Math.max(p.measureText(text, start, end) + 2 * padding(), height);
        // Centred on the line's text
        Paint.FontMetrics lm = paint.getFontMetrics();
        float centerY = y + (lm.ascent + lm.descent) / 2;
        RectF rect = new RectF(x, centerY - height / 2, x + width, centerY + height / 2);
        int color = p.getColor();
        p.setColor(mBackground);
        canvas.drawRoundRect(rect, height / 2, height / 2, p);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, start, end, rect.centerX(), centerY - (m.ascent + m.descent) / 2, p);
        p.setColor(color);
    }
}
