package com.gl4a.utils;

import android.content.Context;

import androidx.annotation.Nullable;

import com.gl4a.fragment.SettingsFragment;

/**
 * THE "Group by project" setting of To-dos and Participating (#287): one for both tabs, per
 * account, kept across restarts; on by default (project headers, a timeline under each).
 */
public final class FeedGrouping {
    private FeedGrouping() {
    }

    private static String key(@Nullable String login) {
        return "group_feed_by_project_" + login;
    }

    public static boolean isGrouped(Context context, @Nullable String login) {
        return context.getSharedPreferences(SettingsFragment.PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(key(login), true);
    }

    public static void setGrouped(Context context, @Nullable String login, boolean grouped) {
        context.getSharedPreferences(SettingsFragment.PREF_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(key(login), grouped).apply();
    }
}
