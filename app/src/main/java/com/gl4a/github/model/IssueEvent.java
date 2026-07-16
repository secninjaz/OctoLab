package com.gl4a.github.model;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabLabel;
import com.gl4a.gitlab.model.GitLabMilestone;
import java.util.Date;
import java.util.List;
import com.gl4a.gitlab.model.GitLabIssueEventType;
/** Stub for GitHub IssueEvent. */
public class IssueEvent {
    public String event() { return GitLabIssueEventType.Commented; }
    public GitLabUser actor() { return null; }
    public GitLabUser assigner() { return null; }
    public GitLabUser assignee() { return null; }
    public GitLabLabel label() { return null; }
    public GitLabMilestone milestone() { return null; }
    public String commitId() { return null; }
    public String commitUrl() { return null; }
    public Date createdAt() { return new Date(); }
    public Rename rename() { return null; }
    public String lockReason() { return null; }
    public String stateReason() { return null; }
    public GitLabUser requestedReviewer() { return null; }
    public List<GitLabUser> requestedReviewers() { return null; }
    public GitLabUser reviewRequester() { return null; }
    public Team requestedTeam() { return null; }
    public DismissedReview dismissedReview() { return null; }
    public CrossReferenceSource source() { return null; }
    public GitLabComment toComment() { return null; }
    public static class DismissedReview {
        public String dismissalCommitId() { return null; }
    }
    public static class CrossReferenceSource {
        public com.gl4a.gitlab.model.GitLabIssue issue() { return null; }
    }
}
