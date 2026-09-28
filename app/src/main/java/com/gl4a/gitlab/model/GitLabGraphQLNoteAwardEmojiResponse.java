package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/** Response envelope for the note { awardEmoji { nodes { ... } } } query. */
public class GitLabGraphQLNoteAwardEmojiResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<GitLabGraphQLError> errors;

    public List<GitLabGraphQLAwardEmoji> nodes() {
        if (data == null || data.note == null || data.note.awardEmoji == null) return null;
        return data.note.awardEmoji.nodes;
    }

    public static class Data {
        @Json(name = "note") public Note note;
    }

    public static class Note {
        @Json(name = "awardEmoji") public Connection awardEmoji;
    }

    public static class Connection {
        @Json(name = "nodes") public List<GitLabGraphQLAwardEmoji> nodes;
    }
}
