package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;
import com.squareup.moshi.Json;

public class GitLabLabel implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "name") public String name;
    @Json(name = "color") public String color;
    @Json(name = "text_color") public String textColor;
    @Json(name = "description") public String description;
    @Json(name = "open_issues_count") public Integer openIssuesCount;
    @Json(name = "closed_issues_count") public Integer closedIssuesCount;

    public long id() { return id; }
    public String name() { return name; }
    public String color() { return color != null ? color.replace("#", "") : "eeeeee"; }
    public String description() { return description; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final GitLabLabel label = new GitLabLabel();
        public Builder name(String n) { label.name = n; return this; }
        public Builder color(String c) { label.color = c; return this; }
        public GitLabLabel build() { return label; }
    }

    public GitLabLabel() {}

    protected GitLabLabel(Parcel in) {
        id = in.readLong();
        name = in.readString();
        color = in.readString();
        textColor = in.readString();
        description = in.readString();
        openIssuesCount = in.readInt();
        closedIssuesCount = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeString(color);
        dest.writeString(textColor);
        dest.writeString(description);
        dest.writeInt(openIssuesCount);
        dest.writeInt(closedIssuesCount);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabLabel> CREATOR = new Creator<GitLabLabel>() {
        @Override
        public GitLabLabel createFromParcel(Parcel in) { return new GitLabLabel(in); }

        @Override
        public GitLabLabel[] newArray(int size) { return new GitLabLabel[size]; }
    };
}
