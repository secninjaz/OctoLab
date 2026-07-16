package com.gl4a.utils;

import android.content.Context;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

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
                            .build();
                }
            }
        }
        return sInstance;
    }
}
