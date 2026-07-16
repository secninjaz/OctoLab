package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabEvent {
    @Json(name = "id") public long id;
    @Json(name = "project_id") public long projectId;
    @Json(name = "action_name") public String actionName;
    @Json(name = "target_id") public Long targetId;
    @Json(name = "target_iid") public Long targetIid;
    @Json(name = "target_type") public String targetType;
    @Json(name = "author_id") public long authorId;
    @Json(name = "target_title") public String targetTitle;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "push_data") public PushData pushData;
    @Json(name = "note") public GitLabComment note;
    @Json(name = "author_username") public String authorUsername;

    public long id() { return id; }
    public GitLabUser actor() { return author; }
    public String type() { return actionName; }
    public String createdAt() { return createdAt; }

    /**
     * Maps GitLab action_name values to human-readable descriptions.
     * Known action_name values: pushed to, pushed new, deleted, created, destroyed,
     * commented on, merged, accepted, imported, closed, reopened.
     */
    public String getActionDescription() {
        if (actionName == null) return "";
        switch (actionName) {
            case "pushed to":      return "pushed to";
            case "pushed new":     return "pushed new branch";
            case "deleted":        return "deleted";
            case "created":        return "created";
            case "destroyed":      return "destroyed";
            case "commented on":   return "commented on";
            case "merged":         return "merged";
            case "accepted":       return "accepted";
            case "imported":       return "imported";
            case "closed":         return "closed";
            case "reopened":       return "reopened";
            default:               return actionName;
        }
    }

    public static class PushData {
        @Json(name = "commit_count") public int commitCount;
        @Json(name = "action") public String action;
        @Json(name = "ref_type") public String refType;
        @Json(name = "commit_from") public String commitFrom;
        @Json(name = "commit_to") public String commitTo;
        @Json(name = "ref") public String ref;
        @Json(name = "commit_title") public String commitTitle;
        @Json(name = "ref_count") public int refCount;
    }
}
