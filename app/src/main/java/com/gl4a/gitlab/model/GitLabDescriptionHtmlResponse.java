package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/**
 * Response of the GraphQL query for an issue's or MR's stored description rendering, with the
 * issue/mergeRequest field aliased to "item" so one model covers both (#199).
 */
public class GitLabDescriptionHtmlResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "project") public Project project;
    }

    public static class Project {
        @Json(name = "item") public Item item;
    }

    public static class Item {
        @Json(name = "descriptionHtml") public String descriptionHtml;
    }
}
