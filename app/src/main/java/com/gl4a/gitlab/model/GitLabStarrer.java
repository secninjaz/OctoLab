package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/** Wrapper returned by GET /projects/:id/starrers — each element is {starred_since, user}. */
public class GitLabStarrer {
    @Json(name = "starred_since") public String starredSince;
    @Json(name = "user") public GitLabUser user;

    public GitLabUser user() { return user; }
    public String starredSince() { return starredSince; }
}
