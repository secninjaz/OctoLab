package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabTodo {
    @Json(name = "id") public long id;
    @Json(name = "project") public GitLabProject project;
    @Json(name = "group") public GitLabGroup group;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "action_name") public String actionName;
    @Json(name = "target_type") public String targetType;
    // Issue or MR. Null for commit to-dos, whose target is a commit (string SHA id); those
    // are split out by ServiceFactory's GitLabTodo adapter into commitSha/commitTitle (#166).
    @Json(name = "target") public GitLabIssue target;
    @Json(name = "target_url") public String targetUrl;
    @Json(name = "body") public String body;
    @Json(name = "state") public String state;
    @Json(name = "created_at") public String createdAt;
    public transient String commitSha;
    public transient String commitTitle;

    public static final String TYPE_COMMIT = "Commit";

    public long id() { return id; }
    public String title() {
        if (target != null && target.title != null) return target.title;
        if (commitTitle != null) return commitTitle;
        // To-dos without a target, e.g. GitLab's own "duo_core_access_granted": GitLab's text
        // for it, else the action in words, never the raw action name (#212)
        if (body != null && !body.trim().isEmpty()) return body.trim();
        return actionName != null ? humanize(actionName) : "";
    }

    /** "duo_core_access_granted" → "Duo core access granted". */
    public static String humanize(String actionName) {
        String words = actionName.replace('_', ' ').trim();
        return words.isEmpty() ? words
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
    public GitLabIssue targetIssue() { return target; }
    public String url() { return targetUrl; }
    public String type() { return targetType; }
    public String reason() { return actionName; }
    public boolean isCommit() { return TYPE_COMMIT.equals(targetType); }
    public boolean isUnread() { return "pending".equals(state); }
    public GitLabProject repository() { return project; }

}
