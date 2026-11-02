package com.gl4a.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.collection.LruCache;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.GroupActivity;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.service.GitLabUserService;


import io.reactivex.Single;
import retrofit2.Response;

/**
 * THE answer to "what is this GitLab path?" (#307). A path like {@code a}, {@code a/b} or
 * {@code a/b/c} can be a user, a group, a subgroup or a project; only GitLab knows which. Every
 * screen that opens a path and finds it isn't what it expected (the user screen: no such user;
 * the project screen: no such project, #306) asks here, so all of them land on the right screen
 * the same way. Asks in the order the path suggests (one part: user, then group; more: project,
 * then group), skipping what the caller already tried, and remembers answers per server for the
 * session. Unknown paths answer {@link Kind#NONE}.
 */
public final class PathResolver {
    public enum Kind { USER, GROUP, PROJECT, NONE }

    /** Nothing on GitLab has the path, for this user: shown as "doesn't exist", not a crash. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String path) {
            super("Nothing found at " + path);
        }
    }

    private static final LruCache<String, Kind> sCache = new LruCache<>(200);

    private PathResolver() {
    }

    /**
     * What {@code path} is, not counting {@code alreadyTried} (the kind the caller found
     * missing), as one GitLab answer at a time; {@link Kind#NONE} if nothing has it.
     */
    public static Single<Kind> resolve(String path, @Nullable Kind alreadyTried) {
        String clean = trim(path);
        String key = Gl4Application.get().getInstanceUrl() + "|" + clean;
        Kind cached = sCache.get(key);
        if (cached != null && cached != alreadyTried) return Single.just(cached);

        boolean single = !clean.contains("/");
        Kind[] order = single
                ? new Kind[] { Kind.USER, Kind.GROUP }
                : new Kind[] { Kind.PROJECT, Kind.GROUP };
        Single<Kind> result = Single.just(Kind.NONE);
        for (int i = order.length - 1; i >= 0; i--) {
            Kind kind = order[i];
            if (kind == alreadyTried) continue;
            Single<Kind> next = result;
            result = exists(kind, clean).flatMap(found -> found ? Single.just(kind) : next);
        }
        return result.doOnSuccess(kind -> {
            if (kind != Kind.NONE) sCache.put(key, kind);
        });
    }

    /** The screen for a resolved path; null for {@link Kind#NONE}. */
    @Nullable
    public static Intent intentFor(Context context, Kind kind, String path) {
        String clean = trim(path);
        switch (kind) {
            case USER:
                return UserActivity.makeIntent(context, clean);
            case GROUP:
                return GroupActivity.makeIntent(context, clean);
            case PROJECT: {
                int slash = clean.lastIndexOf('/');
                return RepositoryActivity.makeIntent(context, clean.substring(0, slash),
                        clean.substring(slash + 1));
            }
            default:
                return null;
        }
    }

    /** Whether GitLab has a {@code kind} at {@code path}; a refusal or failure counts as no. */
    private static Single<Boolean> exists(Kind kind, String path) {
        Single<? extends Response<?>> call;
        switch (kind) {
            case USER:
                return ServiceFactory.get(GitLabUserService.class, false)
                        .searchUsers(path, 1, 1)
                        .map(r -> r.isSuccessful() && r.body() != null && !r.body().isEmpty())
                        .onErrorReturnItem(false);
            case GROUP:
                call = ServiceFactory.get(GitLabGroupService.class, false)
                        .getGroupByPath(Uri.encode(path));
                break;
            case PROJECT:
                call = ServiceFactory.get(GitLabProjectService.class, false)
                        .getProjectByPath(Uri.encode(path));
                break;
            default:
                return Single.just(false);
        }
        return call.map(r -> r.isSuccessful() && r.body() != null).onErrorReturnItem(false);
    }

    private static String trim(String path) {
        String p = path == null ? "" : path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
