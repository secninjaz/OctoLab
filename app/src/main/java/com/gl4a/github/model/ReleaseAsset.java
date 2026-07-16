package com.gl4a.github.model;
import com.gl4a.gitlab.model.GitLabRelease;
import java.util.Date;
/** Stub for GitHub SDK ReleaseAsset. Use GitLabRelease.Asset instead. */
public class ReleaseAsset {
    public String name() { return ""; }
    public String label() { return ""; }
    public String url() { return ""; }
    public String contentType() { return ""; }
    public long size() { return 0; }
    public int downloadCount() { return 0; }
    public Date createdAt() { return new Date(); }
}
