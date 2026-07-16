package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.squareup.moshi.Json;

/**
 * Represents a GitLab merge request review (approval / discussion thread).
 * Maps to GitHub's Review concept for display purposes.
 */
public class GitLabReview implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "iid") public int iid;
    @Json(name = "body") public String body;
    @Json(name = "body_html") public String bodyHtml;
    @Json(name = "state") public String state; // "approved", "changes_requested", "commented", etc.
    @Json(name = "submitted_at") public String submittedAt;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "html_url") public String htmlUrl;

    // GitHub SDK compatible methods
    public long id() { return id; }
    public String body() { return body; }
    public String bodyHtml() { return bodyHtml != null ? bodyHtml : body; }
    public String htmlUrl() { return htmlUrl != null ? htmlUrl : ""; }
    public GitLabUser user() { return author; }

    /** Returns the submission date parsed from the ISO-8601 string, or null on failure. */
    public java.util.Date submittedAt() {
        if (submittedAt == null) return null;
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    java.util.Locale.US).parse(submittedAt);
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    /** Map GitLab review state to a displayable ReviewState-like enum value. */
    public ReviewState state() {
        if (state == null) return ReviewState.Commented;
        switch (state.toLowerCase()) {
            case "approved": return ReviewState.Approved;
            case "changes_requested": return ReviewState.ChangesRequested;
            case "dismissed": return ReviewState.Dismissed;
            case "pending": return ReviewState.Pending;
            default: return ReviewState.Commented;
        }
    }

    public enum ReviewState {
        Approved, ChangesRequested, Commented, Dismissed, Pending
    }

    public GitLabReview() {}

    protected GitLabReview(Parcel in) {
        id = in.readLong();
        iid = in.readInt();
        body = in.readString();
        bodyHtml = in.readString();
        state = in.readString();
        submittedAt = in.readString();
        htmlUrl = in.readString();
        author = in.readParcelable(GitLabUser.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeInt(iid);
        dest.writeString(body);
        dest.writeString(bodyHtml);
        dest.writeString(state);
        dest.writeString(submittedAt);
        dest.writeString(htmlUrl);
        dest.writeParcelable(author, flags);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabReview> CREATOR = new Creator<GitLabReview>() {
        @Override
        public GitLabReview createFromParcel(Parcel in) { return new GitLabReview(in); }

        @Override
        public GitLabReview[] newArray(int size) { return new GitLabReview[size]; }
    };
}
