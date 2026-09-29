package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/** GraphQL response: the current user's group and project memberships with roles (#172). */
public class GitLabMembershipsResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "currentUser") public CurrentUser currentUser;
    }

    public static class CurrentUser {
        @Json(name = "groupMemberships") public Connection groupMemberships;
        @Json(name = "projectMemberships") public Connection projectMemberships;
    }

    public static class Connection {
        @Json(name = "pageInfo") public GitLabNoteReactionsResponse.PageInfo pageInfo;
        @Json(name = "nodes") public List<Node> nodes;
    }

    public static class Node {
        @Json(name = "group") public Namespace group;
        @Json(name = "project") public Namespace project;
        @Json(name = "accessLevel") public AccessLevel accessLevel;
    }

    public static class Namespace {
        @Json(name = "fullPath") public String fullPath;
    }

    public static class AccessLevel {
        @Json(name = "stringValue") public String stringValue;
    }
}
