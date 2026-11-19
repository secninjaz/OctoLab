package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.fragment.SettingsFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE Activity screen's filter and sort (#336), per account and remembered: which kind of item
 * GitLab sends (its events API's target_type or action, for all three tabs), and newest or
 * oldest first (GitLab's own order).
 */
public final class ActivityFeedOptions {
    public static final String FILTER_ALL = "all";
    public static final String FILTER_ISSUES = "issues";
    public static final String FILTER_MERGE_REQUESTS = "merge_requests";
    public static final String FILTER_COMMENTS = "comments";
    public static final String FILTER_PUSHES = "pushes";

    public static final String SORT_NEWEST = "newest";
    public static final String SORT_OLDEST = "oldest";

    private static final String KEY_FILTER = "activity_feed_filter_";
    private static final String KEY_SORT = "activity_feed_sort_";

    private ActivityFeedOptions() {
    }

    private static SharedPreferences prefs() {
        return Gl4Application.get().getSharedPreferences(SettingsFragment.PREF_NAME,
                Context.MODE_PRIVATE);
    }

    private static String account() {
        return String.valueOf(Gl4Application.get().getAuthLogin());
    }

    public static String filter() {
        return prefs().getString(KEY_FILTER + account(), FILTER_ALL);
    }

    public static String sort() {
        return SORT_OLDEST.equals(prefs().getString(KEY_SORT + account(), SORT_NEWEST))
                ? SORT_OLDEST : SORT_NEWEST;
    }

    public static void setFilter(String filter) {
        prefs().edit().putString(KEY_FILTER + account(), filter).apply();
    }

    public static void setSort(String sort) {
        prefs().edit().putString(KEY_SORT + account(), sort).apply();
    }

    public static boolean isFiltered() {
        return !FILTER_ALL.equals(filter());
    }

    public static boolean isOldestFirst() {
        return SORT_OLDEST.equals(sort());
    }

    /** What GitLab is asked for: the kind of item, and the order. */
    public static Map<String, String> queryParams() {
        Map<String, String> params = new HashMap<>();
        switch (filter()) {
            case FILTER_ISSUES: params.put("target_type", "issue"); break;
            case FILTER_MERGE_REQUESTS: params.put("target_type", "merge_request"); break;
            case FILTER_COMMENTS: params.put("action", "commented"); break;
            case FILTER_PUSHES: params.put("action", "pushed"); break;
            default: break;
        }
        params.put("sort", isOldestFirst() ? "asc" : "desc");
        return params;
    }

    /** Under the title: what isn't the default, e.g. "Issues · Oldest first"; null if none. */
    public static CharSequence summary(Context context) {
        List<String> parts = new ArrayList<>();
        switch (filter()) {
            case FILTER_ISSUES: parts.add(context.getString(R.string.activity_type_issues)); break;
            case FILTER_MERGE_REQUESTS:
                parts.add(context.getString(R.string.activity_type_merge_requests));
                break;
            case FILTER_COMMENTS:
                parts.add(context.getString(R.string.activity_type_comments));
                break;
            case FILTER_PUSHES: parts.add(context.getString(R.string.activity_type_pushes)); break;
            default: break;
        }
        if (isOldestFirst()) parts.add(context.getString(R.string.activity_sort_oldest));
        return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
    }
}
