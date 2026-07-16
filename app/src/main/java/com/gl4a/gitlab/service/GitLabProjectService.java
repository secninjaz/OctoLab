package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabBranch;
import com.gl4a.gitlab.model.GitLabContributor;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTag;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabStarrer;

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
import retrofit2.http.QueryMap;

public interface GitLabProjectService {

    // List projects the authenticated user is a member of
    @GET("projects")
    Single<Response<List<GitLabProject>>> listProjects(
            @Query("membership") boolean membership,
            @Query("page") int page,
            @Query("per_page") int perPage,
            @Query("order_by") String orderBy,
            @Query("sort") String sort,
            @Query("visibility") String visibility,
            @Query("simple") boolean simple
    );

    // List projects with arbitrary query params (owned, starred, search, etc.)
    @GET("projects")
    Single<Response<List<GitLabProject>>> listProjectsWithParams(
            @QueryMap Map<String, String> params,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects")
    Single<Response<List<GitLabProject>>> searchProjects(
            @Query("search") String query,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Get project by numeric ID
    @GET("projects/{id}")
    Single<Response<GitLabProject>> getProject(@Path("id") long projectId);

    // Get project by namespace/name — id must be URL-encoded as "namespace%2Frepo"
    // Callers must pass URLEncoder.encode(namespace + "/" + repo, "UTF-8")
    @GET("projects/{id}")
    Single<Response<GitLabProject>> getProjectByPath(
            @Path(value = "id", encoded = true) String id
    );

    @GET("users/{username}/projects")
    Single<Response<List<GitLabProject>>> getUserProjects(
            @Path("username") String username,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/members")
    Single<Response<List<GitLabUser>>> getMembers(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/members/all")
    Single<Response<List<GitLabUser>>> getAllMembers(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/forks")
    Single<Response<List<GitLabProject>>> getForks(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Star a project — POST /projects/:id/star
    @POST("projects/{id}/star")
    Single<Response<GitLabProject>> starProject(@Path("id") long projectId);

    // Unstar a project — DELETE /projects/:id/star
    @POST("projects/{id}/unstar")
    Single<Response<GitLabProject>> unstarProject(@Path("id") long projectId);

    @GET("projects/{id}/starrers")
    Single<Response<List<GitLabStarrer>>> getStarrers(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Starred projects for the authenticated user — /projects?starred=true
    @GET("projects")
    Single<Response<List<GitLabProject>>> getStarredProjects(
            @Query("starred") boolean starred,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // "Watched" concept does not exist in GitLab; substitute with membership=true
    @GET("projects")
    Single<Response<List<GitLabProject>>> getWatchedProjects(
            @Query("membership") boolean membership,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Branches — correct path includes /repository/
    @GET("projects/{id}/repository/branches")
    Single<Response<List<GitLabBranch>>> getBranches(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Branch names with slashes must use encoded=true
    @GET("projects/{id}/repository/branches/{branch}")
    Single<Response<GitLabBranch>> getBranch(
            @Path("id") long projectId,
            @Path(value = "branch", encoded = true) String branch
    );

    @GET("projects/{id}/repository/tags")
    Single<Response<List<GitLabTag>>> getTags(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Git contributors — correct endpoint is /repository/contributors, returns GitLabContributor
    @GET("projects/{id}/repository/contributors")
    Single<Response<List<GitLabContributor>>> getContributors(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // Programming-language breakdown — returns Map<String, Float> (language -> percentage)
    @GET("projects/{id}/languages")
    Single<Response<Map<String, Float>>> getLanguages(
            @Path("id") long projectId
    );

    @POST("projects/{id}/fork")
    Single<Response<GitLabProject>> forkProject(
            @Path("id") long projectId,
            @Body Map<String, Object> body
    );
}
