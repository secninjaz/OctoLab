package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.model.GitLabMergeRequest;

import java.util.List;
import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabMergeRequestService {

    // state must be "opened", "closed", "locked", or "merged" — NOT "open"
    @GET("projects/{id}/merge_requests")
    Single<Response<List<GitLabMergeRequest>>> listMergeRequests(
            @Path("id") long projectId,
            @Query("state") String state,       // "opened" | "closed" | "merged" | "all"
            @Query("labels") String labels,
            @Query("milestone") String milestone,
            @Query("page") int page,
            @Query("per_page") int perPage,
            @Query("order_by") String orderBy,
            @Query("sort") String sort,
            @Query("search") String search
    );

    // state must be "opened", "closed", "locked", or "merged" — NOT "open"
    @GET("merge_requests")
    Single<Response<List<GitLabMergeRequest>>> listMyMergeRequests(
            @Query("state") String state,       // "opened" | "closed" | "merged" | "all"
            @Query("scope") String scope,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/merge_requests/{iid}")
    Single<Response<GitLabMergeRequest>> getMergeRequest(
            @Path("id") long projectId,
            @Path("iid") int iid
    );

    @POST("projects/{id}/merge_requests")
    Single<Response<GitLabMergeRequest>> createMergeRequest(
            @Path("id") long projectId,
            @Body Map<String, Object> body
    );

    @PUT("projects/{id}/merge_requests/{iid}")
    Single<Response<GitLabMergeRequest>> editMergeRequest(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    // Merge — PUT /projects/:id/merge_requests/:mr_iid/merge
    // Body may include: merge_commit_message, squash_commit_message,
    //                   should_remove_source_branch, squash, sha (required if prevent early merge)
    @PUT("projects/{id}/merge_requests/{iid}/merge")
    Single<Response<GitLabMergeRequest>> mergeMergeRequest(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    @GET("projects/{id}/merge_requests/{iid}/commits")
    Single<Response<List<GitLabCommit>>> getCommits(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Diffs (paginated list of individual diffs)
    @GET("projects/{id}/merge_requests/{iid}/diffs")
    Single<Response<List<GitLabDiff>>> getDiffs(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Changes — returns the MR object with a "changes" array of diff objects
    // Use this to get all changed files with their diffs in a single call
    @GET("projects/{id}/merge_requests/{iid}/changes")
    Single<Response<GitLabMergeRequest>> getChanges(
            @Path("id") long projectId,
            @Path("iid") int iid
    );

    // Notes (comments)
    @GET("projects/{id}/merge_requests/{iid}/notes")
    Single<Response<List<GitLabComment>>> getComments(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Query("sort") String sort,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("projects/{id}/merge_requests/{iid}/notes")
    Single<Response<GitLabComment>> createComment(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    @PUT("projects/{id}/merge_requests/{iid}/notes/{noteId}")
    Single<Response<GitLabComment>> editComment(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("noteId") long noteId,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/merge_requests/{iid}/notes/{noteId}")
    Single<Response<Void>> deleteComment(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("noteId") long noteId
    );

    @GET("projects/{id}/merge_requests/{iid}/discussions")
    Single<Response<List<Map<String, Object>>>> getDiscussions(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Query("page") int page,
            @Query("per_page") int perPage
    );
}
