package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/**
 * Response of the GraphQL query that loads award emoji for every note of an issue or MR.
 * The issue/mergeRequest root field is aliased to "noteable" so one model covers both.
 */
public class GitLabNoteReactionsResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "noteable") public Noteable noteable;
    }

    public static class Noteable {
        @Json(name = "notes") public Notes notes;
    }

    public static class Notes {
        @Json(name = "pageInfo") public PageInfo pageInfo;
        @Json(name = "nodes") public List<Note> nodes;
    }

    public static class PageInfo {
        @Json(name = "hasNextPage") public boolean hasNextPage;
        @Json(name = "endCursor") public String endCursor;
    }

    public static class Note {
        // Global ID, e.g. gid://gitlab/Note/123, gid://gitlab/DiffNote/123
        @Json(name = "id") public String id;
        @Json(name = "awardEmoji") public AwardEmojiConnection awardEmoji;
    }

    public static class AwardEmojiConnection {
        @Json(name = "nodes") public List<AwardEmoji> nodes;
    }

    public static class AwardEmoji {
        @Json(name = "name") public String name;
        @Json(name = "user") public User user;
    }

    public static class User {
        @Json(name = "username") public String username;
    }
}
