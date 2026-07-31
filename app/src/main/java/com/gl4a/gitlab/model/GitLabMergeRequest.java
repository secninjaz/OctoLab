package com.gl4a.gitlab.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.squareup.moshi.Json;
import java.util.ArrayList;
import java.util.List;

public class GitLabMergeRequest implements Parcelable {
    @Json(name = "id") public long id;
    @Json(name = "iid") public int iid;
    @Json(name = "project_id") public long projectId;
    @Json(name = "title") public String title;
    @Json(name = "description") public String description;
    @Json(name = "state") public String state;
    @Json(name = "author") public GitLabUser author;
    @Json(name = "assignees") public List<GitLabUser> assignees;
    @Json(name = "assignee") public GitLabUser assignee;
    @Json(name = "reviewers") public List<GitLabUser> reviewers;
    @Json(name = "labels") public List<GitLabLabel> labelNames;
    @Json(name = "milestone") public GitLabMilestone milestone;
    @Json(name = "source_branch") public String sourceBranch;
    @Json(name = "target_branch") public String targetBranch;
    @Json(name = "source_project_id") public long sourceProjectId;
    @Json(name = "target_project_id") public long targetProjectId;
    @Json(name = "created_at") public String createdAt;
    @Json(name = "updated_at") public String updatedAt;
    @Json(name = "merged_at") public String mergedAt;
    @Json(name = "closed_at") public String closedAt;
    @Json(name = "merged_by") public GitLabUser mergedBy;
    @Json(name = "closed_by") public GitLabUser closedBy;
    @Json(name = "web_url") public String webUrl;
    @Json(name = "user_notes_count") public int commentsCount;
    @Json(name = "upvotes") public int upvotes;
    @Json(name = "downvotes") public int downvotes;
    @Json(name = "sha") public String sha;
    @Json(name = "merge_commit_sha") public String mergeCommitSha;
    @Json(name = "diff_refs") public DiffRefs diffRefs;
    @Json(name = "changes_count") public String changesCount;
    @Json(name = "rebase_in_progress") public boolean rebaseInProgress;
    @Json(name = "draft") public boolean draft;
    @Json(name = "work_in_progress") public boolean workInProgress;
    @Json(name = "has_conflicts") public boolean hasConflicts;
    @Json(name = "blocking_discussions_resolved") public boolean blockingDiscussionsResolved;
    @Json(name = "force_remove_source_branch") public boolean forceRemoveSourceBranch;
    @Json(name = "references") public GitLabIssue.GitLabReferences references;
    @Json(name = "head") public GitLabMRBranch head;
    @Json(name = "base") public GitLabMRBranch base;

    // GitHub SDK compatible methods (PullRequest)
    public long id() { return id; }
    public int number() { return iid; }
    public String title() { return title; }
    public String body() { return description; }
    /** Returns MR description rendered as HTML. */
    public String bodyHtml() { return com.gl4a.utils.HtmlUtils.markdownToHtml(description); }
    public String state() { return state; }
    public GitLabUser user() { return author; }
    public GitLabUser assignee() { return assignee; }
    public List<GitLabUser> assignees() { return assignees; }
    public GitLabMilestone milestone() { return milestone; }
    public String htmlUrl() { return webUrl; }
    public String createdAt() { return createdAt; }
    public String updatedAt() { return updatedAt; }
    public String mergedAt() { return mergedAt; }
    public boolean isMerged() { return "merged".equals(state); }
    public boolean isDraft() { return draft || workInProgress; }
    public int comments() { return commentsCount; }
    public String mergeSha() { return mergeCommitSha; }

    public GitLabMRBranch head() {
        if (head != null) return head;
        GitLabMRBranch h = new GitLabMRBranch();
        h.ref = sourceBranch;
        h.sha = sha;
        return h;
    }

    public GitLabMRBranch base() {
        if (base != null) return base;
        GitLabMRBranch b = new GitLabMRBranch();
        b.ref = targetBranch;
        return b;
    }

    public GitLabMergeRequest() {}

    protected GitLabMergeRequest(Parcel in) {
        id = in.readLong();
        iid = in.readInt();
        projectId = in.readLong();
        title = in.readString();
        description = in.readString();
        state = in.readString();
        author = in.readParcelable(GitLabUser.class.getClassLoader());
        assignee = in.readParcelable(GitLabUser.class.getClassLoader());
        mergedBy = in.readParcelable(GitLabUser.class.getClassLoader());
        closedBy = in.readParcelable(GitLabUser.class.getClassLoader());
        labelNames = in.createTypedArrayList(GitLabLabel.CREATOR);
        sourceBranch = in.readString();
        targetBranch = in.readString();
        sha = in.readString();
        mergeCommitSha = in.readString();
        createdAt = in.readString();
        updatedAt = in.readString();
        mergedAt = in.readString();
        closedAt = in.readString();
        changesCount = in.readString();
        draft = in.readByte() != 0;
        workInProgress = in.readByte() != 0;
        commentsCount = in.readInt();
        upvotes = in.readInt();
        downvotes = in.readInt();
        webUrl = in.readString();
        // Fix: previously omitted fields that affect header display and merge menu visibility
        assignees = in.createTypedArrayList(GitLabUser.CREATOR);
        reviewers = in.createTypedArrayList(GitLabUser.CREATOR);
        sourceProjectId = in.readLong();
        targetProjectId = in.readLong();
        rebaseInProgress = in.readByte() != 0;
        hasConflicts = in.readByte() != 0;
        blockingDiscussionsResolved = in.readByte() != 0;
        forceRemoveSourceBranch = in.readByte() != 0;
        // milestone
        int hasMilestone = in.readInt();
        if (hasMilestone == 1) {
            milestone = new GitLabMilestone();
            milestone.id = in.readLong();
            milestone.iid = in.readInt();
            milestone.title = in.readString();
            milestone.state = in.readString();
        }
        // diffRefs
        int hasDiffRefs = in.readInt();
        if (hasDiffRefs == 1) {
            diffRefs = new DiffRefs();
            diffRefs.baseSha = in.readString();
            diffRefs.headSha = in.readString();
            diffRefs.startSha = in.readString();
        }
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
        dest.writeParcelable(mergedBy, flags);
        dest.writeParcelable(closedBy, flags);
        dest.writeTypedList(labelNames);
        dest.writeString(sourceBranch);
        dest.writeString(targetBranch);
        dest.writeString(sha);
        dest.writeString(mergeCommitSha);
        dest.writeString(createdAt);
        dest.writeString(updatedAt);
        dest.writeString(mergedAt);
        dest.writeString(closedAt);
        dest.writeString(changesCount);
        dest.writeByte((byte) (draft ? 1 : 0));
        dest.writeByte((byte) (workInProgress ? 1 : 0));
        dest.writeInt(commentsCount);
        dest.writeInt(upvotes);
        dest.writeInt(downvotes);
        dest.writeString(webUrl);
        // Fix: include all previously omitted fields
        dest.writeTypedList(assignees);
        dest.writeTypedList(reviewers);
        dest.writeLong(sourceProjectId);
        dest.writeLong(targetProjectId);
        dest.writeByte((byte) (rebaseInProgress ? 1 : 0));
        dest.writeByte((byte) (hasConflicts ? 1 : 0));
        dest.writeByte((byte) (blockingDiscussionsResolved ? 1 : 0));
        dest.writeByte((byte) (forceRemoveSourceBranch ? 1 : 0));
        // milestone
        if (milestone != null) {
            dest.writeInt(1);
            dest.writeLong(milestone.id);
            dest.writeInt(milestone.iid);
            dest.writeString(milestone.title());
            dest.writeString(milestone.state());
        } else {
            dest.writeInt(0);
        }
        // diffRefs
        if (diffRefs != null) {
            dest.writeInt(1);
            dest.writeString(diffRefs.baseSha);
            dest.writeString(diffRefs.headSha);
            dest.writeString(diffRefs.startSha);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GitLabMergeRequest> CREATOR = new Creator<GitLabMergeRequest>() {
        @Override
        public GitLabMergeRequest createFromParcel(Parcel in) { return new GitLabMergeRequest(in); }

        @Override
        public GitLabMergeRequest[] newArray(int size) { return new GitLabMergeRequest[size]; }
    };

    public static class DiffRefs {
        @Json(name = "base_sha") public String baseSha;
        @Json(name = "head_sha") public String headSha;
        @Json(name = "start_sha") public String startSha;
    }

    public static class GitLabMRBranch {
        @Json(name = "ref") public String ref;
        @Json(name = "sha") public String sha;
        @Json(name = "repo") public GitLabProject repo;
        @Json(name = "user") public GitLabUser user;

        public String ref() { return ref; }
        public String sha() { return sha; }
        public GitLabProject repo() { return repo; }
        public GitLabUser user() { return user; }
        public String label() { return ref; }
    }
}
