package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabTag {
    @Json(name = "name") public String name;
    @Json(name = "message") public String message;
    @Json(name = "target") public String target;
    @Json(name = "commit") public GitLabCommit commit;
    @Json(name = "release") public TagRelease release;
    @Json(name = "protected") public boolean isProtected;
    @Json(name = "created_at") public String createdAt;

    public String name() { return name; }
    public GitLabCommit commit() { return commit; }
    public String message() { return message; }

    public static class TagRelease {
        @Json(name = "tag_name") public String tagName;
        @Json(name = "description") public String description;
    }
}
