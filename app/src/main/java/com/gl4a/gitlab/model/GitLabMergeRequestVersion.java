package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/** One version (push) of a merge request, from GET /merge_requests/:iid/versions/:id (#184). */
public class GitLabMergeRequestVersion {
    @Json(name = "id") public long id;
    @Json(name = "head_commit_sha") public String headCommitSha;
    @Json(name = "base_commit_sha") public String baseCommitSha;
    @Json(name = "start_commit_sha") public String startCommitSha;
}
