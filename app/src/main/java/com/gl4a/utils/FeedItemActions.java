package com.gl4a.utils;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.gl4a.BaseActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTodo;
import com.google.android.material.snackbar.Snackbar;

/**
 * THE actions on a to-do's or participating comment's row menu (#289), beside marking it read:
 * unsubscribing from its issue or merge request, and bookmarking what it's about. Both lists
 * use these, so they work the same in both.
 */
public final class FeedItemActions {
    private FeedItemActions() {
    }

    private static boolean isIssue(GitLabTodo todo) {
        return "Issue".equals(todo.targetType);
    }

    private static boolean isMergeRequest(GitLabTodo todo) {
        return "MergeRequest".equals(todo.targetType);
    }

    private static long projectId(GitLabTodo todo) {
        if (todo.target != null && todo.target.projectId > 0) return todo.target.projectId;
        return todo.project != null ? todo.project.id : 0;
    }

    /** Only issues and merge requests can be unsubscribed from on GitLab. */
    public static boolean canUnsubscribe(GitLabTodo todo) {
        return (isIssue(todo) || isMergeRequest(todo)) && todo.target != null
                && todo.target.iid > 0 && projectId(todo) > 0;
    }

    /** "Issue #243" or "MR #12", as the row names it. */
    private static String reference(GitLabTodo todo) {
        return (isMergeRequest(todo) ? "MR" : "Issue") + " #" + todo.target.iid;
    }

    /**
     * Unsubscribes from the item's issue or merge request; the to-do itself stays as it is.
     * GitLab's to-do doesn't say whether you're subscribed, and nothing is asked to find out:
     * when you weren't, GitLab leaves it so (304), and either way you end up unsubscribed, so
     * it always says so, with Undo to subscribe again.
     */
    public static void unsubscribe(View anchor, GitLabTodo todo) {
        if (!canUnsubscribe(todo)) return;
        Context context = anchor.getContext();
        String reference = reference(todo);
        long projectId = projectId(todo);
        long iid = todo.target.iid;
        boolean mergeRequest = isMergeRequest(todo);
        Subscriptions.change(projectId, iid, mergeRequest, false)
                .compose(RxUtils::doInBackground)
                .subscribe(done -> {
                    View root = rootView(context, anchor);
                    if (root == null) return;
                    Snackbar.make(root, context.getString(R.string.unsubscribe_success,
                            reference), Snackbar.LENGTH_LONG)
                            .setAction(R.string.undo, v -> resubscribe(context, projectId, iid,
                                    mergeRequest, reference))
                            .show();
                }, error -> failed(context, R.string.unsubscribe_failed, reference, error));
    }

    private static void resubscribe(Context context, long projectId, long iid,
            boolean mergeRequest, String reference) {
        Subscriptions.change(projectId, iid, mergeRequest, true)
                .compose(RxUtils::doInBackground)
                .subscribe(done -> { },
                        error -> failed(context, R.string.subscribe_failed, reference, error));
    }

    private static void failed(Context context, int messageResId, String reference,
            Throwable error) {
        Log.d(Gl4Application.LOG_TAG, "Changing the subscription to " + reference
                + " failed", error);
        if (context instanceof BaseActivity && ((BaseActivity) context).isFinishing()) return;
        Toast.makeText(context, context.getString(messageResId, reference),
                Toast.LENGTH_LONG).show();
    }

    @Nullable
    private static View rootView(Context context, View anchor) {
        if (context instanceof BaseActivity) {
            BaseActivity activity = (BaseActivity) context;
            return activity.isFinishing() ? null : activity.getRootLayout();
        }
        return anchor.isAttachedToWindow() ? anchor : null;
    }

    /**
     * What the item is about as a bookmark: the same as its issue, merge request or commit
     * screen saves (one bookmark, shown as bookmarked in both places); null for anything else.
     */
    @Nullable
    public static BookmarkActions.Target bookmarkTarget(GitLabTodo todo) {
        GitLabProject project = todo.project;
        String path = project != null && project.pathWithNamespace != null
                ? project.pathWithNamespace : "";
        if (todo.isCommit()) {
            if (todo.commitSha == null || todo.targetUrl == null) return null;
            return BookmarkActions.Target.commit(stripAnchor(todo.targetUrl), todo.commitTitle,
                    path, todo.commitSha);
        }
        if (todo.target == null || todo.target.iid <= 0) return null;
        String url = todo.target.webUrl != null ? todo.target.webUrl
                : todo.targetUrl != null ? stripAnchor(todo.targetUrl) : null;
        if (url == null) return null;
        if (isIssue(todo)) {
            return BookmarkActions.Target.issue(url, todo.target.title, path, todo.target.iid);
        }
        if (isMergeRequest(todo)) {
            return BookmarkActions.Target.mergeRequest(url, todo.target.title, path,
                    todo.target.iid);
        }
        return null;
    }

    private static String stripAnchor(String url) {
        return url.replaceFirst("#.*$", "");
    }
}
