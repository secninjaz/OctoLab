package com.gl4a.gitlab.model;

/**
 * Represents the mergeability state of a GitLab merge request.
 * Maps to GitHub's PullRequest.MergeableState.
 */
public enum GitLabMergeableState {
    /** All checks passed, ready to merge. */
    Clean,
    /** MR is behind the target branch. */
    Behind,
    /** Merging is blocked (e.g. unresolved discussions, required approvals). */
    Blocked,
    /** MR has conflicts. */
    Dirty,
    /** MR is a draft / work in progress. */
    Draft,
    /** Checks in progress. */
    Unstable,
    /** Unknown state. */
    Unknown
}
