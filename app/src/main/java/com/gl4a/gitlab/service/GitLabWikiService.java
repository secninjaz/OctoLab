package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabWikiPage;

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

public interface GitLabWikiService {

    // List all wiki pages for a project
    // with_content=true includes the page body; omit for lightweight list
    @GET("projects/{id}/wikis")
    Single<Response<List<GitLabWikiPage>>> listWikiPages(
            @Path("id") long projectId,
            @Query("with_content") boolean withContent
    );

    // Get a single wiki page by its slug
    // slug: the URL-friendly page identifier (e.g. "home", "getting-started")
    @GET("projects/{id}/wikis/{slug}")
    Single<Response<GitLabWikiPage>> getWikiPage(
            @Path("id") long projectId,
            @Path(value = "slug", encoded = true) String slug
    );

    // Create a new wiki page
    // Body keys: title (String), content (String), format ("markdown"|"rdoc"|"asciidoc"|"org")
    @POST("projects/{id}/wikis")
    Single<Response<GitLabWikiPage>> createWikiPage(
            @Path("id") long projectId,
            @Body Map<String, Object> body
    );

    // Edit an existing wiki page
    // Body keys: title (String), content (String), format (String)
    @PUT("projects/{id}/wikis/{slug}")
    Single<Response<GitLabWikiPage>> editWikiPage(
            @Path("id") long projectId,
            @Path(value = "slug", encoded = true) String slug,
            @Body Map<String, Object> body
    );

    // Delete a wiki page
    @DELETE("projects/{id}/wikis/{slug}")
    Single<Response<Void>> deleteWikiPage(
            @Path("id") long projectId,
            @Path(value = "slug", encoded = true) String slug
    );
}
