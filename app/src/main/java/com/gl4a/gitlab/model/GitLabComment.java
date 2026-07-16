package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.squareup.moshi.Json;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GitLabComment implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "type") public String type;
    @Json(name = "body") public String body;
    @Json(name = "note") public String note;  // commit comments use "note" instead of "body"
    @Json(name = "author") public GitLabUser author;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "updated_at") public String updatedAt;
    @Json(name = "system") public boolean system;
    @Json(name = "noteable_type") public String noteableType;
    @Json(name = "noteable_id") public long noteableId;
    @Json(name = "noteable_iid") public long noteableIid;
    @Json(name = "resolved") public boolean resolved;
    @Json(name = "resolvable") public boolean resolvable;
    @Json(name = "resolved_by") public GitLabUser resolvedBy;
    @Json(name = "position") public DiffPosition diffPosition;
    @Json(name = "award_emoji") public java.util.List<GitLabReaction> awardEmoji;
    // Commit comment top-level fields (not wrapped in a "position" object)
    @Json(name = "path") public String commitPath;
    @Json(name = "line") public Integer commitLine;
    @Json(name = "line_type") public String commitLineType;

    // GitHub SDK compatible methods (IssueComment / GitHubCommentBase)
    public long id() { return id; }
    /** Returns comment text — falls back to "note" field used by commit comments. */
    public String body() { return body != null ? body : note; }
    public GitLabUser user() { return author; }
    public String createdAt() { return createdAt; }
    public String updatedAt() { return updatedAt; }
    /** Returns the comment body rendered as HTML. Falls back to note field (commit comments). */
    public String bodyHtml() { return com.gl4a.utils.HtmlUtils.markdownToHtml(body()); }
    /** GitLab API has no author_association field; return null to match absent/unknown. */
    public String authorAssociation() { return null; }
    /** System notes (label changes, state changes) have system=true. Filter these for comment-only views. */
    public boolean isSystemNote() { return system; }

    /** Returns createdAt parsed as a Date for use in timeline comparisons. */
    public Date createdAtDate() { return parseIso(createdAt); }

    /** Returns updatedAt parsed as a Date for edit-timestamp display. */
    public Date updatedAtDate() { return parseIso(updatedAt); }

    private static Date parseIso(String s) {
        if (s == null) return null;
        String[] fmts = {
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        };
        for (String fmt : fmts) {
            try { return new SimpleDateFormat(fmt, Locale.US).parse(s); }
            catch (ParseException ignored) {}
        }
        return null;
    }

    // Transient field: populated when loading MR diff notes, not from JSON
    private String mrWebUrl = "";

    /** Set the MR web URL so that makeDiffIntent() can open the correct diff view. */
    public void setMrWebUrl(String url) { this.mrWebUrl = url != null ? url : ""; }

    // Stub methods for GitHub SDK features not in GitLab
    public GitLabReactions reactions() { return null; }
    public GitLabComment withReactions(GitLabReactions r) { return this; }
    public String htmlUrl() { return ""; }
    public String pullRequestUrl() { return mrWebUrl; }
    public String commitId() { return ""; }
    public String originalCommitId() { return ""; }
    public String path() {
        if (diffPosition != null) {
            // MR/diff note: prefer new_path (target file); fall back to old_path
            if (diffPosition.newPath != null) return diffPosition.newPath;
            if (diffPosition.oldPath != null) return diffPosition.oldPath;
        }
        // Commit comment: path is a top-level field
        return commitPath;
    }
    public int originalPosition() { return 0; }
    /** Returns diff line position, or null if not a diff comment. */
    public Integer position() {
        if (diffPosition == null) return null;
        return diffPosition.newLine != null ? diffPosition.newLine : diffPosition.oldLine;
    }

    public GitLabComment() {}

    protected GitLabComment(Parcel in) {
        id = in.readLong();
        body = in.readString();
        author = in.readParcelable(GitLabUser.class.getClassLoader());
        createdAt = in.readString();
        updatedAt = in.readString();
        system = in.readByte() != 0;
        noteableType = in.readString();
        noteableId = in.readLong();
        noteableIid = in.readLong();
        resolved = in.readByte() != 0;
        resolvable = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(body);
        dest.writeParcelable(author, flags);
        dest.writeString(createdAt);
        dest.writeString(updatedAt);
        dest.writeByte((byte) (system ? 1 : 0));
        dest.writeString(noteableType);
        dest.writeLong(noteableId);
        dest.writeLong(noteableIid);
        dest.writeByte((byte) (resolved ? 1 : 0));
        dest.writeByte((byte) (resolvable ? 1 : 0));
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabComment> CREATOR = new Creator<GitLabComment>() {
        @Override
        public GitLabComment createFromParcel(Parcel in) { return new GitLabComment(in); }

        @Override
        public GitLabComment[] newArray(int size) { return new GitLabComment[size]; }
    };

    /**
     * Position object for diff notes (DiscussionNote / DiffNote).
     * Returned by the API inside the note's "position" field.
     */
    public static class DiffPosition {
        @Json(name = "base_sha") public String baseSha;
        @Json(name = "start_sha") public String startSha;
        @Json(name = "head_sha") public String headSha;
        @Json(name = "old_path") public String oldPath;
        @Json(name = "new_path") public String newPath;
        @Json(name = "position_type") public String positionType;
        @Json(name = "old_line") public Integer oldLine;
        @Json(name = "new_line") public Integer newLine;
    }
}
