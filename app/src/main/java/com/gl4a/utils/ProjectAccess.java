package com.gl4a.utils;

import android.content.Context;

import androidx.annotation.Nullable;

import com.gl4a.ApiRequestException;
import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabProject;

/**
 * THE rules for what a user may see of a project (#304), so the app shows what GitLab web shows
 * for their role, never an error for what the role doesn't allow. From what GitLab sends with
 * the project (the user's role, each feature's setting); no extra requests. Without a role in
 * the data (e.g. an admin who isn't a member), everything is shown, as before: what GitLab then
 * refuses is handled by {@link #isRefused}.
 */
public final class ProjectAccess {
    private static final int REPORTER = 20;

    private ProjectAccess() {
    }

    /** The user's role (10 Guest … 50 Owner), or -1 if GitLab didn't say. */
    private static int role(GitLabProject project) {
        if (project == null || project.permissions == null) return -1;
        int level = project.permissions.accessLevel();
        return level > 0 ? level : -1;
    }

    /**
     * A feature as GitLab web shows it: off when the project turned it off; for members below
     * Reporter, only on a non-private project where it's open to everyone (a Guest on a
     * private project sees no code or merge requests).
     */
    private static boolean allows(GitLabProject project, @Nullable String featureLevel,
            boolean guestsMayUse) {
        if (project == null) return true;
        if ("disabled".equals(featureLevel)) return false;
        int role = role(project);
        if (role < 0 || role >= REPORTER || guestsMayUse) return true;
        return !project.isPrivate() && !"private".equals(featureLevel);
    }

    /** Files, commits, branches, README, code search, download. */
    public static boolean canReadCode(GitLabProject project) {
        return allows(project, project != null ? project.repositoryAccessLevel : null, false);
    }

    /** Merge requests. */
    public static boolean canSeeMergeRequests(GitLabProject project) {
        return canReadCode(project) && allows(project,
                project != null ? project.mergeRequestsAccessLevel : null, false);
    }

    /** Work items: any member may see them, unless the project turned them off. */
    public static boolean canSeeIssues(GitLabProject project) {
        return allows(project, project != null ? project.issuesAccessLevel : null, true);
    }

    /** The wiki: as work items. */
    public static boolean canSeeWiki(GitLabProject project) {
        return allows(project, project != null ? project.wikiAccessLevel : null, true);
    }

    /** GitLab refused it for this user (403), or says there's nothing there for them (404). */
    public static boolean isRefused(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PathResolver.NotFoundException) return true;
            if (t instanceof ApiRequestException) {
                int status = ((ApiRequestException) t).getStatus();
                return status == 403 || status == 404;
            }
        }
        return false;
    }

    /** What to say when GitLab refused something opened directly, e.g. from a link. */
    public static String refusedMessage(Context context, Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PathResolver.NotFoundException || (t instanceof ApiRequestException
                    && ((ApiRequestException) t).getStatus() == 404)) {
                return context.getString(R.string.access_not_found_or_refused);
            }
        }
        return context.getString(R.string.access_refused);
    }
}
