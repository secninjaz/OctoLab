package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;
import java.util.Map;

/**
 * GraphQL response for several groups' latest project activity in one request, each group
 * under its own alias (g0, g1, ...), for sorting groups by activity (#175).
 */
public class GitLabGroupActivityResponse {
    @Json(name = "data") public Map<String, Group> data;
    @Json(name = "errors") public List<Object> errors;

    public static class Group {
        @Json(name = "projects") public Projects projects;
    }

    public static class Projects {
        @Json(name = "nodes") public List<Project> nodes;
    }

    public static class Project {
        @Json(name = "lastActivityAt") public String lastActivityAt;
    }
}
