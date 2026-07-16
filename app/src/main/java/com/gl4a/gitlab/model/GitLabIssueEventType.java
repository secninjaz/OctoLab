package com.gl4a.gitlab.model;

/**
 * GitLab note/event action types — replaces GitHub's IssueEventType enum.
 * GitLab uses string action names in its events API.
 */
public final class GitLabIssueEventType {
    public static final String Closed = "closed";
    public static final String Reopened = "reopened";
    public static final String Merged = "merged";
    public static final String Referenced = "referenced";
    public static final String Assigned = "assigned";
    public static final String Unassigned = "unassigned";
    public static final String Labeled = "labeled";
    public static final String Unlabeled = "unlabeled";
    public static final String Locked = "locked";
    public static final String Unlocked = "unlocked";
    public static final String Milestoned = "milestone_updated";
    public static final String Demilestoned = "demilestoned";
    public static final String Renamed = "title_changed";
    public static final String HeadRefDeleted = "head_ref_deleted";
    public static final String HeadRefRestored = "head_ref_restored";
    public static final String HeadRefForcePushed = "head_ref_force_pushed";
    public static final String AutoMergeDisabled = "auto_merge_disabled";
    public static final String AutoMergeEnabled = "auto_merge_enabled";
    public static final String AutoRebaseEnabled = "auto_rebase_enabled";
    public static final String AutoSquashEnabled = "auto_squash_enabled";
    public static final String AddedToMergeQueue = "added_to_merge_queue";
    public static final String RemovedFromMergeQueue = "removed_from_merge_queue";
    public static final String CommentDeleted = "comment_deleted";
    public static final String ReviewRequested = "review_requested";
    public static final String ReviewRequestRemoved = "review_request_removed";
    public static final String ConvertToDraft = "convert_to_draft";
    public static final String ReadyForReview = "ready_for_review";
    public static final String ReviewDismissed = "review_dismissed";
    public static final String CrossReferenced = "cross-referenced";
    public static final String Transferred = "transferred";
    public static final String Commented = "commented";

    private GitLabIssueEventType() {}
}
