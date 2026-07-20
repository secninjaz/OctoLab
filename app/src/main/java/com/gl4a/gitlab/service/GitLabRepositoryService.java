package com.gl4a.gitlab.service;

import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabFile;
import com.gl4a.gitlab.model.GitLabTreeItem;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.HEAD;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GitLabRepositoryService {

    // GET /projects/:id/repository/tree
    // Returns: id, name, type ("blob"|"tree"), path, mode
    @GET("projects/{id}/repository/tree")
    Single<Response<List<GitLabTreeItem>>> getTree(
            @Path("id") long projectId,
            @Query("path") String path,
            @Query("ref") String ref,
            @Query("recursive") boolean recursive,
            @Query("page") int page,
            @Query("per_page") int perPage
    );

    // filePath must be URL-encoded by the caller (e.g. "src%2Fmain%2FFoo.java")
    @GET("projects/{id}/repository/files/{filePath}/raw")
    Single<Response<okhttp3.ResponseBody>> getRawFile(
            @Path("id") long projectId,
            @Path(value = "filePath", encoded = true) String filePath,
            @Query("ref") String ref
    );

    // filePath must be URL-encoded by the caller
    @GET("projects/{id}/repository/files/{filePath}")
    Single<Response<GitLabFile>> getFile(
            @Path("id") long projectId,
            @Path(value = "filePath", encoded = true) String filePath,
            @Query("ref") String ref
    );

    // HEAD request — returns X-Gitlab-Size header without downloading content.
    // Use this to get file sizes efficiently in the repository browser.
    @HEAD("projects/{id}/repository/files/{filePath}")
    Single<Response<Void>> getFileHead(
            @Path("id") long projectId,
            @Path(value = "filePath", encoded = true) String filePath,
            @Query("ref") String ref
    );

    @GET("projects/{id}/repository/archive")
    Single<Response<okhttp3.ResponseBody>> getArchive(
            @Path("id") long projectId,
            @Query("sha") String sha,
            @Query("format") String format
    );

    // Get the last commit that touched a specific file path on a given ref
    @GET("projects/{id}/repository/commits/{sha}")
    Single<Response<GitLabCommit>> getCommit(
            @Path("id") long projectId,
            @Path("sha") String sha
    );

    // List commits for a specific file path (use path + per_page=1 for "last commit on file")
    @GET("projects/{id}/repository/commits")
    Single<Response<List<GitLabCommit>>> getFileCommits(
            @Path("id") long projectId,
            @Query("ref_name") String refName,
            @Query("path") String filePath,
            @Query("page") int page,
            @Query("per_page") int perPage
    );
}
