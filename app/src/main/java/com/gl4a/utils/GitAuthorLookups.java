package com.gl4a.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.service.GitLabUserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds the GitLab accounts of git authors shown without one, e.g. Contributors (#322): one of
 * each author's commits, one at a time, then the accounts GitLab links those commits to, a
 * batch in one GraphQL question, as the Commits tab does ({@link CommitAuthors}). Only rows
 * still on screen; each answer kept in {@link GitAuthorLinks}. When GitLab says there were too
 * many requests, it waits as long as GitLab asks, then carries on with the rows still shown.
 */
public final class GitAuthorLookups {
    /** Who asked: told when an account is found, and asked whether it still wants it. */
    public interface Listener {
        /** Whether the author is still shown; lookups for rows scrolled away are skipped. */
        boolean isStillShown(String email);

        void onFound(String email, GitLabUser user);
    }

    private static final int BATCH = 10;

    private static final class Pending {
        final String email;
        final long projectId;
        final Listener listener;

        Pending(String email, long projectId, Listener listener) {
            this.email = email;
            this.projectId = projectId;
            this.listener = listener;
        }
    }

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    // Waiting, in the order asked, by account and email
    private static final LinkedHashMap<String, Pending> sPending = new LinkedHashMap<>();
    // GitLab links no account to these this session: not asked again until the app restarts
    private static final Set<String> sNoAccount =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Long, String> sProjectPaths = new ConcurrentHashMap<>();
    private static boolean sRunning;
    private static long sPausedUntil;

    private GitAuthorLookups() {
    }

    private static String key(String email) {
        return Gl4Application.get().getAuthLogin() + "|" + email.toLowerCase(Locale.ROOT).trim();
    }

    /** Looks up {@code email}'s account in {@code projectId}, unless known or being asked. */
    public static void request(String email, long projectId, Listener listener) {
        if (email == null || projectId <= 0 || GitAuthorLinks.get(email) != null) return;
        String key = key(email);
        if (sNoAccount.contains(key)) return;
        synchronized (sPending) {
            sPending.put(key, new Pending(email, projectId, listener));
        }
        schedule();
    }

    private static void schedule() {
        synchronized (sPending) {
            if (sRunning || sPending.isEmpty()) return;
            long wait = sPausedUntil - System.currentTimeMillis();
            if (wait > 0) {
                sMain.postDelayed(GitAuthorLookups::schedule, wait);
                return;
            }
            sRunning = true;
        }
        sExecutor.execute(GitAuthorLookups::work);
    }

    /** Up to a batch of authors still shown, of one project, taken off the waiting list. */
    private static List<Pending> takeBatch() {
        List<Pending> batch = new ArrayList<>();
        synchronized (sPending) {
            Iterator<Map.Entry<String, Pending>> it = sPending.entrySet().iterator();
            while (it.hasNext() && batch.size() < BATCH) {
                Pending p = it.next().getValue();
                if (!p.listener.isStillShown(p.email)) {
                    it.remove();
                    continue;
                }
                if (!batch.isEmpty() && batch.get(0).projectId != p.projectId) continue;
                it.remove();
                batch.add(p);
            }
        }
        return batch;
    }

    private static void work() {
        GitLabUserService users = ServiceFactory.get(GitLabUserService.class, false);
        while (true) {
            List<Pending> batch = takeBatch();
            if (batch.isEmpty()) break;
            try {
                String path = projectPath(batch.get(0).projectId);
                if (path == null) continue;
                // One of each author's commits, one request at a time
                List<GitLabCommit> commits = new ArrayList<>();
                List<Pending> asked = new ArrayList<>();
                for (Pending p : batch) {
                    List<GitLabCommit> found = AvatarHandler.bodyOf(users
                            .getCommitsByAuthor(android.net.Uri.encode(path), p.email, 1)
                            .blockingGet());
                    if (found == null || found.isEmpty() || found.get(0).id == null) {
                        sNoAccount.add(key(p.email));
                        continue;
                    }
                    GitLabCommit commit = new GitLabCommit();
                    commit.id = found.get(0).id;
                    commit.authorEmail = p.email;
                    commits.add(commit);
                    asked.add(p);
                }
                // The accounts GitLab links them to, in one question
                CommitAuthors.link(path, commits);
                for (int i = 0; i < commits.size(); i++) {
                    Pending p = asked.get(i);
                    GitLabUser user = commits.get(i).authorUser;
                    if (user != null && user.login() != null) {
                        sMain.post(() -> p.listener.onFound(p.email, user));
                    } else {
                        sNoAccount.add(key(p.email));
                    }
                }
            } catch (AvatarHandler.RateLimitedException e) {
                // Back on the list, asked again once GitLab's wait is over
                synchronized (sPending) {
                    for (Pending p : batch) sPending.put(key(p.email), p);
                    sPausedUntil = System.currentTimeMillis() + e.retryAfterMs;
                }
                Log.d(Gl4Application.LOG_TAG, "Git author lookups wait "
                        + e.retryAfterMs / 1000 + " s: rate limited");
                break;
            } catch (Exception e) {
                Log.d(Gl4Application.LOG_TAG, "Git author lookups failed", e);
            }
        }
        synchronized (sPending) {
            sRunning = false;
        }
        sMain.post(GitAuthorLookups::schedule);
    }

    private static String projectPath(long projectId) {
        String path = sProjectPaths.get(projectId);
        if (path != null) return path;
        GitLabProject project = AvatarHandler.bodyOf(ServiceFactory
                .get(GitLabProjectService.class, false).getProject(projectId).blockingGet());
        if (project == null || project.pathWithNamespace == null) return null;
        sProjectPaths.put(projectId, project.pathWithNamespace);
        return project.pathWithNamespace;
    }
}
