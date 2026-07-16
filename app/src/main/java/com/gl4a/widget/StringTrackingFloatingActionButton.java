package com.gl4a.widget;

import android.content.Context;
import android.util.AttributeSet;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

/**
 * A FloatingActionButton stub that optionally tracks a state string
 * (e.g. milestone open/closed state) to tint itself accordingly.
 * In this GitLab port the visual state change is a no-op.
 */
public class StringTrackingFloatingActionButton extends FloatingActionButton {

    public StringTrackingFloatingActionButton(Context context) {
        super(context);
    }

    public StringTrackingFloatingActionButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StringTrackingFloatingActionButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Called to reflect the current milestone/issue state (e.g. "open" or "closed"). No-op stub. */
    public void setState(String state) {
        // no visual distinction needed in this stub
    }
}
