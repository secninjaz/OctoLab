package com.gl4a.widget;

import android.net.Uri;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import com.gl4a.utils.IntentUtils;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

public class LinkSpan extends ClickableSpan {
    private final String mUrl;

    public LinkSpan(String url) {
        mUrl = url;
    }

    @Override
    public void onClick(@NonNull View widget) {
        FragmentActivity activity = IntentUtils.findActivity(widget.getContext());
        if (activity != null) {
            IntentUtils.openLinkInternallyOrExternally(activity, Uri.parse(mUrl));
        }
    }

    @Override
    public void updateDrawState(@NonNull TextPaint ds) {
        ds.setColor(ds.linkColor);
        ds.setUnderlineText(false);
    }
}
