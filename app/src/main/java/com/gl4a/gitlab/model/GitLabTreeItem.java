package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.squareup.moshi.Json;

public class GitLabTreeItem implements Parcelable {
    @Json(name = "id") public String id;
    @Json(name = "name") public String name;
    @Json(name = "type") public String type; // "blob" or "tree"
    @Json(name = "path") public String path;
    @Json(name = "mode") public String mode;

    public String name() { return name; }
    public String path() { return path; }
    public String type() { return type; }
    public boolean isDirectory() { return "tree".equals(type); }
    public boolean isFile() { return "blob".equals(type); }

    // GitHub SDK Content compat
    public String sha() { return id; }
    public String encoding() { return null; }
    public String content() { return null; }
    public Long size() { return null; }
    public String htmlUrl() { return null; }
    public String downloadUrl() { return null; }

    public GitLabTreeItem() {}

    protected GitLabTreeItem(Parcel in) {
        id = in.readString();
        name = in.readString();
        type = in.readString();
        path = in.readString();
        mode = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(name);
        dest.writeString(type);
        dest.writeString(path);
        dest.writeString(mode);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabTreeItem> CREATOR = new Creator<GitLabTreeItem>() {
        @Override
        public GitLabTreeItem createFromParcel(Parcel in) { return new GitLabTreeItem(in); }

        @Override
        public GitLabTreeItem[] newArray(int size) { return new GitLabTreeItem[size]; }
    };
}
