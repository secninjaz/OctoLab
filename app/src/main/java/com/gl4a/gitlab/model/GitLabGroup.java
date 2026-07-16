package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabGroup {
    @Json(name = "id") public long id;
    @Json(name = "name") public String name;
    @Json(name = "path") public String path;
    @Json(name = "description") public String description;
    @Json(name = "visibility") public String visibility;
    @Json(name = "avatar_url") public String avatarUrl;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "full_name") public String fullName;
    @Json(name = "full_path") public String fullPath;
    @Json(name = "parent_id") public Long parentId;
    @Json(name = "members_count") public int membersCount;
    @Json(name = "projects_count") public int projectsCount;

    // GitHub Organization compat
    public long id() { return id; }
    public String login() { return path; }
    public String name() { return name; }
    public String avatarUrl() { return avatarUrl; }
    public String htmlUrl() { return webUrl; }
    public String description() { return description; }
    public String type() { return "Organization"; }
}
