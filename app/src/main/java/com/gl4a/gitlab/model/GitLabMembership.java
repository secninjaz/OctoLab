package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/** A user's direct membership of a project or group, from GET /users/:id/memberships (#232). */
public class GitLabMembership {
    @Json(name = "source_id") public long sourceId;
    @Json(name = "source_name") public String sourceName;
    @Json(name = "source_type") public String sourceType;
    @Json(name = "access_level") public int accessLevel;
}
