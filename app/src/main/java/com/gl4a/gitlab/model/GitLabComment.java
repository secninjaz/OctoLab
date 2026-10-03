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
    // When a resolvable discussion was resolved; resolved/resolvable/resolved_by are below (#179)
    @Json(name = "resolved_at") public String resolvedAt;
    // True when a push resolved the thread: "Automatically resolved" (#179)
    @Json(name = "resolved_by_push") public boolean resolvedByPush;
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
            // Before the 'Z'-literal patterns, which parse as local time: GraphQL times
            // have no milliseconds ("2026-08-17T11:54:42Z") and would be off by the UTC offset
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
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

    // Transient reaction cache — populated asynchronously from the award emoji API
    /** Set when this "note" stands for resource events (labels/milestone/state), #180. */
    public static class EventInfo {
        public final String kind;  // "label", "milestone", "state"
        public final java.util.List<GitLabLabel> added = new java.util.ArrayList<>();
        public final java.util.List<GitLabLabel> removed = new java.util.ArrayList<>();
        public String milestone;
        public boolean milestoneRemoved;
        public String state;
        public String sourceCommit;
        // "closed with merge request fmd-server!44 (merged)" (#200)
        public String sourceMrProject;
        public String sourceMrIid;
        public String sourceMrState;
        public String sourceMrUrl;

        public EventInfo(String kind) {
            this.kind = kind;
        }
    }
    public transient EventInfo eventInfo;

    // Thread info from the discussions API (#123). A reply is any note after the first in a
    // non-individual discussion; GitLab threads are one level deep.
    private transient String mDiscussionId;
    private transient boolean mThreadReply;
    public String discussionId() { return mDiscussionId; }
    public boolean isThreadReply() { return mThreadReply; }
    /** On a thread's first note: its replies, for the collapsed "N replies" row (#179). */
    public static class ThreadSummary {
        public int replyCount;
        public GitLabUser lastReplyAuthor;
        public String lastReplyAt;
        public final java.util.List<GitLabUser> replyAuthors = new java.util.ArrayList<>();
    }
    public transient ThreadSummary threadSummary;
    /** The thread's resolved state, on every note of it, incl. system notes (#179). */
    public transient boolean threadResolved;

    public Date resolvedAtDate() { return parseIso(resolvedAt); }

    /** Place in a thread, for its card shape (#179): see THREAD_* constants. */
    public static final int THREAD_NONE = 0, THREAD_FIRST = 1, THREAD_MIDDLE = 2, THREAD_LAST = 3;
    private transient int mThreadPosition;
    public int threadPosition() { return mThreadPosition; }
    public void setThreadPosition(int position) { mThreadPosition = position; }

    public GitLabComment withThread(String discussionId, boolean reply) {
        mDiscussionId = discussionId;
        mThreadReply = reply;
        return this;
    }

    // Who last edited the note and when, from GraphQL (REST has no such fields); the editor is
    // null if the note was never edited. Not loaded when GraphQL is unavailable (#151).
    private transient boolean mEditInfoLoaded;
    private transient String mLastEditedAt;
    private transient GitLabUser mLastEditedBy;
    public boolean editInfoLoaded() { return mEditInfoLoaded; }
    public Date lastEditedAtDate() { return parseIso(mLastEditedAt); }
    public GitLabUser lastEditedBy() { return mLastEditedBy; }
    public GitLabComment withEditInfo(String lastEditedAt, GitLabUser lastEditedBy) {
        mEditInfoLoaded = true;
        mLastEditedAt = lastEditedAt;
        mLastEditedBy = lastEditedBy;
        return this;
    }

    // GitLab's stored rendering of the note, from GraphQL (REST has none), shown instead of
    // rendering the markdown again (#199); null when not loaded
    private transient String mBodyHtml;
    public String storedHtml() { return mBodyHtml; }
    public GitLabComment withStoredHtml(String bodyHtml) {
        mBodyHtml = bodyHtml;
        return this;
    }

    private transient GitLabReactions mCachedReactions;
    public GitLabReactions reactions() { return mCachedReactions; }
    public GitLabComment withReactions(GitLabReactions r) { mCachedReactions = r; return this; }

    // Emoji contents the signed-in user reacted with; null until reaction details are loaded
    private transient java.util.Set<String> mViewerReactedContents;
    public java.util.Set<String> viewerReactedContents() { return mViewerReactedContents; }

    /** Stores reaction counts and the viewer's own reactions computed from award emoji details. */
    public GitLabComment withReactionDetails(java.util.List<GitLabReaction> details,
            String ownLogin) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        java.util.Set<String> viewerReacted = new java.util.HashSet<>();
        for (GitLabReaction r : details) {
            counts.merge(r.name, 1, Integer::sum);
            if (com.gl4a.utils.ApiHelpers.loginEquals(r.user(), ownLogin)) {
                viewerReacted.add(r.content());
            }
        }
        mCachedReactions = GitLabReactions.builder()
                .plusOne(counts.getOrDefault("thumbsup", 0))
                .minusOne(counts.getOrDefault("thumbsdown", 0))
                .laugh(counts.getOrDefault("laughing", 0))
                .hooray(counts.getOrDefault("tada", 0))
                .heart(counts.getOrDefault("heart", 0))
                .confused(counts.getOrDefault("confused", 0))
                .rocket(counts.getOrDefault("rocket", 0))
                .eyes(counts.getOrDefault("eyes", 0))
                .build();
        mViewerReactedContents = viewerReacted;
        return this;
    }
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
        note = in.readString();
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
        dest.writeString(note);
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
