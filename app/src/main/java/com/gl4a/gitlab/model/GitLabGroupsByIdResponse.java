package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/** GraphQL groups(ids:): the details of groups known only by ID, e.g. memberships (#236). */
public class GitLabGroupsByIdResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "groups") public Connection groups;
    }

    public static class Connection {
        @Json(name = "nodes") public List<Node> nodes;
    }

    public static class Node {
        @Json(name = "id") public String id;
        @Json(name = "fullPath") public String fullPath;
        @Json(name = "name") public String name;
        @Json(name = "avatarUrl") public String avatarUrl;
        @Json(name = "visibility") public String visibility;
        @Json(name = "description") public String description;

        public long numericId() {
            if (id == null) return 0;
            try {
                return Long.parseLong(id.substring(id.lastIndexOf('/') + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public GitLabGroup toGroup() {
            GitLabGroup g = new GitLabGroup();
            g.id = numericId();
            g.name = name;
            g.fullPath = fullPath;
            g.path = fullPath != null ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : null;
            g.avatarUrl = avatarUrl;
            g.visibility = visibility;
            g.description = description;
            return g;
        }
    }
}
