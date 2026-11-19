package com.gl4a.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

/**
 * THE mark on a filter button whose filter or sort isn't the default (#336): a small dot in the
 * accent colour at its top end, so a changed list can't pass for the whole one.
 */
public final class FilterIndicator {
    private static final int DOT_DP = 8;

    private FilterIndicator() {
    }

    /** {@code item} shows {@code icon}, with the dot when {@code changed}. */
    public static void apply(Context context, MenuItem item, @DrawableRes int icon,
            boolean changed) {
        Drawable base = ContextCompat.getDrawable(context, icon);
        if (base == null || !changed) {
            item.setIcon(base);
            return;
        }
        TypedValue accent = new TypedValue();
        context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorAccent, accent, true);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent.data);
        int size = Math.round(DOT_DP * context.getResources().getDisplayMetrics().density);
        dot.setSize(size, size);
        LayerDrawable marked = new LayerDrawable(new Drawable[] { base.mutate(), dot });
        marked.setLayerGravity(1, Gravity.TOP | Gravity.END);
        marked.setLayerSize(1, size, size);
        item.setIcon(marked);
    }
}
