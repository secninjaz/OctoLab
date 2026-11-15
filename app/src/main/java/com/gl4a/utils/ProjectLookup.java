package com.gl4a.utils;

import android.net.Uri;
import android.os.SystemClock;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabProjectService;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * THE way to look up a project by its path (#333): each screen and check asked GitLab again,
 * up to five times in one second, which with the rest used up gitlab.com's limit on requests.
 * The answer is kept for a minute per account, and lookups at the same time share one request.
 * A forced refresh (pull to refresh, the project screen) still asks GitLab, and keeps its answer
 * for the others.
 */
public final class ProjectLookup {
    private static final long KEEP_MS = 60_000;

    private static final class Entry {
        final long at;
        final Single<Response<GitLabProject>> answer;

        Entry(long at, Single<Response<GitLabProject>> answer) {
            this.at = at;
            this.answer = answer;
        }
    }

    private static final Map<String, Entry> sEntries = new HashMap<>();

    private ProjectLookup() {
    }

    /**
     * GitLab's project at {@code encodedPath} ("group%2Fproject"), as
     * {@link GitLabProjectService#getProjectByPath} answers it.
     */
    public static synchronized Single<Response<GitLabProject>> byPath(String encodedPath,
            boolean bypassCache) {
        Gl4Application app = Gl4Application.get();
        String key = app.getInstanceUrl() + "|" + app.getAuthLogin() + "|"
                + Uri.decode(encodedPath).toLowerCase(Locale.ROOT);
        long now = SystemClock.elapsedRealtime();
        Entry entry = sEntries.get(key);
        if (!bypassCache && entry != null && now - entry.at < KEEP_MS) {
            return entry.answer;
        }
        Entry[] mine = new Entry[1];
        Single<Response<GitLabProject>> answer = ServiceFactory
                .get(GitLabProjectService.class, bypassCache)
                .getProjectByPath(encodedPath)
                // Only a project is kept: a refusal or failure is asked again next time
                .doOnSuccess(response -> {
                    if (!response.isSuccessful() || response.body() == null) forget(key, mine[0]);
                })
                .doOnError(error -> forget(key, mine[0]))
                .cache();
        mine[0] = new Entry(now, answer);
        sEntries.put(key, mine[0]);
        return answer;
    }

    private static synchronized void forget(String key, Entry entry) {
        if (sEntries.get(key) == entry) sEntries.remove(key);
    }
}
