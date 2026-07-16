package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/**
 * Represents an award emoji (reaction) returned by the Award Emoji API.
 * Endpoint examples:
 *   GET /projects/:id/issues/:iid/award_emoji
 *   POST /projects/:id/merge_requests/:iid/award_emoji  { "name": "thumbsup" }
 */
public class GitLabAwardEmoji {
    @Json(name = "id") public long id;
    @Json(name = "name") public String name;           // e.g. "thumbsup", "heart"
    @Json(name = "user") public GitLabUser user;       // the user who awarded it
    @Json(name = "created_at") public String createdAt;
    @Json(name = "updated_at") public String updatedAt;
    @Json(name = "awardable_id") public long awardableId;
    @Json(name = "awardable_type") public String awardableType; // "Issue" | "MergeRequest" | ...

    public long id() { return id; }
    public String name() { return name; }
    public GitLabUser user() { return user; }
    public String createdAt() { return createdAt; }
}
