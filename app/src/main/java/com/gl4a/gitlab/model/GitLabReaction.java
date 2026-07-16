package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.Date;

/**
 * Represents an emoji reaction/award on a GitLab resource (issue, MR, note).
 * Maps to GitHub's Reaction concept.
 */
public class GitLabReaction {
    // GitLab uses "award emoji" instead of reactions
    // The API endpoint is /award_emoji

    public static final String CONTENT_PLUS_ONE = "+1";
    public static final String CONTENT_MINUS_ONE = "-1";
    public static final String CONTENT_LAUGH = "laugh";
    public static final String CONTENT_HOORAY = "tada";
    public static final String CONTENT_HEART = "heart";
    public static final String CONTENT_CONFUSED = "confused";
    public static final String CONTENT_ROCKET = "rocket";
    public static final String CONTENT_EYES = "eyes";

    @Json(name = "id") public long id;
    @Json(name = "name") public String name; // emoji name, e.g. "thumbsup"
    @Json(name = "user") public GitLabUser user;
    @Json(name = "created_at") public String createdAt;

    public long id() { return id; }
    public String content() { return name != null ? mapEmojiNameToContent(name) : ""; }
    public GitLabUser user() { return user; }
    public Date createdAt() { return null; } // stub

    private static String mapEmojiNameToContent(String name) {
        switch (name) {
            case "thumbsup": return "+1";
            case "thumbsdown": return "-1";
            case "laughing": return "laugh";
            case "tada": return "hooray";
            case "heart": return "heart";
            case "confused": return "confused";
            case "rocket": return "rocket";
            case "eyes": return "eyes";
            default: return name;
        }
    }
}
