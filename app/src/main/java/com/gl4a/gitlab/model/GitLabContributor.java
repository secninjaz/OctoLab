package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/**
 * Represents a contributor entry from GET /projects/:id/repository/contributors.
 * This is distinct from GitLabUser; the contributors endpoint returns git author
 * data (name, email, commit count, line additions/deletions), not user objects.
 */
public class GitLabContributor {
    @Json(name = "name") public String name;
    @Json(name = "email") public String email;
    @Json(name = "commits") public int commits;
    @Json(name = "additions") public int additions;
    @Json(name = "deletions") public int deletions;

    public String name() { return name; }
    public String email() { return email; }
    public int commits() { return commits; }
    public int additions() { return additions; }
    public int deletions() { return deletions; }
}
