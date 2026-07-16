package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;
import com.squareup.moshi.Json;
import java.util.Date;

public class GitLabMilestone implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "iid") public int iid;
    @Json(name = "project_id") public long projectId;
    @Json(name = "title") public String title;
    @Json(name = "description") public String description;
    @Json(name = "state") public String state;
    @Json(name = "due_date") public String dueDate;
    @Json(name = "start_date") public String startDate;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "updated_at") public String updatedAt;
    @Json(name = "closed_issues") public Integer closedIssues;  // null when no issue data loaded
    @Json(name = "total_issues") public Integer totalIssues;  // null in basic milestone response
    @Json(name = "web_url") public String webUrl;

    // Mutable due-date (held separately from parsed JSON for edit support)
    private Date mDueOnDate;

    public long id() { return id; }
    public int number() { return iid; }
    public String title() { return title; }
    public String description() { return description; }
    public String state() { return state; }

    /** Returns the due-date as a Date; null if not set. */
    public Date dueOn() { return mDueOnDate; }

    public int openIssues() { 
        if (totalIssues == null || closedIssues == null) return 0;
        return totalIssues - closedIssues;
    }
    public int closedIssues() { return closedIssues != null ? closedIssues : 0; }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.milestone.id = id;
        b.milestone.iid = iid;
        b.milestone.title = title;
        b.milestone.description = description;
        b.milestone.state = state;
        b.milestone.dueDate = dueDate;
        b.milestone.mDueOnDate = mDueOnDate;
        return b;
    }

    public static class Builder {
        private final GitLabMilestone milestone = new GitLabMilestone();

        public Builder state(String s) { milestone.state = s; return this; }
        public Builder title(String t) { milestone.title = t; return this; }
        public Builder description(String d) { milestone.description = d; return this; }
        public Builder dueOn(Date d) { milestone.mDueOnDate = d; return this; }
        public GitLabMilestone build() { return milestone; }
    }

    public GitLabMilestone() {}

    protected GitLabMilestone(Parcel in) {
        id = in.readLong();
        iid = in.readInt();
        title = in.readString();
        description = in.readString();
        state = in.readString();
        dueDate = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeInt(iid);
        dest.writeString(title);
        dest.writeString(description);
        dest.writeString(state);
        dest.writeString(dueDate);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabMilestone> CREATOR = new Creator<GitLabMilestone>() {
        @Override
        public GitLabMilestone createFromParcel(Parcel in) { return new GitLabMilestone(in); }

        @Override
        public GitLabMilestone[] newArray(int size) { return new GitLabMilestone[size]; }
    };
}
