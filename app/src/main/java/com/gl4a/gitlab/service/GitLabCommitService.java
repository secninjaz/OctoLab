package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabCommitDiscussion;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabDiff;
import com.squareup.moshi.Json;

import java.util.List;
import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabCommitService {

    @GET("projects/{id}/repository/commits")
    Single<Response<List<GitLabCommit>>> listCommits(
            @Path("id") long projectId,
            @Query("ref_name") String refName,
            @Query("path") String path,
            @Query("page") int page,
            @Query("per_page") int perPage,
            @Query("since") String since,
            @Query("until") String until
    );

    @GET("projects/{id}/repository/commits/{sha}")
    Single<Response<GitLabCommit>> getCommit(
            @Path("id") long projectId,
            @Path("sha") String sha
    );

    @GET("projects/{id}/repository/commits/{sha}/diff")
    Single<Response<List<GitLabDiff>>> getCommitDiff(
            @Path("id") long projectId,
            @Path("sha") String sha,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/repository/commits/{sha}/discussions")
    Single<Response<List<GitLabCommitDiscussion>>> getCommitDiscussions(
            @Path("id") long projectId,
            @Path("sha") String sha,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/repository/commits/{sha}/comments")
    Single<Response<List<GitLabComment>>> getCommitComments(
            @Path("id") long projectId,
            @Path("sha") String sha,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("projects/{id}/repository/commits/{sha}/comments")
    Single<Response<GitLabComment>> createCommitComment(
            @Path("id") long projectId,
            @Path("sha") String sha,
            @Body Map<String, Object> body
    );

    @GET("projects/{id}/repository/compare")
    Single<Response<GitLabCompare>> compareCommits(
            @Path("id") long projectId,
            @Query("from") String from,
            @Query("to") String to
    );

    // CI pipeline statuses for a specific commit SHA
    @GET("projects/{id}/repository/commits/{sha}/statuses")
    Single<Response<List<Map<String, Object>>>> getCommitStatuses(
            @Path("id") long projectId,
            @Path("sha") String sha,
            @Query("ref") String ref,
            @Query("stage") String stage,
            @Query("name") String name,
            @Query("all") boolean all,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    class GitLabCompare {
        public GitLabCommit commit;
        public List<GitLabCommit> commits;
        public List<GitLabDiff> diffs;
        // Snake-case keys require explicit @Json annotations; Moshi does NOT auto-convert
        @Json(name = "compare_timeout") public boolean compareTimeout;
        @Json(name = "compare_same_ref") public boolean compareSameRef;
    }
}
