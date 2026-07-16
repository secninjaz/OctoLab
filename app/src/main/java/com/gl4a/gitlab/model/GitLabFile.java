package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabFile {
    @Json(name = "file_name") public String fileName;
    @Json(name = "file_path") public String filePath;
    @Json(name = "size") public long size;
    @Json(name = "encoding") public String encoding;
    @Json(name = "content") public String content;
    @Json(name = "content_sha256") public String contentSha256;
    @Json(name = "ref") public String ref;
    @Json(name = "blob_id") public String blobId;
    @Json(name = "commit_id") public String commitId;
    @Json(name = "last_commit_id") public String lastCommitId;
    @Json(name = "execute_filemode") public boolean executeFilemode;

    // GitHub SDK Content compat
    public String name() { return fileName; }
    public String path() { return filePath; }
    public String sha() { return blobId; }
    public String encoding() { return encoding; }
    public String content() { return content; }
    public Long size() { return size; }
    public String type() { return "file"; }
}
