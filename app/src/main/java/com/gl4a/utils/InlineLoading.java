package com.gl4a.utils;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.gl4a.R;

/**
 * THE "loading in place" (#321): a small spinner exactly where content will appear, until it
 * does. Content that loads in the background never pops in unannounced and pushes the screen
 * down: a group's children (#242), list footers while the next page loads, a commit's details,
 * an epic's parent and child items. Every such place uses this, so they look and behave alike.
 */
public final class InlineLoading {
    private InlineLoading() {
    }

    /** A spinner view for {@code parent}, not yet added, e.g. a list footer. */
    public static View create(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext())
                .inflate(R.layout.inline_loading, parent, false);
    }

    /** A spinner added to {@code parent} at {@code index}; remove it with {@link #remove}. */
    public static View insert(ViewGroup parent, int index) {
        View spinner = create(parent);
        parent.addView(spinner, index);
        return spinner;
    }

    /** {@code container} shown, holding only a spinner until its content replaces it. */
    public static View showIn(ViewGroup container) {
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        return insert(container, 0);
    }

    public static void remove(@Nullable View spinner) {
        if (spinner != null && spinner.getParent() instanceof ViewGroup) {
            ((ViewGroup) spinner.getParent()).removeView(spinner);
        }
    }
}
