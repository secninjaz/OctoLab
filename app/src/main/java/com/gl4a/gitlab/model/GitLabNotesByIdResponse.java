package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;
import java.util.Map;

/**
 * Response of a GraphQL query that looks up several notes by global ID in one request, each
 * under its own alias (n0, n1, ...). Used where there is no parent noteable to page through,
 * e.g. commit comments. Missing notes are returned as null.
 */
public class GitLabNotesByIdResponse {
    @Json(name = "data") public Map<String, GitLabNoteReactionsResponse.Note> data;
    @Json(name = "errors") public List<Object> errors;
}
