package com.gl4a.utils;

import android.content.Context;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.annotation.NonNull;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonSpansFactory;
import io.noties.markwon.core.CoreProps;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

import com.gl4a.utils.IntentUtils;

import org.commonmark.node.Link;

public final class MarkwonProvider {

    private static volatile Markwon sInstance;

    private MarkwonProvider() {}

    public static Markwon get(Context context) {
        if (sInstance == null) {
            synchronized (MarkwonProvider.class) {
                if (sInstance == null) {
                    sInstance = Markwon.builder(context.getApplicationContext())
                            .usePlugin(StrikethroughPlugin.create())
                            .usePlugin(TablePlugin.create(context))
                            .usePlugin(TaskListPlugin.create(context))
                            .usePlugin(LinkifyPlugin.create())
                            .usePlugin(new AbstractMarkwonPlugin() {
                                @Override
                                public void configureSpansFactory(
                                        @NonNull MarkwonSpansFactory.Builder builder) {
                                    // Replace default link span with one that has no underline
                                    builder.setFactory(Link.class, (config, props) -> {
                                        final String url = CoreProps.LINK_DESTINATION.require(props);
                                        return new ClickableSpan() {
                                            @Override
                                            public void onClick(@NonNull View widget) {
                                                androidx.fragment.app.FragmentActivity activity =
                                                        IntentUtils.findActivity(widget.getContext());
                                                if (activity != null) {
                                                    IntentUtils.openLinkInternallyOrExternally(
                                                            activity, android.net.Uri.parse(url));
                                                }
                                            }
                                            @Override
                                            public void updateDrawState(@NonNull TextPaint ds) {
                                                ds.setColor(ds.linkColor);
                                                ds.setUnderlineText(false);
                                            }
                                        };
                                    });
                                }
                            })
                            .build();
                }
            }
        }
        return sInstance;
    }
}
