package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabAwardEmoji;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabDiscussion;
import com.gl4a.gitlab.model.GitLabIssue;

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

/**
 * A group's epics (#312), read into {@link GitLabIssue} as their fields match an issue's. GitLab
 * addresses an epic by its number (iid) for the epic itself, its reactions and its children,
 * but by its ID ({@code epicId}) for its comments.
 */
public interface GitLabEpicService {
    /** @param group the group's ID or its URL-encoded path */
    @GET("groups/{group}/epics?with_labels_details=true")
    Single<Response<List<GitLabIssue>>> listEpics(
            @Path(value = "group", encoded = true) String group,
            @Query("state") String state,
            @Query("search") String search,
            @Query("order_by") String orderBy,
            @Query("sort") String sort,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("groups/{group}/epics/{iid}?with_labels_details=true")
    Single<Response<GitLabIssue>> getEpic(
            @Path(value = "group", encoded = true) String group,
            @Path("iid") int iid
    );

    /** Child epics, one level down. */
    @GET("groups/{groupId}/epics/{iid}/epics?with_labels_details=true")
    Single<Response<List<GitLabIssue>>> getChildEpics(
            @Path("groupId") long groupId,
            @Path("iid") int iid
    );

    @GET("groups/{groupId}/epics/{iid}/issues?with_labels_details=true")
    Single<Response<List<GitLabIssue>>> getChildIssues(
            @Path("groupId") long groupId,
            @Path("iid") int iid,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("groups/{groupId}/epics/{epicId}/discussions")
    Single<Response<List<GitLabDiscussion>>> getDiscussions(
            @Path("groupId") long groupId,
            @Path("epicId") long epicId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("groups/{groupId}/epics/{epicId}/notes")
    Single<Response<GitLabComment>> createComment(
            @Path("groupId") long groupId,
            @Path("epicId") long epicId,
            @Body Map<String, Object> body
    );

    @POST("groups/{groupId}/epics/{epicId}/discussions/{discussionId}/notes")
    Single<Response<GitLabComment>> addDiscussionNote(
            @Path("groupId") long groupId,
            @Path("epicId") long epicId,
            @Path("discussionId") String discussionId,
            @Body Map<String, Object> body
    );

    @PUT("groups/{groupId}/epics/{epicId}/notes/{noteId}")
    Single<Response<GitLabComment>> editComment(
            @Path("groupId") long groupId,
            @Path("epicId") long epicId,
            @Path("noteId") long noteId,
            @Body Map<String, Object> body
    );

    @DELETE("groups/{groupId}/epics/{epicId}/notes/{noteId}")
    Single<Response<Void>> deleteComment(
            @Path("groupId") long groupId,
            @Path("epicId") long epicId,
            @Path("noteId") long noteId
    );

    @GET("groups/{groupId}/epics/{iid}/award_emoji")
    Single<Response<List<GitLabAwardEmoji>>> getAwardEmojis(
            @Path("groupId") long groupId,
            @Path("iid") int iid,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("groups/{groupId}/epics/{iid}/award_emoji")
    Single<Response<GitLabAwardEmoji>> addAwardEmoji(
            @Path("groupId") long groupId,
            @Path("iid") int iid,
            @Body Map<String, Object> body
    );

    @DELETE("groups/{groupId}/epics/{iid}/award_emoji/{awardId}")
    Single<Response<Void>> deleteAwardEmoji(
            @Path("groupId") long groupId,
            @Path("iid") int iid,
            @Path("awardId") long awardId
    );

    @GET("groups/{groupId}/epics/{iid}/notes/{noteId}/award_emoji")
    Single<Response<List<GitLabAwardEmoji>>> getNoteAwardEmojis(
            @Path("groupId") long groupId,
            @Path("iid") int iid,
            @Path("noteId") long noteId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @POST("groups/{groupId}/epics/{iid}/notes/{noteId}/award_emoji")
    Single<Response<GitLabAwardEmoji>> addNoteAwardEmoji(
            @Path("groupId") long groupId,
            @Path("iid") int iid,
            @Path("noteId") long noteId,
            @Body Map<String, Object> body
    );

    @DELETE("groups/{groupId}/epics/{iid}/notes/{noteId}/award_emoji/{awardId}")
    Single<Response<Void>> deleteNoteAwardEmoji(
            @Path("groupId") long groupId,
            @Path("iid") int iid,
            @Path("noteId") long noteId,
            @Path("awardId") long awardId
    );
}
