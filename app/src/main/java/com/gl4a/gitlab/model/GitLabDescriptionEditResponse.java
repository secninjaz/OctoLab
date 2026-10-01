package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/**
 * Response of the GraphQL query for who last edited an issue's description and when (#151):
 * the work item's description widget. Other widgets come back as empty objects.
 */
public class GitLabDescriptionEditResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "project") public Project project;
    }

    public static class Project {
        @Json(name = "workItems") public WorkItems workItems;
    }

    public static class WorkItems {
        @Json(name = "nodes") public List<WorkItem> nodes;
    }

    public static class WorkItem {
        @Json(name = "widgets") public List<Widget> widgets;
    }

    public static class Widget {
        @Json(name = "lastEditedAt") public String lastEditedAt;
        @Json(name = "lastEditedBy") public GitLabNoteReactionsResponse.User lastEditedBy;
    }
}
