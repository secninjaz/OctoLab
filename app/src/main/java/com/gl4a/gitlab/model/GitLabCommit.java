package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;
import com.squareup.moshi.Json;
import java.util.List;

public class GitLabCommit implements Parcelable {
    @Json(name = "id") public String id;
    @Json(name = "short_id") public String shortId;
    @Json(name = "title") public String title;
    @Json(name = "message") public String message;
    @Json(name = "author_name") public String authorName;
    @Json(name = "author_email") public String authorEmail;
    @Json(name = "authored_date") public String authoredDate;
    @Json(name = "committer_name") public String committerName;
    @Json(name = "committer_email") public String committerEmail;
    @Json(name = "committed_date") public String committedDate;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "parent_ids") public List<String> parentIds;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "stats") public CommitStats stats;
    @Json(name = "status") public String status;
    // author as user (resolved separately)
    public GitLabUser authorUser;

    // GitHub SDK compatible methods
    public String sha() { return id; }
    public GitLabCommitDetail commit() { return toDetail(); }

    private GitLabCommitDetail toDetail() {
        GitLabCommitDetail d = new GitLabCommitDetail();
        d.message = message;
        d.authoredDate = authoredDate;
        d.committedDate = committedDate;
        d.authorName = authorName;
        d.authorEmail = authorEmail;
        return d;
    }

    public GitLabUser author() {
        if (authorUser != null) return authorUser;
        GitLabUser u = new GitLabUser();
        u.username = authorEmail;
        u.name = authorName;
        u.email = authorEmail;
        return u;
    }

    public GitLabCommit() {}

    protected GitLabCommit(Parcel in) {
        id = in.readString();
        shortId = in.readString();
        title = in.readString();
        message = in.readString();
        authorName = in.readString();
        authorEmail = in.readString();
        authoredDate = in.readString();
        committerName = in.readString();
        committerEmail = in.readString();
        committedDate = in.readString();
        createdAt = in.readString();
        parentIds = in.createStringArrayList();
        webUrl = in.readString();
        status = in.readString();
        authorUser = in.readParcelable(GitLabUser.class.getClassLoader());
        // stats — stored as addition/deletion/total ints with a present flag
        int hasStats = in.readInt();
        if (hasStats == 1) {
            stats = new CommitStats();
            stats.additions = in.readInt();
            stats.deletions = in.readInt();
            stats.total = in.readInt();
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(shortId);
        dest.writeString(title);
        dest.writeString(message);
        dest.writeString(authorName);
        dest.writeString(authorEmail);
        dest.writeString(authoredDate);
        dest.writeString(committerName);
        dest.writeString(committerEmail);
        dest.writeString(committedDate);
        dest.writeString(createdAt);
        dest.writeStringList(parentIds);
        dest.writeString(webUrl);
        dest.writeString(status);
        dest.writeParcelable(authorUser, flags);
        if (stats != null) {
            dest.writeInt(1);
            dest.writeInt(stats.additions);
            dest.writeInt(stats.deletions);
            dest.writeInt(stats.total);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabCommit> CREATOR = new Creator<GitLabCommit>() {
        @Override
        public GitLabCommit createFromParcel(Parcel in) { return new GitLabCommit(in); }

        @Override
        public GitLabCommit[] newArray(int size) { return new GitLabCommit[size]; }
    };

    public static class CommitStats {
        @Json(name = "additions") public int additions;
        @Json(name = "deletions") public int deletions;
        @Json(name = "total") public int total;
    }

    public static class GitLabCommitDetail {
        public String message;
        public String authoredDate;
        public String committedDate;
        public String authorName;
        public String authorEmail;

        public String message() { return message; }
        public GitLabGitUser author() {
            GitLabGitUser u = new GitLabGitUser();
            u.name = authorName;
            u.email = authorEmail;
            u.date = authoredDate;
            return u;
        }
        public GitLabGitUser committer() {
            GitLabGitUser u = new GitLabGitUser();
            u.name = "Committer";
            u.date = committedDate;
            return u;
        }
    }

    public static class GitLabGitUser {
        public String name;
        public String email;
        public String date;

        public String name() { return name; }
        public String email() { return email; }
        public String date() { return date; }
    }
}
