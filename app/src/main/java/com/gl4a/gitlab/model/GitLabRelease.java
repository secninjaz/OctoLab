package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;
import java.util.List;

public class GitLabRelease {
    @Json(name = "tag_name") public String tagName;
    @Json(name = "name") public String name;
    @Json(name = "description") public String description;
    @Json(name = "description_html") public String descriptionHtml;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "released_at") public String releasedAt;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "commit") public GitLabCommit commit;
    @Json(name = "assets") public Assets assets;
    @Json(name = "upcoming_release") public boolean upcomingRelease;

    public String tagName() { return tagName; }
    public String name() { return name != null ? name : tagName; }
    public String body() { return description; }
    /** Returns release description as HTML. Falls back to markdown render since GitLab rarely populates description_html. */
    public String bodyHtml() {
        if (descriptionHtml != null && !descriptionHtml.isEmpty()) return descriptionHtml;
        return com.gl4a.utils.HtmlUtils.markdownToHtml(description);
    }
    public String publishedAt() { return releasedAt != null ? releasedAt : createdAt; }
    public GitLabUser author() { return author; }
    public boolean isDraft() { return false; }
    public boolean isPrerelease() { return upcomingRelease; }
    /** Returns custom download links. For archive sources (zip/tar.gz), use sourceAssets(). */
    public List<Asset> assets() { return assets != null ? assets.links : null; }
    /** Returns source archive links (zip, tar.gz, etc.) confirmed from API response. */
    public List<Source> sourceAssets() { return assets != null ? assets.sources : null; }

    public static class Assets {
        @Json(name = "count") public int count;
        @Json(name = "sources") public List<Source> sources;
        @Json(name = "links") public List<Asset> links;
    }

    public static class Asset {
        @Json(name = "id") public long id;
        @Json(name = "name") public String name;
        @Json(name = "url") public String url;
        @Json(name = "direct_asset_url") public String directAssetUrl;
        @Json(name = "link_type") public String linkType;

        public Asset() {}
        public String name() { return name; }
        /**
         * Returns the best download URL.
         * Prefers direct_asset_url (GitLab release permalink) over raw upload URL,
         * since upload URLs require browser session auth while package/API URLs
         * work with PRIVATE-TOKEN.
         */
        public String browserDownloadUrl() {
            if (directAssetUrl != null && !directAssetUrl.isEmpty()) return directAssetUrl;
            return url;
        }
        public long size() { return 0; }
    }

    public static class Source {
        @Json(name = "format") public String format;
        @Json(name = "url") public String url;
        public String name() { return format; }
        public String browserDownloadUrl() { return url; }
    }
}
