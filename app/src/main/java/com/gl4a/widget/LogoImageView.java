package com.gl4a.widget;

import android.content.Context;
import android.util.AttributeSet;

import com.gl4a.utils.AvatarHandler;

/**
 * The view for every project and group logo, on the shared logo frame, so the frame's look is
 * set in one place (AvatarHandler's logo style, #246).
 */
public class LogoImageView extends FixedSizeImageView {
    public LogoImageView(Context context) {
        super(context);
        init();
    }

    public LogoImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LogoImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        AvatarHandler.setLogoFrame(this, true);
    }
}
