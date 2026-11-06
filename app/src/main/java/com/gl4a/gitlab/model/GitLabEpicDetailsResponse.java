package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.List;

/**
 * Response of the GraphQL query for what REST leaves out of an epic (#312), from its work item's
 * widgets: its labels with their colours, its assignees, its parent, its child counts (as
 * GitLab web shows them, children's children included) and its children's labels, whose
 * colours REST leaves out too. Other widgets come back empty.
 */
public class GitLabEpicDetailsResponse {
    @Json(name = "data") public Data data;
    @Json(name = "errors") public List<Object> errors;

    public static class Data {
        @Json(name = "group") public Group group;
    }

    public static class Group {
        @Json(name = "workItem") public WorkItem workItem;
    }

    public static class WorkItem {
        @Json(name = "widgets") public List<Widget> widgets;
    }

    public static class Widget {
        @Json(name = "labels") public Nodes<Label> labels;
        @Json(name = "assignees") public Nodes<GitLabGraphQLUser> assignees;
        @Json(name = "parent") public Parent parent;
        @Json(name = "rolledUpCountsByType") public List<TypeCount> rolledUpCountsByType;
        /** The direct children, with only their labels asked for. */
        @Json(name = "children") public Nodes<WorkItem> children;
    }

    public static class Nodes<T> {
        @Json(name = "nodes") public List<T> nodes;
    }

    public static class Label {
        @Json(name = "title") public String title;
        @Json(name = "color") public String color;
        @Json(name = "textColor") public String textColor;
        @Json(name = "description") public String description;

        public GitLabLabel toGitLabLabel() {
            GitLabLabel label = new GitLabLabel();
            label.name = title;
            label.color = color;
            label.textColor = textColor;
            label.description = description;
            return label;
        }
    }

    public static class Parent {
        @Json(name = "iid") public String iid;
        @Json(name = "title") public String title;
        @Json(name = "webUrl") public String webUrl;
        @Json(name = "reference") public String reference;
    }

    public static class TypeCount {
        @Json(name = "workItemType") public TypeName workItemType;
        @Json(name = "countsByState") public Counts countsByState;
    }

    public static class TypeName {
        @Json(name = "name") public String name;
    }

    public static class Counts {
        @Json(name = "opened") public int opened;
        @Json(name = "closed") public int closed;
        @Json(name = "all") public int all;
    }
}
