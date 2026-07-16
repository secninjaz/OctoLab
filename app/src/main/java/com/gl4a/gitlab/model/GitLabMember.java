package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabMember {
    @Json(name = "id") public long id;
    @Json(name = "username") public String username;
    @Json(name = "name") public String name;
    @Json(name = "avatar_url") public String avatarUrl;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "access_level") public int accessLevel;
    @Json(name = "expires_at") public String expiresAt;
    @Json(name = "state") public String state;

    public GitLabUser toUser() {
        GitLabUser u = new GitLabUser();
        u.id = id;
        u.username = username;
        u.name = name;
        u.avatarUrl = avatarUrl;
        u.webUrl = webUrl;
        return u;
    }

    public long id() { return id; }
    public String login() { return username; }
    public String name() { return name; }
    public String avatarUrl() { return avatarUrl; }
}
