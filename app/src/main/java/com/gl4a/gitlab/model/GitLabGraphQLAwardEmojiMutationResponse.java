package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/**
 * Response envelope for the awardEmojiAdd/awardEmojiRemove mutations. Both mutations are
 * queried with a "result:" alias so this single shape covers either one.
 */
public class GitLabGraphQLAwardEmojiMutationResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<GitLabGraphQLError> errors;

    public List<String> userErrors() {
        return data != null && data.result != null ? data.result.errors : null;
    }

    public GitLabGraphQLAwardEmoji awardEmoji() {
        return data != null && data.result != null ? data.result.awardEmoji : null;
    }

    public static class Data {
        @Json(name = "result") public Result result;
    }

    public static class Result {
        @Json(name = "errors") public List<String> errors;
        @Json(name = "awardEmoji") public GitLabGraphQLAwardEmoji awardEmoji;
    }
}
