package com.gl4a.github.service;

import com.gl4a.github.model.GitComment;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabPage;

import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * Stub interface for RepositoryCommentService.
 * Original GitHub SDK service — stubbed for GitLab port compilation.
 * Replace usages with the corresponding GitLab service (GitLabCommitService).
 */
public interface RepositoryCommentService {
    Single<Response<GitLabPage<GitComment>>> getCommitComments(String owner, String repo, String sha, long page);
    Single<Response<GitLabComment>> createCommitComment(String owner, String repo, String sha, Map<String, Object> body);
    Single<Response<Void>> deleteCommitComment(String owner, String repo, long id);
}
