package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabSnippet;

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

public interface GitLabSnippetService {

    @GET("snippets")
    Single<Response<List<GitLabSnippet>>> listMySnippets(
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("snippets/public")
    Single<Response<List<GitLabSnippet>>> listPublicSnippets(
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("snippets/{id}")
    Single<Response<GitLabSnippet>> getSnippet(@Path("id") long snippetId);

    // Legacy single-file raw content — keep for backwards compat with GitLab < 14.0
    @GET("snippets/{id}/raw")
    Single<Response<okhttp3.ResponseBody>> getRawSnippet(@Path("id") long snippetId);

    // Multi-file snippet raw content (GitLab 14.0+)
    // ref:      branch/tag/commit SHA (e.g. "main")
    // filePath: must be URL-encoded by the caller
    @GET("snippets/{id}/files/{ref}/{filePath}/raw")
    Single<Response<okhttp3.ResponseBody>> getSnippetFileRaw(
            @Path("id") long snippetId,
            @Path("ref") String ref,
            @Path(value = "filePath", encoded = true) String filePath
    );

    @POST("snippets")
    Single<Response<GitLabSnippet>> createSnippet(@Body Map<String, Object> body);

    @PUT("snippets/{id}")
    Single<Response<GitLabSnippet>> editSnippet(
            @Path("id") long snippetId,
            @Body Map<String, Object> body
    );

    @DELETE("snippets/{id}")
    Single<Response<Void>> deleteSnippet(@Path("id") long snippetId);

    @GET("users/{userId}/snippets")
    Single<Response<List<GitLabSnippet>>> getUserSnippets(
            @Path("userId") long userId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );
}
