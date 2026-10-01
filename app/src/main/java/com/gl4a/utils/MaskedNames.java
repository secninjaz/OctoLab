package com.gl4a.utils;

import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabUserService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import retrofit2.Response;

/**
 * GitLab's discussions, resource events and events APIs return "****" as the name of some
 * users, such as project access token bots, while its users, notes and GraphQL APIs return the
 * real name (#193). This puts the real name back, looked up once per user, or the username if
 * that fails.
 */
public final class MaskedNames {
    // By instance and user ID: the same ID is a different user on another instance
    private static final Map<String, String> sNames = new ConcurrentHashMap<>();

    private MaskedNames() {}

    public static boolean isMasked(String name) {
        return name != null && name.matches("\\*+");
    }

    /** Fixes masked author names of {@code comments} in place. Blocking: call off the main thread. */
    public static List<GitLabComment> unmask(List<GitLabComment> comments) {
        String instance = Gl4Application.get().getInstanceUrl();
        Map<Long, String> names = new HashMap<>();
        for (GitLabComment comment : comments) {
            unmask(instance, comment.user(), names);
            unmask(instance, comment.resolvedBy, names);
        }
        return comments;
    }

    /** Like {@link #unmask(List)}, for the authors of activity events, which are masked too. */
    public static void unmaskUsers(Iterable<GitLabUser> users) {
        String instance = Gl4Application.get().getInstanceUrl();
        Map<Long, String> names = new HashMap<>();
        for (GitLabUser user : users) {
            unmask(instance, user, names);
        }
    }

    private static void unmask(String instance, GitLabUser user, Map<Long, String> names) {
        if (user == null || !isMasked(user.name)) return;
        String name = names.get(user.id);
        if (name == null) {
            name = lookUp(instance, user);
            names.put(user.id, name);
        }
        user.name = name;
    }

    private static String lookUp(String instance, GitLabUser user) {
        String key = instance + "#" + user.id;
        String cached = sNames.get(key);
        if (cached != null) return cached;
        String name = null;
        try {
            Response<GitLabUser> response = ServiceFactory.get(GitLabUserService.class, false)
                    .getUser(user.id).blockingGet();
            GitLabUser full = response.body();
            if (response.isSuccessful() && full != null && !StringUtils.isBlank(full.name)
                    && !isMasked(full.name)) {
                name = full.name;
            }
        } catch (RuntimeException e) {
            Log.d(Gl4Application.LOG_TAG, "Couldn't look up the name of user " + user.id, e);
        }
        if (name == null) {
            // Not cached, so it's tried again next time
            return user.username;
        }
        sNames.put(key, name);
        return name;
    }
}
