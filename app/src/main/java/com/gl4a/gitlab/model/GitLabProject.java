package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;
import com.squareup.moshi.Json;

public class GitLabProject implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "name") public String name;
    @Json(name = "name_with_namespace") public String nameWithNamespace;
    @Json(name = "path") public String path;
    @Json(name = "path_with_namespace") public String pathWithNamespace;
    @Json(name = "description") public String description;
    @Json(name = "visibility") public String visibility;
    @Json(name = "star_count") public int starCount;
    @Json(name = "forks_count") public int forksCount;
    @Json(name = "open_issues_count") public int openIssuesCount;
    @Json(name = "default_branch") public String defaultBranch;
    @Json(name = "http_url_to_repo") public String httpUrlToRepo;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "last_activity_at") public String updatedAt;
    /** Explicit alias kept for code that references lastActivityAt directly. */
    public String lastActivityAt() { return updatedAt; }
    @Json(name = "container_registry_enabled") public boolean containerRegistryEnabled;
    @Json(name = "namespace") public GitLabNamespace namespace;
    @Json(name = "owner") public GitLabUser owner;
    @Json(name = "forked_from_project") public GitLabProject forkedFromProject;
    @Json(name = "archived") public boolean archived;
    @Json(name = "empty_repo") public boolean emptyRepo;
    @Json(name = "permissions") public GitLabProjectPermissions permissions;
    @Json(name = "readme_url") public String readmeUrl;
    @Json(name = "topics") public java.util.List<String> topics;
    @Json(name = "avatar_url") public String avatarUrl;
    @Json(name = "mirror") public boolean mirror;
    @Json(name = "ssh_url_to_repo") public String sshUrl;

    // GitHub SDK compatible methods
    public long id() { return id; }
    public String name() { return name; }
    public String fullName() { return pathWithNamespace; }
    public String description() { return description; }
    public boolean isPrivate() { return "private".equals(visibility); }
    public boolean isFork() { return forkedFromProject != null; }
    public int stargazersCount() { return starCount; }
    public int forksCount() { return forksCount; }
    public int openIssuesCount() { return openIssuesCount; }
    public String defaultBranch() { return defaultBranch != null ? defaultBranch : "main"; }
    public String htmlUrl() { return webUrl; }
    public String cloneUrl() { return httpUrlToRepo; }
    public GitLabUser owner() {
        if (owner != null) return owner;
        if (namespace != null) return namespace.toUser();
        return null;
    }
    /**
     * Returns the human-readable display name using proper casing.
     * Uses nameWithNamespace ("testG / OctoLab") stripped of spaces,
     * falling back to pathWithNamespace ("testg/octolab").
     */
    public String displayName() {
        if (nameWithNamespace != null && !nameWithNamespace.isEmpty()) {
            // GitLab returns "testG / OctoLab" — normalise to "testG/OctoLab"
            return nameWithNamespace.replace(" / ", "/").replace(" /", "/").replace("/ ", "/");
        }
        if (pathWithNamespace != null && !pathWithNamespace.isEmpty()) return pathWithNamespace;
        if (name != null && !name.isEmpty()) return name;
        return path;
    }
    public GitLabProject parent() { return forkedFromProject; }
    public boolean hasIssues() { return true; }
    public boolean hasWiki() { return true; }
    public String language() { return null; }

    public static class GitLabNamespace {
        @Json(name = "id") public long id;
        @Json(name = "name") public String name;
        @Json(name = "path") public String path;
        @Json(name = "kind") public String kind;
        @Json(name = "avatar_url") public String avatarUrl;
        @Json(name = "web_url") public String webUrl;

        public GitLabUser toUser() {
            GitLabUser u = new GitLabUser();
            u.id = id;
            u.username = path;
            u.name = name;
            u.avatarUrl = avatarUrl;
            u.webUrl = webUrl;
            return u;
        }
    }

    public static class GitLabProjectPermissions {
        @Json(name = "project_access") public GitLabAccess projectAccess;
        @Json(name = "group_access") public GitLabAccess groupAccess;

        public boolean canPush() {
            int level = projectAccess != null ? projectAccess.accessLevel :
                    (groupAccess != null ? groupAccess.accessLevel : 0);
            return level >= 30; // Developer+
        }
    }

    public static class GitLabAccess {
        @Json(name = "access_level") public int accessLevel;
    }

    public GitLabProject() {}

    protected GitLabProject(Parcel in) {
        id = in.readLong();
        name = in.readString();
        nameWithNamespace = in.readString();
        path = in.readString();
        pathWithNamespace = in.readString();
        description = in.readString();
        visibility = in.readString();
        starCount = in.readInt();
        forksCount = in.readInt();
        openIssuesCount = in.readInt();
        defaultBranch = in.readString();
        httpUrlToRepo = in.readString();
        webUrl = in.readString();
        createdAt = in.readString();
        updatedAt = in.readString();
        owner = in.readParcelable(GitLabUser.class.getClassLoader());
        archived = in.readByte() != 0;
        emptyRepo = in.readByte() != 0;
        readmeUrl = in.readString();
        avatarUrl = in.readString();
        sshUrl = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeString(nameWithNamespace);
        dest.writeString(path);
        dest.writeString(pathWithNamespace);
        dest.writeString(description);
        dest.writeString(visibility);
        dest.writeInt(starCount);
        dest.writeInt(forksCount);
        dest.writeInt(openIssuesCount);
        dest.writeString(defaultBranch);
        dest.writeString(httpUrlToRepo);
        dest.writeString(webUrl);
        dest.writeString(createdAt);
        dest.writeString(updatedAt);
        dest.writeParcelable(owner, flags);
        dest.writeByte((byte) (archived ? 1 : 0));
        dest.writeByte((byte) (emptyRepo ? 1 : 0));
        dest.writeString(readmeUrl);
        dest.writeString(avatarUrl);
        dest.writeString(sshUrl);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabProject> CREATOR = new Creator<GitLabProject>() {
        @Override
        public GitLabProject createFromParcel(Parcel in) { return new GitLabProject(in); }

        @Override
        public GitLabProject[] newArray(int size) { return new GitLabProject[size]; }
    };
}
