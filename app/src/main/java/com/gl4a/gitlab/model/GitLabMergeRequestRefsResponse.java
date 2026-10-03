package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;
import java.util.Map;

/**
 * Response of a GraphQL query looking up merge requests by global ID, each under its own
 * alias (m0, m1, ...), for "closed with merge request !N" events (#200). Missing or
 * inaccessible merge requests are null.
 */
public class GitLabMergeRequestRefsResponse {
    @Json(name = "data") public Map<String, MergeRequest> data;
    @Json(name = "errors") public List<Object> errors;

    public static class MergeRequest {
        @Json(name = "iid") public String iid;
        @Json(name = "state") public String state;
        @Json(name = "webUrl") public String webUrl;
        @Json(name = "project") public Project project;
    }

    public static class Project {
        @Json(name = "fullPath") public String fullPath;
    }
}
