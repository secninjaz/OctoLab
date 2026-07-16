package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabLabel;
import com.gl4a.gitlab.model.GitLabMilestone;

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

public interface GitLabIssueService {

    // state must be "opened" or "closed" — GitLab API v4 does not accept "open"
    @GET("projects/{id}/issues")
    Single<Response<List<GitLabIssue>>> listIssues(
            @Path("id") long projectId,
            @Query("state") String state,       // "opened" | "closed" | "all"
            @Query("labels") String labels,
            @Query("milestone") String milestone,
            @Query("assignee_username") String assignee,
            @Query("page") int page,
            @Query("per_page") int perPage,
            @Query("order_by") String orderBy,
            @Query("sort") String sort,
            @Query("search") String search
    );

    // state must be "opened" or "closed" — GitLab API v4 does not accept "open"
    @GET("issues")
    Single<Response<List<GitLabIssue>>> listMyIssues(
            @Query("state") String state,       // "opened" | "closed" | "all"
            @Query("scope") String scope,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/issues/{iid}")
    Single<Response<GitLabIssue>> getIssue(
            @Path("id") long projectId,
            @Path("iid") int iid
    );

    // Body should use "assignee_ids" (int array), not "assignee_id" (scalar)
    // Body keys: title, description, assignee_ids (List<Long>), milestone_id,
    //            labels (comma-separated string), due_date, confidential
    @POST("projects/{id}/issues")
    Single<Response<GitLabIssue>> createIssue(
            @Path("id") long projectId,
            @Body Map<String, Object> body
    );

    // To close: include "state_event" = "close" in body
    // To reopen: include "state_event" = "reopen" in body
    // Body keys: title, description, assignee_ids, milestone_id, labels,
    //            due_date, state_event, confidential
    @PUT("projects/{id}/issues/{iid}")
    Single<Response<GitLabIssue>> editIssue(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    // Issue notes (comments)
    @GET("projects/{id}/issues/{iid}/notes")
    Single<Response<List<GitLabComment>>> getComments(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Query("sort") String sort,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("projects/{id}/issues/{iid}/notes")
    Single<Response<GitLabComment>> createComment(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    @PUT("projects/{id}/issues/{iid}/notes/{noteId}")
    Single<Response<GitLabComment>> editComment(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("noteId") long noteId,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/issues/{iid}/notes/{noteId}")
    Single<Response<Void>> deleteComment(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("noteId") long noteId
    );

    // Labels — POST body: name (String), color (hex with leading #), description (String)
    @GET("projects/{id}/labels")
    Single<Response<List<GitLabLabel>>> getLabels(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("projects/{id}/labels")
    Single<Response<GitLabLabel>> createLabel(
            @Path("id") long projectId,
            @Body Map<String, Object> body
    );

    @PUT("projects/{id}/labels/{labelId}")
    Single<Response<GitLabLabel>> editLabel(
            @Path("id") long projectId,
            @Path("labelId") long labelId,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/labels/{labelId}")
    Single<Response<Void>> deleteLabel(
            @Path("id") long projectId,
            @Path("labelId") long labelId
    );

    // Milestones — state must be "active" or "closed" (not "open")
    @GET("projects/{id}/milestones")
    Single<Response<List<GitLabMilestone>>> getMilestones(
            @Path("id") long projectId,
            @Query("state") String state,       // "active" | "closed"
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/milestones/{milestoneId}")
    Single<Response<GitLabMilestone>> getMilestone(
            @Path("id") long projectId,
            @Path("milestoneId") long milestoneId
    );

    // Body keys: title, description, due_date (YYYY-MM-DD), start_date (YYYY-MM-DD)
    @POST("projects/{id}/milestones")
    Single<Response<GitLabMilestone>> createMilestone(
            @Path("id") long projectId,
            @Body Map<String, Object> body
    );

    // To close milestone: include "state_event" = "close" in body
    // To reopen milestone: include "state_event" = "activate" in body
    @PUT("projects/{id}/milestones/{milestoneId}")
    Single<Response<GitLabMilestone>> editMilestone(
            @Path("id") long projectId,
            @Path("milestoneId") long milestoneId,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/milestones/{milestoneId}")
    Single<Response<Void>> deleteMilestone(
            @Path("id") long projectId,
            @Path("milestoneId") long milestoneId
    );

    @GET("projects/{id}/milestones/{milestoneId}/issues")
    Single<Response<List<GitLabIssue>>> getMilestoneIssues(
            @Path("id") long projectId,
            @Path("milestoneId") long milestoneId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );
}
