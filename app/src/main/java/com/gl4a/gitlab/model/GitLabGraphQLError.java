package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/** Top-level "errors" entry in a GraphQL response envelope. */
public class GitLabGraphQLError {
    @Json(name = "message") public String message;
}
