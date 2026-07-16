package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.squareup.moshi.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GitLabIssue implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "iid") public int iid;
    @Json(name = "project_id") public long projectId;
    @Json(name = "title") public String title;
    @Json(name = "description") public String description;
    @Json(name = "state") public String state;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "assignees") public List<GitLabUser> assignees;
    @Json(name = "assignee") public GitLabUser assignee;
    @Json(name = "labels") public List<String> labelNames;
    @Json(name = "milestone") public GitLabMilestone milestone;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "updated_at") public String updatedAt;
    @Json(name = "closed_at") public String closedAt;
    @Json(name = "closed_by") public GitLabUser closedBy;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "user_notes_count") public int commentsCount;
    @Json(name = "upvotes") public int upvotes;
    @Json(name = "downvotes") public int downvotes;
    @Json(name = "due_date") public String dueDate;
    @Json(name = "weight") public Integer weight;
    @Json(name = "time_stats") public TimeStats timeStats;
    @Json(name = "confidential") public boolean confidential;
    @Json(name = "references") public GitLabReferences references;

    // GitHub SDK compatible methods
    public long id() { return id; }
    public int number() { return iid; }
    public String title() { return title; }
    public String body() { return description; }
    public String state() { return state; }
    public GitLabUser user() { return author; }
    public GitLabUser assignee() { return assignee; }
    public List<GitLabUser> assignees() { return assignees; }
    public GitLabMilestone milestone() { return milestone; }
    public String htmlUrl() { return webUrl; }
    /** GitHub SDK compatibility alias for htmlUrl(). */
    public String url() { return webUrl; }
    public String createdAt() { return createdAt; }
    public String updatedAt() { return updatedAt; }
    public String closedAt() { return closedAt; }
    public GitLabUser closedBy() { return closedBy; }
    public int comments() { return commentsCount; }
    public boolean isPullRequest() { return false; }
    // GitLab has no "locked" concept equivalent to GitHub; return false unconditionally.
    // "confidential" is a separate field meaning private-to-members, not comment-locked.
    public boolean locked() { return false; }
    public Object pullRequest() { return null; }
    /** Returns the issue description rendered as HTML for display in the app. */
    public String bodyHtml() { return com.gl4a.utils.HtmlUtils.markdownToHtml(description); }
    public GitLabReactions reactions() { return null; }

    /** Returns labels as GitLabLabel objects using labelNames. */
    public List<GitLabLabel> labels() {
        if (labelNames == null) return Collections.emptyList();
        List<GitLabLabel> result = new ArrayList<>();
        for (String name : labelNames) {
            GitLabLabel label = new GitLabLabel();
            label.name = name;
            result.add(label);
        }
        return result;
    }

    public GitLabIssue() {}

    protected GitLabIssue(Parcel in) {
        id = in.readLong();
        iid = in.readInt();
        projectId = in.readLong();
        title = in.readString();
        description = in.readString();
        state = in.readString();
        author = in.readParcelable(GitLabUser.class.getClassLoader());
        assignee = in.readParcelable(GitLabUser.class.getClassLoader());
        closedBy = in.readParcelable(GitLabUser.class.getClassLoader());
        assignees = in.createTypedArrayList(GitLabUser.CREATOR);
        labelNames = in.createStringArrayList();
        createdAt = in.readString();
        updatedAt = in.readString();
        closedAt = in.readString();
        webUrl = in.readString();
        commentsCount = in.readInt();
        upvotes = in.readInt();
        downvotes = in.readInt();
        dueDate = in.readString();
        confidential = in.readByte() != 0;
        // Fix: milestone, weight, timeStats, references were missing from Parcel round-trip
        int hasMilestone = in.readInt();
        if (hasMilestone == 1) {
            milestone = new GitLabMilestone();
            milestone.id = in.readLong();
            milestone.iid = in.readInt();
            milestone.title = in.readString();
            milestone.state = in.readString();
        }
        int hasWeight = in.readInt();
        if (hasWeight == 1) {
            weight = in.readInt();
        }
        // timeStats and references are skipped in parcelling (not displayed from parcel path)
        // but we must write/read a flag to keep the stream in sync.
        in.readInt(); // timeStats placeholder
        in.readInt(); // references placeholder
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeInt(iid);
        dest.writeLong(projectId);
        dest.writeString(title);
        dest.writeString(description);
        dest.writeString(state);
        dest.writeParcelable(author, flags);
        dest.writeParcelable(assignee, flags);
        dest.writeParcelable(closedBy, flags);
        dest.writeTypedList(assignees);
        dest.writeStringList(labelNames);
        dest.writeString(createdAt);
        dest.writeString(updatedAt);
        dest.writeString(closedAt);
        dest.writeString(webUrl);
        dest.writeInt(commentsCount);
        dest.writeInt(upvotes);
        dest.writeInt(downvotes);
        dest.writeString(dueDate);
        dest.writeByte((byte) (confidential ? 1 : 0));
        // Fix: include milestone so that fillData() can display milestone info
        if (milestone != null) {
            dest.writeInt(1);
            dest.writeLong(milestone.id);
            dest.writeInt(milestone.iid);
            dest.writeString(milestone.title());
            dest.writeString(milestone.state());
        } else {
            dest.writeInt(0);
        }
        // weight
        if (weight != null) {
            dest.writeInt(1);
            dest.writeInt(weight);
        } else {
            dest.writeInt(0);
        }
        // timeStats and references: placeholder flags (not yet Parcelable)
        dest.writeInt(0);
        dest.writeInt(0);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabIssue> CREATOR = new Creator<GitLabIssue>() {
        @Override
        public GitLabIssue createFromParcel(Parcel in) { return new GitLabIssue(in); }

        @Override
        public GitLabIssue[] newArray(int size) { return new GitLabIssue[size]; }
    };

    public static class GitLabReferences {
        @Json(name = "short") public String shortRef;
        @Json(name = "relative") public String relative;
        @Json(name = "full") public String full;
    }

    /** GitLab time-tracking summary returned inside an issue object. */
    public static class TimeStats {
        @Json(name = "time_estimate") public int timeEstimate;
        @Json(name = "total_time_spent") public int totalTimeSpent;
        @Json(name = "human_time_estimate") public String humanTimeEstimate;
        @Json(name = "human_total_time_spent") public String humanTotalTimeSpent;
    }
}
