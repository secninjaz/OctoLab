package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabAwardEmoji;

import java.util.List;
import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabAwardEmojiService {

    // ---- Issue award emoji ----

    @GET("projects/{id}/issues/{iid}/award_emoji")
    Single<Response<List<GitLabAwardEmoji>>> getIssueAwardEmojis(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Body: { "name": "thumbsup" }  (emoji name without colons)
    @POST("projects/{id}/issues/{iid}/award_emoji")
    Single<Response<GitLabAwardEmoji>> addIssueAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/issues/{iid}/award_emoji/{awardId}")
    Single<Response<Void>> deleteIssueAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("awardId") long awardId
    );

    // ---- Merge request award emoji ----

    @GET("projects/{id}/merge_requests/{iid}/award_emoji")
    Single<Response<List<GitLabAwardEmoji>>> getMergeRequestAwardEmojis(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Body: { "name": "thumbsup" }
    @POST("projects/{id}/merge_requests/{iid}/award_emoji")
    Single<Response<GitLabAwardEmoji>> addMergeRequestAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/merge_requests/{iid}/award_emoji/{awardId}")
    Single<Response<Void>> deleteMergeRequestAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("awardId") long awardId
    );

    // ---- Issue note (comment) award emoji ----

    @GET("projects/{id}/issues/{iid}/notes/{note_id}/award_emoji")
    Single<Response<List<GitLabAwardEmoji>>> getIssueNoteAwardEmojis(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("note_id") long noteId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("projects/{id}/issues/{iid}/notes/{note_id}/award_emoji")
    Single<Response<GitLabAwardEmoji>> addIssueNoteAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("note_id") long noteId,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/issues/{iid}/notes/{note_id}/award_emoji/{award_id}")
    Single<Response<Void>> deleteIssueNoteAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("note_id") long noteId,
            @Path("award_id") long awardId
    );

    // ---- Merge Request note (comment) award emoji ----

    @GET("projects/{id}/merge_requests/{iid}/notes/{note_id}/award_emoji")
    Single<Response<List<GitLabAwardEmoji>>> getMergeRequestNoteAwardEmojis(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("note_id") long noteId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("projects/{id}/merge_requests/{iid}/notes/{note_id}/award_emoji")
    Single<Response<GitLabAwardEmoji>> addMergeRequestNoteAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("note_id") long noteId,
            @Body Map<String, Object> body
    );

    @DELETE("projects/{id}/merge_requests/{iid}/notes/{note_id}/award_emoji/{award_id}")
    Single<Response<Void>> deleteMergeRequestNoteAwardEmoji(
            @Path("id") long projectId,
            @Path("iid") int iid,
            @Path("note_id") long noteId,
            @Path("award_id") long awardId
    );
}