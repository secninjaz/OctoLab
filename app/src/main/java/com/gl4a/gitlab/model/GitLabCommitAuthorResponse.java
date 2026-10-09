package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/** GraphQL repository.commit(ref).author: the account GitLab links a commit to (#244). */
public class GitLabCommitAuthorResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "project") public Project project;
    }

    public static class Project {
        @Json(name = "repository") public Repository repository;
    }

    public static class Repository {
        @Json(name = "commit") public Commit commit;
    }

    public static class Commit {
        @Json(name = "author") public Author author;
    }

    public static class Author {
        @Json(name = "username") public String username;
    }

    /** The author's username, or null if GitLab links the commit to no account. */
    public String username() {
        return data != null && data.project != null && data.project.repository != null
                && data.project.repository.commit != null
                && data.project.repository.commit.author != null
                ? data.project.repository.commit.author.username : null;
    }
}
