package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.List;

import retrofit2.Response;

/**
 * THE pending to-do count of each account (#290), for the drawer: the active account's from its
 * To-dos, the others' from the background check, each from the answer already asked for (no
 * extra requests). -1 when not known.
 */
public final class TodoCounts {
    private static final String PREFS = "todo_counts";

    private TodoCounts() {
    }

    /** GitLab's total from a to-do list's answer; -1 when it doesn't say and there are some. */
    public static int fromResponse(Response<?> response, @Nullable List<?> page) {
        String total = response.headers().get("X-Total");
        try {
            if (total != null) return Integer.parseInt(total);
        } catch (NumberFormatException ignored) {
        }
        return page == null || page.isEmpty() ? 0 : -1;
    }

    public static int get(Context context, @Nullable String login) {
        if (login == null) return -1;
        return prefs(context).getInt(key(login), -1);
    }

    public static void set(Context context, @Nullable String login, int count) {
        if (login == null) return;
        prefs(context).edit().putInt(key(login), count).apply();
    }

    private static String key(String login) {
        return login.toLowerCase(java.util.Locale.ROOT);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
