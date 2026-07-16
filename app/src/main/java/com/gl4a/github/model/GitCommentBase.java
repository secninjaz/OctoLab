package com.gl4a.github.model;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabReactions;
import java.util.Date;
/** Stub for GitHub SDK GitHubCommentBase. Compiled via GitLabComment. */
public interface GitCommentBase {
    long id();
    String body();
    String bodyHtml();
    String htmlUrl();
    GitLabUser user();
    Date createdAt();
    Date updatedAt();
    GitLabReactions reactions();
    GitCommentBase withReactions(GitLabReactions r);
}
