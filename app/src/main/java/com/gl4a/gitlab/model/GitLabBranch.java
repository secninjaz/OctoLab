package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabBranch {
    @Json(name = "name") public String name;
    @Json(name = "commit") public GitLabCommit commit;
    @Json(name = "protected") public boolean isProtected;
    @Json(name = "merged") public boolean merged;
    @Json(name = "default") public boolean isDefault;
    @Json(name = "developers_can_push") public boolean developersCanPush;
    @Json(name = "developers_can_merge") public boolean developersCanMerge;

    public String name() { return name; }
    public GitLabCommit commit() { return commit; }
    public boolean isProtected() { return isProtected; }
}
