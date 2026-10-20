package com.gl4a.utils;

import androidx.annotation.DrawableRes;

import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabEvent;

/**
 * THE type icons on the feed rows' badges (#288): what a to-do, participating comment or
 * Your Activity event is about, from what GitLab sends with it. 0 when it isn't one of these
 * (e.g. joined a project): no badge.
 */
public final class FeedTypeIcons {
    private FeedTypeIcons() {
    }

    /** A to-do's or participating comment's target_type. */
    @DrawableRes
    public static int forTarget(String targetType) {
        if (targetType == null) return 0;
        return switch (targetType) {
            // With a margin as the other icons have (#288)
            case "Issue" -> R.drawable.feed_badge_issue;
            case "MergeRequest" -> R.drawable.pull_request;
            case "Commit" -> R.drawable.commit;
            case "Release" -> R.drawable.release;
            case "Discussion" -> R.drawable.discussion;
            default -> 0;
        };
    }

    /** An event: a push, a branch or tag created or deleted, or its target_type. */
    @DrawableRes
    public static int forEvent(GitLabEvent event) {
        if (event.pushData != null) {
            if ("tag".equalsIgnoreCase(event.pushData.refType)) return R.drawable.tag;
            String action = event.pushData.action;
            return "created".equals(action) || "deleted".equals(action)
                    ? R.drawable.branch : R.drawable.commit;
        }
        if (event.targetType == null) return 0;
        return switch (event.targetType) {
            case "Note", "DiffNote", "DiscussionNote" -> R.drawable.comments;
            case "Milestone" -> R.drawable.milestone;
            default -> forTarget(event.targetType);
        };
    }
}
