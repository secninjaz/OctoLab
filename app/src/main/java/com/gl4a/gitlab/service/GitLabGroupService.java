package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabMember;
import com.gl4a.gitlab.model.GitLabProject;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabGroupService {
    @GET("groups")
    Single<Response<List<GitLabGroup>>> listGroups(
            @Query("owned") boolean owned,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    /** GET /users/:id/groups — groups the specified user belongs to. */
    @GET("users/{id}/groups")
    Single<Response<List<GitLabGroup>>> getUserGroups(
            @Path("id") long userId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("groups/{id}")
    Single<Response<GitLabGroup>> getGroup(@Path("id") long groupId);

    @GET("groups/{id}")
    Single<Response<GitLabGroup>> getGroupByPath(@Path(value = "id", encoded = true) String groupPath);

    @GET("groups/{id}/members")
    Single<Response<List<GitLabMember>>> getMembers(
            @Path("id") long groupId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("groups/{id}/members")
    Single<Response<List<GitLabMember>>> getMembersByPath(
            @Path(value = "id", encoded = true) String groupPath,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("groups/{id}/projects")
    Single<Response<List<GitLabProject>>> getProjects(
            @Path("id") long groupId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );
}
