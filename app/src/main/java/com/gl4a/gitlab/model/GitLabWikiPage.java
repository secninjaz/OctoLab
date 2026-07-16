package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/**
 * Represents a wiki page returned by GET /projects/:id/wikis or GET /projects/:id/wikis/:slug.
 * When retrieved from the list endpoint without with_content=true, the content field is null.
 */
public class GitLabWikiPage {
    @Json(name = "slug") public String slug;
    @Json(name = "title") public String title;
    @Json(name = "content") public String content;
    @Json(name = "format") public String format;   // "markdown" | "rdoc" | "asciidoc" | "org"
    @Json(name = "encoding") public String encoding;

    public String slug() { return slug; }
    public String title() { return title; }
    public String content() { return content; }
    public String format() { return format; }
}
