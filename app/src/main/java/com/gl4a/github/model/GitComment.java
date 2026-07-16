package com.gl4a.github.model;
import android.os.Parcel;
import android.os.Parcelable;
import com.gl4a.gitlab.model.GitLabUser;
import java.util.Date;
/** Stub for GitHub SDK git.GitComment. */
public class GitComment implements GitCommentBase, Parcelable {
    public long id() { return 0; }
    public String body() { return ""; }
    public String bodyHtml() { return ""; }
    public String htmlUrl() { return ""; }
    public GitLabUser user() { return null; }
    public Date createdAt() { return new Date(); }
    public Date updatedAt() { return new Date(); }
    public String commitId() { return ""; }
    public String path() { return null; }
    public Integer position() { return null; }
    public com.gl4a.gitlab.model.GitLabReactions reactions() { return null; }
    public GitComment withReactions(com.gl4a.gitlab.model.GitLabReactions r) { return this; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Parcelable.Creator<GitComment> CREATOR = new Parcelable.Creator<GitComment>() {
        @Override public GitComment createFromParcel(Parcel in) { return new GitComment(); }
        @Override public GitComment[] newArray(int size) { return new GitComment[size]; }
    };
}
