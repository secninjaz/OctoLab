package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabMergeRequest;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabSearchResult;
import com.gl4a.gitlab.model.GitLabUser;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface GitLabSearchService {
    @GET("search")
    Single<Response<List<GitLabProject>>> searchProjects(
            @Query("scope") String scope,
            @Query("search") String query,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("search")
    Single<Response<List<GitLabUser>>> searchUsers(
            @Query("scope") String scope,
            @Query("search") String query,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("search")
    Single<Response<List<GitLabIssue>>> searchIssues(
            @Query("scope") String scope,
            @Query("search") String query,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("search")
    Single<Response<List<GitLabSearchResult.GitLabBlob>>> searchCode(
            @Query("scope") String scope,
            @Query("search") String query,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/search")
    Single<Response<List<GitLabSearchResult.GitLabBlob>>> searchInProject(
            @retrofit2.http.Path("id") long projectId,
            @Query("scope") String scope,
            @Query("search") String query,
            @Query("page") int page,
            @Query("per_page") int perPage
    );
}
