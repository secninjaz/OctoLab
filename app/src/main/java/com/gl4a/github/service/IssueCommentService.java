package com.gl4a.github.service;

import com.gl4a.gitlab.model.GitLabComment;

import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * Stub interface for IssueCommentService.
 * Original GitHub SDK service — stubbed for GitLab port compilation.
 * Replace usages with the corresponding GitLab service (GitLabIssueService).
 */
public interface IssueCommentService {
    Single<Response<GitLabComment>> createIssueComment(String owner, String repo, int number,
            Map<String, Object> body);
}
