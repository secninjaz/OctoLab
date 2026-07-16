package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;
import java.util.List;
import java.util.Map;

public class GitLabSnippet {
    @Json(name = "id") public long id;
    @Json(name = "title") public String title;
    @Json(name = "description") public String description;
    @Json(name = "visibility") public String visibility;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "updated_at") public String updatedAt;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "raw_url") public String rawUrl;
    @Json(name = "file_name") public String fileName;
    @Json(name = "files") public List<SnippetFile> files;

    // GitHub Gist compat
    public long id() { return id; }
    public String description() { return title; }
    public GitLabUser owner() { return author; }
    public String createdAt() { return createdAt; }
    public String updatedAt() { return updatedAt; }
    public String htmlUrl() { return webUrl; }
    public boolean isPublic() { return "public".equals(visibility); }

    public Map<String, SnippetFile> files() {
        if (files == null) return null;
        Map<String, SnippetFile> map = new java.util.LinkedHashMap<>();
        for (SnippetFile f : files) map.put(f.path != null ? f.path : fileName, f);
        return map;
    }

    public static class SnippetFile {
        @Json(name = "path") public String path;
        @Json(name = "raw_url") public String rawUrl;

        public String filename() { return path; }
        public String rawUrl() { return rawUrl; }
        public String content() { return null; }
        public String type() { return "text/plain"; }
        public int size() { return 0; }
    }
}
