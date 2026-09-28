package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/** Minimal GraphQL User shape returned inside awardEmoji nodes. */
public class GitLabGraphQLUser {
    @Json(name = "id") public String id; // gid://gitlab/User/<numericId>
    @Json(name = "username") public String username;
    @Json(name = "name") public String name;
    @Json(name = "avatarUrl") public String avatarUrl;

    /** Converts to the REST-shaped GitLabUser used everywhere else in the app. */
    public GitLabUser toGitLabUser() {
        GitLabUser user = GitLabUser.create(username, parseNumericId(id));
        user.name = name;
        user.avatarUrl = avatarUrl;
        return user;
    }

    private static long parseNumericId(String gid) {
        if (gid == null) return 0L;
        int slash = gid.lastIndexOf('/');
        try {
            return Long.parseLong(slash >= 0 ? gid.substring(slash + 1) : gid);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
