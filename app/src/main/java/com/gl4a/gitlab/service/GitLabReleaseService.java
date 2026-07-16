package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabRelease;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabReleaseService {
    @GET("projects/{id}/releases")
    Single<Response<List<GitLabRelease>>> listReleases(
            @Path("id") long projectId,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    @GET("projects/{id}/releases/{tagName}")
    Single<Response<GitLabRelease>> getRelease(
            @Path("id") long projectId,
            @Path("tagName") String tagName
    );
}
