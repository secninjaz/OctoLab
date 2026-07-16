package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;
import java.util.List;

public class GitLabSearchResult {
    // Projects
    public List<GitLabProject> projects;
    // Issues
    public List<GitLabIssue> issues;
    // MRs
    public List<GitLabMergeRequest> mergeRequests;
    // Users
    public List<GitLabUser> users;
    // Blobs (code search)
    public List<GitLabBlob> blobs;

    public static class GitLabBlob {
        @Json(name = "basename") public String basename;
        @Json(name = "data") public String data;
        @Json(name = "path") public String path;
        @Json(name = "filename") public String filename;
        @Json(name = "id") public String id;
        @Json(name = "ref") public String ref;
        @Json(name = "startline") public int startline;
        @Json(name = "project_id") public long projectId;

        public String path() { return path != null ? path : filename; }
        public String data() { return data; }
        /** Stub: returns null (GitLab code search does not return a Repository in the blob). */
        public GitLabProject repository() { return null; }
        /** Stub: text matches not available in GitLab code search blob results. */
        public java.util.List<Object> textMatches() { return null; }
    }
}
