package com.gl4a.utils;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.annotation.Nullable;

import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.widget.LinkSpan;

/**
 * Links in activity feed items, as GitLab web has them (#140): the project, a pushed branch or
 * tag, commits, issues, merge requests and comments. Built from the project's path as GitLab
 * gives it and opened through the app's one link handler (LinkSpan / IntentUtils), so they
 * open as links in comments do. Only the activity screens use this; the event data and the
 * participating code (Participation) are untouched.
 */
public final class EventLinks {
    private EventLinks() {
    }

    @Nullable
    private static String projectUrl(@Nullable String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) return null;
        return com.gl4a.Gl4Application.get().getInstanceUrl() + "/" + projectPath;
    }

    /** What tapping the item opens: its comment, issue, merge request, commit(s) or branch. */
    @Nullable
    public static String mainUrl(GitLabEvent event, @Nullable String projectPath) {
        String project = projectUrl(projectPath);
        if (project == null) return null;
        if (event.pushData != null) {
            GitLabEvent.PushData push = event.pushData;
            if (push.commitCount > 1 && push.commitFrom != null && push.commitTo != null) {
                return project + "/-/compare/" + push.commitFrom + "..." + push.commitTo;
            }
            if (push.commitTo != null) {
                return project + "/-/commit/" + push.commitTo;
            }
            return refUrl(project, push.ref);
        }
        if (event.note != null && event.note.noteableType != null
                && event.note.noteableIid > 0) {
            String target = targetUrl(project, event.note.noteableType, event.note.noteableIid);
            return target != null ? target + "#note_" + event.note.id : project;
        }
        if (event.targetType != null && event.targetIid != null) {
            String target = targetUrl(project, event.targetType, event.targetIid);
            if (target != null) return target;
        }
        return project;
    }

    /** The issue, merge request or snippet an item refers to, without a comment's anchor. */
    @Nullable
    private static String referenceUrl(GitLabEvent event, String project) {
        if (event.note != null && event.note.noteableType != null
                && event.note.noteableIid > 0) {
            return targetUrl(project, event.note.noteableType, event.note.noteableIid);
        }
        if (event.targetType != null && event.targetIid != null) {
            return targetUrl(project, event.targetType, event.targetIid);
        }
        return null;
    }

    @Nullable
    private static String targetUrl(String project, String type, long iid) {
        switch (type) {
            case "Issue": return project + "/-/issues/" + iid;
            case "MergeRequest": return project + "/-/merge_requests/" + iid;
            case "Snippet": return project + "/-/snippets/" + iid;
            default: return null;
        }
    }

    @Nullable
    private static String refUrl(String project, @Nullable String ref) {
        return ref != null && !ref.isEmpty() ? project + "/-/tree/" + ref : project;
    }

    /**
     * Turns the bold parts of an item's title into links: the project, a branch or tag, and
     * "#N", "!N" or "issue #N"-style references, each to what GitLab web links it to.
     */
    public static void linkify(SpannableStringBuilder title, GitLabEvent event,
            @Nullable String projectPath) {
        String project = projectUrl(projectPath);
        if (project == null) return;
        for (StyleSpan span : title.getSpans(0, title.length(), StyleSpan.class)) {
            int start = title.getSpanStart(span), end = title.getSpanEnd(span);
            if (start < 0 || end <= start) continue;
            String text = title.subSequence(start, end).toString();
            String url = null;
            if (text.equals(projectPath)) {
                url = project;
            } else if (event.pushData != null && text.equals(event.pushData.ref)) {
                url = "deleted".equals(event.pushData.action) ? project
                        : refUrl(project, event.pushData.ref);
            } else if (text.startsWith("#") || text.startsWith("!") || text.contains(" #")) {
                // The issue or merge request itself; only tapping the item opens a comment
                url = referenceUrl(event, project);
            }
            if (url != null) {
                title.setSpan(new LinkSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
