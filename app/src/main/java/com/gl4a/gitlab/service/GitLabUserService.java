package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabUser;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabUserService {

    // Authenticated user
    @GET("user")
    Single<Response<GitLabUser>> getCurrentUser();

    @GET("users/{id}")
    Single<Response<GitLabUser>> getUser(@Path("id") long userId);

    // Search users — GET /users?username=xxx returns a list; take the first element for exact match.
    // Also accepts ?search=xxx for partial name/email matching.
    @GET("users")
    Single<Response<List<GitLabUser>>> searchUsers(
            @Query("username") String username,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("users/{id}/projects")
    Single<Response<List<GitLabProject>>> getUserProjects(
            @Path("id") long userId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Starred projects for a specific user — correct endpoint is /users/:id/starred_projects
    // NOT /users/:id/starred (that path does not exist in GitLab API v4)
    @GET("users/{id}/starred_projects")
    Single<Response<List<GitLabProject>>> getStarredProjects(
            @Path("id") long userId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Projects the user has contributed to (has at least one commit)
    @GET("users/{id}/contributed_projects")
    Single<Response<List<GitLabProject>>> getContributedProjects(
            @Path("id") long userId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // User events
    @GET("users/{id}/events")
    Single<Response<List<GitLabEvent>>> getUserEvents(
            @Path("id") long userId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Events for the authenticated user (personal activity)
    @GET("events")
    Single<Response<List<GitLabEvent>>> getCurrentUserEvents(
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Events from a specific project
    @retrofit2.http.GET("projects/{id}/events")
    Single<Response<List<GitLabEvent>>> getProjectEvents(
            @retrofit2.http.Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // All events visible to the authenticated user across accessible projects.
    // GitLab does not expose a truly public unauthenticated event feed; this endpoint
    // returns the broadest activity stream available for the authenticated user.
    // Equivalent to GET /events — identical scope to getCurrentUserEvents because
    // GitLab API v4 does not distinguish "public" from "personal" at this endpoint.
    // Kept as a separate method so callers can be updated independently if GitLab
    // introduces a separate public-events path in the future.
    @GET("events")
    Single<Response<List<GitLabEvent>>> listAllEvents(
            @Query("page") int page,
            @Query("per_page") int perPage
    );
}
