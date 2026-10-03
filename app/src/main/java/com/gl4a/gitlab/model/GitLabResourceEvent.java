package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/**
 * A label, milestone or state change from GitLab's resource events APIs
 * (resource_label_events, resource_milestone_events, resource_state_events). These aren't
 * notes, so the notes/discussions APIs don't return them (#180).
 */
public class GitLabResourceEvent {
    @Json(name = "id") public long id;
    @Json(name = "user") public GitLabUser user;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "action") public String action;            // label/milestone: "add" | "remove"
    @Json(name = "label") public GitLabLabel label;
    @Json(name = "milestone") public Milestone milestone;
    @Json(name = "state") public String state;              // state events: closed/reopened/merged/…
    @Json(name = "source_commit") public String sourceCommit;
    // The MR that closed the issue (a global ID, not an iid), #200
    @Json(name = "source_merge_request_id") public Long sourceMergeRequestId;

    public static class Milestone {
        @Json(name = "title") public String title;
    }
}
