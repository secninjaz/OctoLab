package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/**
 * GraphQL projects(ids:) with one user's role in each (#233): the project details for rows built
 * from IDs alone (memberships), and the user's access level, direct or inherited.
 */
public class GitLabProjectsByIdResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "projects") public Connection projects;
    }

    public static class Connection {
        @Json(name = "nodes") public List<Node> nodes;
    }

    public static class Node {
        @Json(name = "id") public String id;
        @Json(name = "fullPath") public String fullPath;
        @Json(name = "name") public String name;
        @Json(name = "nameWithNamespace") public String nameWithNamespace;
        @Json(name = "description") public String description;
        @Json(name = "visibility") public String visibility;
        @Json(name = "starCount") public int starCount;
        @Json(name = "forksCount") public int forksCount;
        @Json(name = "avatarUrl") public String avatarUrl;
        @Json(name = "archived") public boolean archived;
        @Json(name = "projectMembers") public Members projectMembers;

        /** The numeric ID from "gid://gitlab/Project/123", or 0. */
        public long numericId() {
            if (id == null) return 0;
            try {
                return Long.parseLong(id.substring(id.lastIndexOf('/') + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public GitLabProject toProject() {
            GitLabProject p = new GitLabProject();
            p.id = numericId();
            p.name = name;
            p.nameWithNamespace = nameWithNamespace;
            p.pathWithNamespace = fullPath;
            p.path = fullPath != null ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : null;
            p.description = description;
            p.visibility = visibility;
            p.starCount = starCount;
            p.forksCount = forksCount;
            p.avatarUrl = avatarUrl;
            p.archived = archived;
            return p;
        }
    }

    public static class Members {
        @Json(name = "nodes") public List<Member> nodes;
    }

    public static class Member {
        @Json(name = "accessLevel") public AccessLevel accessLevel;
        @Json(name = "user") public User user;
    }

    public static class AccessLevel {
        @Json(name = "integerValue") public int integerValue;
        @Json(name = "stringValue") public String stringValue;
    }

    public static class User {
        @Json(name = "username") public String username;
    }
}
