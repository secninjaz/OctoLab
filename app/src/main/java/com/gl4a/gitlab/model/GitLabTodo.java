package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabTodo {
    @Json(name = "id") public long id;
    @Json(name = "project") public GitLabProject project;
    @Json(name = "group") public GitLabGroup group;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "action_name") public String actionName;
    @Json(name = "target_type") public String targetType;
    @Json(name = "target") public GitLabIssue target;
    @Json(name = "target_url") public String targetUrl;
    @Json(name = "body") public String body;
    @Json(name = "state") public String state;
    @Json(name = "created_at") public String createdAt;

    public long id() { return id; }
    public String title() {
        if (target != null && target.title != null) return target.title;
        return actionName;
    }
    public GitLabIssue targetIssue() { return target; }
    public String url() { return targetUrl; }
    public String type() { return targetType; }
    public String reason() { return actionName; }
    public boolean isUnread() { return "pending".equals(state); }
    public GitLabProject repository() { return project; }

}
